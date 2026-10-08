package dev.overseersmp.overseer;

public enum Action {
    BLESS, CURSE, SMITE, NONE;

    /** Unknown or missing strings become NONE: an unrecognised action does nothing. */
    public static Action parse(String s) {
        if (s == null) return NONE;
        return switch (s.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "bless" -> BLESS;
            case "curse" -> CURSE;
            case "smite" -> SMITE;
            default -> NONE;
        };
    }
}
