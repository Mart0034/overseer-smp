package dev.overseersmp.overseer;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

class EffectCatalogTest {
    private final Settings defaults = Settings.defaults();

    @Test void everyIdResolvesUnderItsOwnActionOnly() {
        for (Action a : new Action[] {Action.BLESS, Action.CURSE, Action.SMITE}) {
            for (String id : EffectCatalog.ids(a)) {
                assertFalse(EffectCatalog.resolve(a, id, defaults) instanceof Effect.None, id);
                for (Action other : new Action[] {Action.BLESS, Action.CURSE, Action.SMITE, Action.NONE}) {
                    if (other != a) assertInstanceOf(Effect.None.class, EffectCatalog.resolve(other, id, defaults), id + " under " + other);
                }
            }
        }
    }

    @Test void whitelistMatchesTheSpec() {
        assertEquals(java.util.List.of("speed", "haste", "regeneration", "luck", "night_vision", "jump_boost",
                "gift_bread", "gift_torches", "gift_beef", "gift_golden_carrots"), EffectCatalog.ids(Action.BLESS));
        assertEquals(java.util.List.of("slowness", "hunger", "mining_fatigue", "glowing", "blindness", "chicken"), EffectCatalog.ids(Action.CURSE));
        assertEquals(java.util.List.of("lightning"), EffectCatalog.ids(Action.SMITE));
    }

    @Test void giftsAreFixed() {
        assertEquals(new Effect.Gift("BREAD", 8), EffectCatalog.resolve(Action.BLESS, "gift_bread", defaults));
        assertEquals(new Effect.Gift("TORCH", 16), EffectCatalog.resolve(Action.BLESS, "gift_torches", defaults));
        assertEquals(new Effect.Gift("COOKED_BEEF", 4), EffectCatalog.resolve(Action.BLESS, "gift_beef", defaults));
        assertEquals(new Effect.Gift("GOLDEN_CARROT", 2), EffectCatalog.resolve(Action.BLESS, "gift_golden_carrots", defaults));
    }

    @Test void durationsAreClampedToHardMaximums() {
        var huge = Settings.with(Map.of("effects.durations-seconds.speed", 99999, "effects.durations-seconds.slowness", 99999,
                "effects.durations-seconds.blindness", 600, "effects.durations-seconds.glowing", 121));
        assertEquals(300, ((Effect.Potion) EffectCatalog.resolve(Action.BLESS, "speed", huge)).seconds());
        assertEquals(120, ((Effect.Potion) EffectCatalog.resolve(Action.CURSE, "slowness", huge)).seconds());
        assertEquals(5, ((Effect.Potion) EffectCatalog.resolve(Action.CURSE, "blindness", huge)).seconds());
        assertEquals(120, ((Effect.Potion) EffectCatalog.resolve(Action.CURSE, "glowing", huge)).seconds());
        var low = Settings.with(Map.of("effects.durations-seconds.speed", -5, "effects.durations-seconds.haste", 0));
        assertEquals(1, ((Effect.Potion) EffectCatalog.resolve(Action.BLESS, "speed", low)).seconds());
        assertEquals(1, ((Effect.Potion) EffectCatalog.resolve(Action.BLESS, "haste", low)).seconds());
    }

    @Test void defaultsRespectSpecMaximums() {
        for (String id : EffectCatalog.ids(Action.CURSE)) {
            if (EffectCatalog.resolve(Action.CURSE, id, defaults) instanceof Effect.Potion p) assertTrue(p.seconds() <= 120, id);
        }
        for (String id : EffectCatalog.ids(Action.BLESS)) {
            if (EffectCatalog.resolve(Action.BLESS, id, defaults) instanceof Effect.Potion p) assertTrue(p.seconds() <= 300, id);
        }
        assertEquals(5, ((Effect.Potion) EffectCatalog.resolve(Action.CURSE, "blindness", defaults)).seconds());
    }

    @Test void allPotionsAreLevelOne() {
        for (Action a : new Action[] {Action.BLESS, Action.CURSE})
            for (String id : EffectCatalog.ids(a))
                if (EffectCatalog.resolve(a, id, defaults) instanceof Effect.Potion p) assertEquals(0, p.amplifier(), id);
    }

    @Test void nullsAndJunkResolveToNothing() {
        assertInstanceOf(Effect.None.class, EffectCatalog.resolve(null, "speed", defaults));
        assertInstanceOf(Effect.None.class, EffectCatalog.resolve(Action.BLESS, null, defaults));
        assertInstanceOf(Effect.None.class, EffectCatalog.resolve(Action.BLESS, "  ", defaults));
    }

    @Test void promptDescriptionListsEveryId() {
        String d = EffectCatalog.describe();
        for (String id : EffectCatalog.allIds()) assertTrue(d.contains(id), id);
    }

    @Test void actionParsingDefaultsToNone() {
        assertEquals(Action.NONE, Action.parse(null));
        assertEquals(Action.NONE, Action.parse("op"));
        assertEquals(Action.SMITE, Action.parse(" Smite "));
    }
}
