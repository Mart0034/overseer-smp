package dev.overseersmp.overseer.decree;

import java.util.List;
import java.util.Map;

/** A decree as persisted to disk. {@code state} is the modifiers' own restore data (see {@link Modifier}). */
public record ActiveDecree(String text, String source, long startedAt, long expiresAt, List<Selected> selected, Map<String, String> state) {
    /** One chosen modifier with its (already clamped) parameters. */
    public record Selected(String id, Map<String, Double> params) {}
}
