package dev.overseersmp.overseer.decree;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A coded world modifier. The model may only choose a modifier id and parameter values; values are clamped to {@link #bounds()}.
 * <p>
 * Contract: {@code enable} and {@code disable} must be idempotent and must fully restore state. Anything a modifier needs to
 * remember in order to restore the world (for example a game rule's previous value) goes into {@code state}, which the engine
 * persists immediately, so a restart in the middle of a decree can still restore it.
 */
public interface Modifier {
    /** Parameter bound: clamp(min, max) with a default for missing or invalid values. */
    record Bound(double min, double max, double def) {
        public double clamp(Object v) {
            double d = v instanceof Number n ? n.doubleValue() : def;
            if (Double.isNaN(d) || Double.isInfinite(d)) d = def;
            return Math.max(min, Math.min(max, d));
        }
    }

    String id();

    String displayName();

    String description();

    /** Parameter name -> bound. Names are shared across modifiers (scale, interval_seconds, spawn_multiplier, drop_multiplier, jump_level). */
    Map<String, Bound> bounds();

    /** Whether this modifier may run together with another one (both sides must agree). */
    default boolean compatibleWith(String otherId) { return !otherId.equals(id()); }

    /** Relative weight for the random fallback. */
    default int weight() { return 10; }

    void enable(Map<String, Double> params, Map<String, String> state);

    void disable(Map<String, Double> params, Map<String, String> state);

    /** Called every few seconds while active. */
    default void tick() {}

    /** Parameter values clamped into bounds; unknown names are dropped, missing ones get the default. */
    default Map<String, Double> clamp(Map<String, ?> raw) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (var e : bounds().entrySet()) out.put(e.getKey(), e.getValue().clamp(raw == null ? null : raw.get(e.getKey())));
        return out;
    }

    default Map<String, Double> defaults() { return clamp(Map.of()); }
}
