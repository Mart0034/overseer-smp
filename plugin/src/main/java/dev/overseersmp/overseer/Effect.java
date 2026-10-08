package dev.overseersmp.overseer;

/** A fully validated, bounded effect. Nothing outside this set can ever be applied to a player. */
public sealed interface Effect {
    record None() implements Effect {}

    /** Level I potion effect; key is the vanilla effect key, e.g. "speed". */
    record Potion(String key, int amplifier, int seconds) implements Effect {}

    record Gift(String material, int amount) implements Effect {}

    record Chicken() implements Effect {}

    record Lightning() implements Effect {}

    None NONE = new None();
}
