package dev.overseersmp.overseer.decree;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import dev.overseersmp.overseer.ContentFilter;
import dev.overseersmp.overseer.InputFilter;
import dev.overseersmp.overseer.ResponseParser;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the model's decree answer into a validated choice. Same stance as the prayer parser: the text is hostile.
 * Expected JSON (flat, so it fits a strict schema): {"decree": "...", "modifier": "<id>", "second_modifier": "<id>|none",
 * "scale": n, "interval_seconds": n, "spawn_multiplier": n, "drop_multiplier": n, "jump_level": n}.
 * Unknown modifiers fail the parse (the caller falls back); incompatible or unknown second modifiers are dropped;
 * every parameter is clamped into the bounds of the modifier that owns it.
 */
public final class DecreeParser {
    public record Parsed(List<ActiveDecree.Selected> selected, String text) {}

    public record Result(Parsed parsed, String problem) {
        public boolean ok() { return parsed != null; }
    }

    private final Map<String, Modifier> registry;
    private final ContentFilter filter;
    private final int maxTextChars;

    public DecreeParser(Map<String, Modifier> registry, ContentFilter filter, int maxTextChars) {
        this.registry = registry;
        this.filter = filter;
        this.maxTextChars = maxTextChars;
    }

    public Result parse(String raw) {
        if (raw == null || raw.isBlank()) return fail("empty");
        if (raw.length() > ResponseParser.MAX_RAW) return fail("too long");
        String json = ResponseParser.extractObject(raw);
        if (json == null) return fail("no json object");
        JsonObject o;
        try {
            JsonReader r = new JsonReader(new StringReader(json));
            r.setStrictness(Strictness.STRICT);
            JsonElement e = JsonParser.parseReader(r);
            if (!e.isJsonObject()) return fail("not an object");
            o = e.getAsJsonObject();
        } catch (JsonParseException | IllegalStateException ex) {
            return fail("malformed json");
        }
        Modifier first = registry.get(str(o, "modifier"));
        if (first == null) return fail("unknown modifier");
        String text = sanitise(rawStr(o, "decree"));
        if (text.isEmpty()) return fail("missing decree text");
        if (filter.isBlocked(text) || filter.mentionsMoneyOrLinks(text)) return fail("unsafe text");

        Map<String, Object> numbers = new HashMap<>();
        for (var e : o.entrySet()) {
            JsonElement v = e.getValue();
            if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber()) numbers.put(e.getKey(), v.getAsDouble());
        }
        List<ActiveDecree.Selected> sel = new ArrayList<>();
        sel.add(new ActiveDecree.Selected(first.id(), first.clamp(numbers)));
        Modifier second = registry.get(str(o, "second_modifier"));
        if (second != null && !second.id().equals(first.id()) && first.compatibleWith(second.id()) && second.compatibleWith(first.id())) {
            sel.add(new ActiveDecree.Selected(second.id(), second.clamp(numbers)));
        }
        return new Result(new Parsed(List.copyOf(sel), text), null);
    }

    private String sanitise(String s) {
        if (s == null) return "";
        String c = InputFilter.clean(s);
        if (c.codePointCount(0, c.length()) > maxTextChars) c = c.substring(0, c.offsetByCodePoints(0, maxTextChars - 1)).stripTrailing() + "…";
        return c;
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString().trim().toLowerCase(java.util.Locale.ROOT) : null;
    }

    /** The string as written (decree text keeps its case); ids use {@link #str}, which lower-cases. */
    private static String rawStr(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : null;
    }

    private static Result fail(String why) { return new Result(null, why); }
}
