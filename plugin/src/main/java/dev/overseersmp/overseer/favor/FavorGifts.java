package dev.overseersmp.overseer.favor;

import dev.overseersmp.overseer.Action;
import java.util.List;
import java.util.Optional;
import java.util.Random;

/**
 * At decree time: Faithful or better (favor >= 25) get a small random blessing, Heretics (favor <= -50) a small curse.
 * Only mild whitelist effects: no blindness, no lightning. The effect itself still goes through EffectCatalog (bounded durations).
 */
public final class FavorGifts {
    public record Gift(Action action, String effectId, String message) {}

    public static final int BLESS_FROM = FavorTitle.FAITHFUL.from;
    public static final int CURSE_AT_OR_BELOW = -50;

    static final List<String> BLESSINGS = List.of("speed", "haste", "regeneration", "luck", "night_vision", "jump_boost",
            "gift_bread", "gift_torches", "gift_beef", "gift_golden_carrots");
    static final List<String> CURSES = List.of("slowness", "hunger", "mining_fatigue", "glowing", "chicken");

    private FavorGifts() {}

    public static Optional<Gift> pick(int favor, Random rnd) {
        if (favor >= BLESS_FROM) {
            return Optional.of(new Gift(Action.BLESS, BLESSINGS.get(rnd.nextInt(BLESSINGS.size())),
                    "The Overseer smiles on its " + FavorTitle.forFavor(favor).display.toLowerCase() + " pilgrims. A small gift for you."));
        }
        if (favor <= CURSE_AT_OR_BELOW) {
            return Optional.of(new Gift(Action.CURSE, CURSES.get(rnd.nextInt(CURSES.size())),
                    "The Overseer has not forgotten your doubt, heretic. A small reminder."));
        }
        return Optional.empty();
    }
}
