package dev.overseersmp.overseer;

/** Validates a prayer before any API call. Rejected prayers get an in-character refusal and cost nothing. */
public final class InputFilter {
    public enum Verdict { OK, EMPTY, TOO_LONG, BLOCKED }

    private final ContentFilter filter;
    private final int maxChars;

    public InputFilter(Settings settings, ContentFilter filter) {
        this.filter = filter;
        this.maxChars = settings.maxPrayerChars;
    }

    /** Trim, drop control characters and colour-code sections, collapse whitespace. */
    public static String clean(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder(raw.length());
        raw.codePoints().forEach(cp -> {
            if (cp == '§' || Character.isISOControl(cp)) sb.append(' ');
            else sb.appendCodePoint(cp);
        });
        return sb.toString().replaceAll("\\s+", " ").trim();
    }

    public Verdict check(String cleaned) {
        if (cleaned == null || cleaned.isEmpty() || cleaned.codePoints().noneMatch(Character::isLetterOrDigit)) return Verdict.EMPTY;
        if (cleaned.codePointCount(0, cleaned.length()) > maxChars) return Verdict.TOO_LONG;
        if (filter.isBlocked(cleaned)) return Verdict.BLOCKED;
        return Verdict.OK;
    }
}
