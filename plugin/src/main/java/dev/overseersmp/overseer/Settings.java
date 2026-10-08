package dev.overseersmp.overseer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Immutable snapshot of config.yml. Every limit, timeout and the model name come from here. The API key is never printed. */
public final class Settings {
    public final String model, apiUrl, apiVersion;
    public final int maxTokens, timeoutSeconds;
    public final boolean structuredOutput, silenced;
    public final double monthlyCapUsd, priceInPerMtok, priceOutPerMtok;
    public final int maxPrayerChars, perPlayerPerDay, cooldownSeconds, serverPerDay, maxReplyChars, history;
    public final Map<String, Integer> durations;
    public final List<String> extraWords;
    private final String apiKey;

    private Settings(Map<String, Object> m) {
        this.apiKey = str(m, "anthropic.api-key", "");
        this.model = str(m, "anthropic.model", "claude-haiku-5-5");
        this.apiUrl = str(m, "anthropic.api-url", "https://api.anthropic.com/v1/messages");
        this.apiVersion = str(m, "anthropic.version", "2023-06-01");
        this.maxTokens = (int) num(m, "anthropic.max-tokens", 150, 16, 1000);
        this.timeoutSeconds = (int) num(m, "anthropic.timeout-seconds", 15, 1, 120);
        this.structuredOutput = bool(m, "anthropic.structured-output", true);
        this.monthlyCapUsd = num(m, "anthropic.monthly-cap-usd", 8.0, 0, 1000);
        this.priceInPerMtok = num(m, "anthropic.price-per-mtok-input", 0.10, 0, 1000);
        this.priceOutPerMtok = num(m, "anthropic.price-per-mtok-output", 0.50, 0, 1000);
        this.maxPrayerChars = (int) num(m, "prayers.max-chars", 200, 1, 1000);
        this.perPlayerPerDay = (int) num(m, "prayers.per-player-per-day", 3, 0, 1000);
        this.cooldownSeconds = (int) num(m, "prayers.cooldown-seconds", 60, 0, 86400);
        this.serverPerDay = (int) num(m, "prayers.server-per-day", 600, 0, 100000);
        this.maxReplyChars = (int) num(m, "prayers.max-reply-chars", 220, 20, 500);
        this.history = (int) num(m, "prayers.history", 3, 0, 10);
        this.silenced = bool(m, "silenced", false);
        Map<String, Integer> d = new HashMap<>();
        String p = "effects.durations-seconds.";
        for (var e : m.entrySet()) {
            if (e.getKey().startsWith(p) && e.getValue() instanceof Number n) d.put(e.getKey().substring(p.length()), n.intValue());
        }
        this.durations = Map.copyOf(d);
        List<String> w = new ArrayList<>();
        if (m.get("filter.extra-words") instanceof List<?> l) for (Object o : l) if (o != null) w.add(String.valueOf(o));
        this.extraWords = List.copyOf(w);
    }

    /** @param flat keys like "prayers.max-chars" (what ConfigurationSection#getValues(true) returns) */
    public static Settings from(Map<String, Object> flat) { return new Settings(flat); }

    public static Settings defaults() { return new Settings(Map.of()); }

    public static Settings with(Map<String, Object> overrides) { return new Settings(overrides); }

    public String apiKey() { return apiKey; }

    public boolean hasKey() { return apiKey != null && apiKey.length() >= 20; }

    private static String str(Map<String, Object> m, String k, String def) {
        Object o = m.get(k);
        return o == null ? def : String.valueOf(o).trim();
    }

    private static boolean bool(Map<String, Object> m, String k, boolean def) {
        Object o = m.get(k);
        return o instanceof Boolean b ? b : o == null ? def : Boolean.parseBoolean(String.valueOf(o));
    }

    private static double num(Map<String, Object> m, String k, double def, double min, double max) {
        Object o = m.get(k);
        double v = o instanceof Number n ? n.doubleValue() : def;
        if (Double.isNaN(v)) v = def;
        return Math.max(min, Math.min(max, v));
    }

    @Override public String toString() {
        return "Settings[model=" + model + ", maxTokens=" + maxTokens + ", key=" + (hasKey() ? "set" : "missing") + "]";
    }
}
