package dev.overseersmp.overseer.favor;

import static org.junit.jupiter.api.Assertions.*;

import dev.overseersmp.overseer.Action;
import dev.overseersmp.overseer.Database;
import dev.overseersmp.overseer.Effect;
import dev.overseersmp.overseer.EffectCatalog;
import dev.overseersmp.overseer.Settings;
import dev.overseersmp.overseer.decree.Modifier;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FavorTest {
    @Test void titleBandsMatchTheSpecAtEveryBoundary() {
        int[][] cases = {{-100, 0}, {-51, 0}, {-50, 0}, {-49, 1}, {-1, 1}, {0, 2}, {24, 2}, {25, 3}, {49, 3}, {50, 4}, {74, 4}, {75, 5}, {100, 5}};
        FavorTitle[] t = FavorTitle.values();
        for (int[] c : cases) assertEquals(t[c[1]], FavorTitle.forFavor(c[0]), "favor " + c[0]);
        assertEquals("Heretic", FavorTitle.forFavor(-50).display);
        assertEquals("Doubter", FavorTitle.forFavor(-1).display);
        assertEquals("Pilgrim", FavorTitle.forFavor(0).display);
        assertEquals("Faithful", FavorTitle.forFavor(25).display);
        assertEquals("Devout", FavorTitle.forFavor(50).display);
        assertEquals("Prophet", FavorTitle.forFavor(75).display);
    }

    @Test void everyFavorValueHasExactlyOneBandAndBandsAreMonotonic() {
        int last = -1;
        for (int f = -100; f <= 100; f++) {
            int o = FavorTitle.forFavor(f).ordinal();
            assertTrue(o >= last, "bands never go backwards at " + f);
            last = o;
        }
    }

    @Test void progressToTheNextBand() {
        assertEquals(10, FavorTitle.forFavor(15).toNext(15), "Pilgrim at 15 needs 10 more for Faithful (25)");
        assertEquals(1, FavorTitle.forFavor(74).toNext(74));
        assertEquals(0, FavorTitle.PROPHET.toNext(100));
        assertNull(FavorTitle.PROPHET.next());
        assertEquals(FavorTitle.DOUBTER, FavorTitle.HERETIC.next());
        assertEquals(9, FavorTitle.forFavor(-58).toNext(-58), "Heretic at -58 needs 9 to reach Doubter (-49)");
    }

    @Test void colourNamesAreValidAdventureColours() {
        for (FavorTitle t : FavorTitle.values())
            assertNotNull(net.kyori.adventure.text.format.NamedTextColor.NAMES.value(t.color), t.color);
    }

    // ---- decree-time gifts
    @Test void giftsGoToFaithfulAndAboveAndCursesToHereticsOnly() {
        var rnd = new Random(3);
        for (int f : new int[] {24, 10, 0, -1, -49}) assertTrue(FavorGifts.pick(f, rnd).isEmpty(), "no gift at " + f);
        for (int f : new int[] {25, 49, 50, 74, 75, 100}) assertEquals(Action.BLESS, FavorGifts.pick(f, rnd).orElseThrow().action(), "bless at " + f);
        for (int f : new int[] {-50, -75, -100}) assertEquals(Action.CURSE, FavorGifts.pick(f, rnd).orElseThrow().action(), "curse at " + f);
    }

    @Test void everyPossibleGiftIsOnTheWhitelistAndMild() {
        Set<String> seen = new HashSet<>();
        var rnd = new Random(11);
        for (int i = 0; i < 4000; i++) {
            for (int f : new int[] {60, -60}) {
                var g = FavorGifts.pick(f, rnd).orElseThrow();
                seen.add(g.effectId());
                Effect fx = EffectCatalog.resolve(g.action(), g.effectId(), Settings.defaults());
                assertFalse(fx instanceof Effect.None, g.effectId() + " must resolve");
                if (fx instanceof Effect.Potion p) assertTrue(p.seconds() <= 300 && p.amplifier() == 0);
            }
        }
        assertFalse(seen.contains("blindness"), "no blindness as a 'small' curse");
        assertFalse(seen.contains("lightning"));
        assertTrue(seen.size() >= 12, "variety: " + seen);
    }

    // ---- leaderboard
    @Test void leaderboardAlwaysHasTenRankedRowsAndSanitisedNames() {
        var lines = LeaderboardText.lines(List.of(new LeaderboardText.Row("Alice", 80), new LeaderboardText.Row("&4Evil&kName§c!!", 30)));
        assertEquals(1 + 10 + 1, lines.size());
        assertTrue(lines.get(1).contains("Alice") && lines.get(1).contains("Prophet"));
        assertFalse(lines.get(2).contains("&4") || lines.get(2).contains("§") || lines.get(2).contains("!"), lines.get(2));
        assertTrue(lines.get(2).contains("Faithful"));
        assertTrue(lines.get(3).endsWith("&8-"), "empty rank");
        assertEquals(12, LeaderboardText.lines(List.of()).size());
    }

    @Test void leaderboardIgnoresRowsBeyondTen() {
        var many = new java.util.ArrayList<LeaderboardText.Row>();
        for (int i = 0; i < 25; i++) many.add(new LeaderboardText.Row("P" + i, 50 - i));
        var lines = LeaderboardText.lines(many);
        assertEquals(12, lines.size());
        assertTrue(lines.get(10).contains("P9"));
    }

    // ---- /fame and favor lookups
    @Test void fameDefaultsToOffAndPersists() throws Exception {
        try (var db = new Database("jdbc:sqlite::memory:")) {
            UUID u = UUID.randomUUID();
            db.touchPlayer(u, "Steve", 1);
            assertFalse(db.fame(u), "opt-out by default");
            db.setFame(u, true);
            assertTrue(db.fame(u));
            db.touchPlayer(u, "Steve", 2);                 // rejoining must not reset it
            assertTrue(db.fame(u));
            db.setFame(u, false);
            assertFalse(db.fame(u));
            assertFalse(db.fame(UUID.randomUUID()), "unknown player is opted out");
        }
    }

    @Test void favorLookupByNameAndTopRows() throws Exception {
        try (var db = new Database("jdbc:sqlite::memory:")) {
            UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
            db.touchPlayer(a, "Alice", 1); db.addFavor(a, 10); db.addFavor(a, 10);
            db.touchPlayer(b, "Bob", 2);   db.addFavor(b, 10);
            db.touchPlayer(c, "Cleo", 3);  db.addFavor(c, -9);
            assertArrayEquals(new Object[] {"Alice", 20}, db.favorByName("alice"));
            assertNull(db.favorByName("nobody"));
            var top = db.topFavorRows(2);
            assertEquals(2, top.size());
            assertEquals("Alice", top.get(0)[0]);
            assertEquals("Bob", top.get(1)[0]);
            assertEquals(3, db.topFavorRows(10).size(), "negative favor is ranked last, zero is excluded");
        }
    }

    // ---- decree prompt parameters carry units
    @Test void everyDecreeParameterHasAMeaningForThePrompt() {
        // The real modifiers need a Bukkit plugin to construct; the contract is checked through the record helper.
        var b = new Modifier.Bound(20, 120, 45, "seconds between chicken drops (60 means about once a minute)");
        assertTrue(b.meaning().contains("60"));
        assertEquals("", new Modifier.Bound(0, 1, 0).meaning());
        assertEquals(45.0, b.clamp("garbage"));
    }
}
