package dev.overseersmp.overseer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The whitelist (CLAUDE.md section 6). The model only ever names an id; durations and amounts come from here,
 * are configurable downwards, and are clamped to the hard maximums below. Never damage, inventory loss or teleport.
 */
public final class EffectCatalog {
    private enum Kind { POTION, GIFT, CHICKEN, LIGHTNING }

    private record Entry(Action action, String id, Kind kind, String key, int amount, int defaultSeconds, int maxSeconds, String text) {}

    private static final Map<String, Entry> ENTRIES = new LinkedHashMap<>();

    private static void potion(Action a, String id, int def, int max, String text) {
        ENTRIES.put(id, new Entry(a, id, Kind.POTION, id.equals("jump_boost") ? "jump_boost" : id, 0, def, max, text));
    }

    private static void gift(String id, String material, int amount, String text) {
        ENTRIES.put(id, new Entry(Action.BLESS, id, Kind.GIFT, material, amount, 0, 0, text));
    }

    static {
        potion(Action.BLESS, "speed", 120, 300, "Speed I");
        potion(Action.BLESS, "haste", 120, 300, "Haste I");
        potion(Action.BLESS, "regeneration", 30, 300, "Regeneration I");
        potion(Action.BLESS, "luck", 300, 300, "Luck");
        potion(Action.BLESS, "night_vision", 300, 300, "Night Vision");
        potion(Action.BLESS, "jump_boost", 120, 300, "Jump Boost I");
        gift("gift_bread", "BREAD", 8, "8 bread");
        gift("gift_torches", "TORCH", 16, "16 torches");
        gift("gift_beef", "COOKED_BEEF", 4, "4 cooked beef");
        gift("gift_golden_carrots", "GOLDEN_CARROT", 2, "2 golden carrots");
        potion(Action.CURSE, "slowness", 60, 120, "Slowness I");
        potion(Action.CURSE, "hunger", 60, 120, "Hunger I");
        potion(Action.CURSE, "mining_fatigue", 60, 120, "Mining Fatigue I");
        potion(Action.CURSE, "glowing", 60, 120, "Glowing");
        potion(Action.CURSE, "blindness", 5, 5, "Blindness (5 s)");
        ENTRIES.put("chicken", new Entry(Action.CURSE, "chicken", Kind.CHICKEN, null, 0, 0, 0, "one harmless chicken dropped on their head"));
        ENTRIES.put("lightning", new Entry(Action.SMITE, "lightning", Kind.LIGHTNING, null, 0, 0, 0, "lightning effect, no damage, no fire"));
    }

    private EffectCatalog() {}

    /** Returns {@link Effect#NONE} unless the id is on the whitelist AND belongs to the given action. */
    public static Effect resolve(Action action, String id, Settings settings) {
        if (action == null || action == Action.NONE || id == null) return Effect.NONE;
        Entry e = ENTRIES.get(id.trim().toLowerCase(java.util.Locale.ROOT));
        if (e == null || e.action != action) return Effect.NONE;
        return switch (e.kind) {
            case POTION -> {
                int s = settings.durations.getOrDefault(e.id, e.defaultSeconds);
                yield new Effect.Potion(e.key, 0, Math.max(1, Math.min(e.maxSeconds, s)));
            }
            case GIFT -> new Effect.Gift(e.key, e.amount);
            case CHICKEN -> new Effect.Chicken();
            case LIGHTNING -> new Effect.Lightning();
        };
    }

    public static Set<String> allIds() { return ENTRIES.keySet(); }

    public static List<String> ids(Action a) {
        List<String> out = new ArrayList<>();
        for (Entry e : ENTRIES.values()) if (e.action == a) out.add(e.id);
        return out;
    }

    /** Text injected into the system prompt so the model knows exactly what it may choose. */
    public static String describe() {
        StringBuilder sb = new StringBuilder();
        for (Action a : new Action[] {Action.BLESS, Action.CURSE, Action.SMITE}) {
            sb.append("action \"").append(a.name().toLowerCase(java.util.Locale.ROOT)).append("\" -> effect must be one of:\n");
            for (Entry e : ENTRIES.values()) if (e.action == a) sb.append("  - ").append(e.id).append(": ").append(e.text).append('\n');
        }
        sb.append("action \"none\" -> effect must be \"none\".\n");
        return sb.toString();
    }
}
