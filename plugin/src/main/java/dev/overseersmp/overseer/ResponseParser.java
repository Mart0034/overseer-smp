package dev.overseersmp.overseer;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import java.io.StringReader;

/**
 * Turns the model's raw text into a {@link Decision}, or a problem. Treats the text as hostile:
 * unknown actions/effects become "no effect", favor is clamped, replies are sanitised, filtered and truncated,
 * unknown JSON fields (e.g. a "command") are ignored.
 */
public final class ResponseParser {
    public static final int MAX_RAW = 20_000;
    public static final int FAVOR_MAX = 10;

    public record Result(Decision decision, String problem) {
        public boolean ok() { return decision != null; }
    }

    private final Settings settings;
    private final ContentFilter filter;

    public ResponseParser(Settings settings, ContentFilter filter) {
        this.settings = settings;
        this.filter = filter;
    }

    public Result parse(String raw) {
        if (raw == null || raw.isBlank()) return fail("empty");
        if (raw.length() > MAX_RAW) return fail("too long");
        String json = extractObject(raw);
        if (json == null) return fail("no json object");
        JsonObject o;
        try {
            JsonReader reader = new JsonReader(new StringReader(json));
            reader.setStrictness(Strictness.STRICT);   // no unquoted keys, no single quotes, no trailing junk
            JsonElement e = JsonParser.parseReader(reader);
            if (!e.isJsonObject()) return fail("not an object");
            o = e.getAsJsonObject();
        } catch (JsonParseException | IllegalStateException ex) {
            return fail("malformed json");
        }
        String reply = sanitiseReply(str(o, "reply"));
        if (reply.isEmpty()) return fail("missing reply");
        if (filter.isBlocked(reply) || filter.mentionsMoneyOrLinks(reply)) return fail("unsafe reply");

        String rawAction = clip(str(o, "action"));
        String rawEffect = clip(str(o, "effect"));
        Action action = Action.parse(rawAction);
        Effect effect = EffectCatalog.resolve(action, rawEffect, settings);
        if (effect instanceof Effect.None) action = Action.NONE;   // outside the whitelist (or mismatched): nothing happens
        String effectId = effect instanceof Effect.None ? null : rawEffect.toLowerCase(java.util.Locale.ROOT);
        return new Result(new Decision(reply, action, effectId, effect, favor(o), rawAction, rawEffect), null);
    }

    private Result fail(String why) { return new Result(null, why); }

    private String sanitiseReply(String s) {
        if (s == null) return "";
        String c = InputFilter.clean(s);
        int max = settings.maxReplyChars;
        if (c.codePointCount(0, c.length()) > max) {
            c = c.substring(0, c.offsetByCodePoints(0, max - 1)).stripTrailing() + "…";
        }
        return c;
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : null;
    }

    private static String clip(String s) {
        if (s == null) return "";
        String c = InputFilter.clean(s);
        return c.length() > 40 ? c.substring(0, 40) : c;
    }

    private static int favor(JsonObject o) {
        JsonElement e = o.get("favor_delta");
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) return 0;
        double d = e.getAsDouble();
        if (Double.isNaN(d) || Double.isInfinite(d)) return 0;
        return (int) Math.max(-FAVOR_MAX, Math.min(FAVOR_MAX, Math.round(d)));
    }

    /** First balanced {...} in the text (tolerates code fences or chatter around it); null if none. */
    static String extractObject(String s) {
        int start = s.indexOf('{');
        if (start < 0) return null;
        int depth = 0;
        boolean inStr = false, esc = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (esc) esc = false;
                else if (c == '\\') esc = true;
                else if (c == '"') inStr = false;
            } else if (c == '"') inStr = true;
            else if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return s.substring(start, i + 1);
        }
        return null;
    }
}
