package dev.overseersmp.overseer.decree;

import static org.junit.jupiter.api.Assertions.*;

import dev.overseersmp.overseer.ContentFilter;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DecreeTest {
    // ---- fakes
    static final class TestClock extends Clock {
        Instant now;
        TestClock(Instant start) { now = start; }
        void advance(Duration d) { now = now.plus(d); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId z) { return this; }
        @Override public Instant instant() { return now; }
    }

    /** Records every call; keeps its "world value" in the engine-persisted state like the real merciful_death does. */
    static class FakeMod implements Modifier {
        final String id;
        final Map<String, Bound> bounds;
        final List<String> calls;
        final Map<String, Boolean> world;      // the "world": survives a simulated restart, like real game rules
        final List<String> incompatible;

        FakeMod(String id, List<String> calls, Map<String, Boolean> world, List<String> incompatible, Map<String, Bound> bounds) {
            this.id = id; this.calls = calls; this.world = world; this.incompatible = incompatible; this.bounds = bounds;
        }

        @Override public String id() { return id; }
        @Override public String displayName() { return "Fake " + id; }
        @Override public String description() { return "does " + id; }
        @Override public Map<String, Bound> bounds() { return bounds; }
        @Override public boolean compatibleWith(String o) { return !o.equals(id) && !incompatible.contains(o); }

        @Override public void enable(Map<String, Double> params, Map<String, String> state) {
            calls.add("enable:" + id + ":" + params);
            if (!state.containsKey(id + ".prev")) state.put(id + ".prev", String.valueOf(world.getOrDefault(id, false)));
            world.put(id, true);
        }

        @Override public void disable(Map<String, Double> params, Map<String, String> state) {
            calls.add("disable:" + id);
            String prev = state.get(id + ".prev");
            if (prev != null) { world.put(id, Boolean.parseBoolean(prev)); state.remove(id + ".prev"); }
        }
    }

    @TempDir Path dir;
    List<String> calls;
    Map<String, Boolean> world;
    Map<String, Modifier> registry;
    TestClock clock;
    DecreeStore store;

    static Instant berlin(int y, int m, int d, int h, int min) { return ZonedDateTime.of(y, m, d, h, min, 0, 0, DecreeSchedule.ZONE).toInstant(); }

    Map<String, Modifier> newRegistry() {
        Map<String, Modifier> r = new LinkedHashMap<>();
        Map<String, Modifier.Bound> scale = Map.of("scale", new Modifier.Bound(0.4, 0.75, 0.5));
        Map<String, Modifier.Bound> interval = Map.of("interval_seconds", new Modifier.Bound(20, 120, 45));
        r.put("small", new FakeMod("small", calls, world, List.of("giants"), scale));
        r.put("giants", new FakeMod("giants", calls, world, List.of("small"), Map.of("scale", new Modifier.Bound(1.25, 2.0, 1.5))));
        r.put("rain", new FakeMod("rain", calls, world, List.of(), interval));
        r.put("merciful", new FakeMod("merciful", calls, world, List.of(), Map.of()));
        return r;
    }

    @BeforeEach void setUp() {
        calls = new ArrayList<>();
        world = new java.util.HashMap<>();
        registry = newRegistry();
        clock = new TestClock(berlin(2026, 10, 9, 20, 0).plusSeconds(5));
        store = new DecreeStore(dir.resolve("decree.json"));
    }

    DecreeEngine engine() { return new DecreeEngine(registry, store, clock, m -> {}); }

    DecreeParser parser() { return new DecreeParser(registry, new ContentFilter(List.of()), 300); }

    static String json(String text, String mod, String second, String nums) {
        return "{\"decree\": \"" + text + "\", \"modifier\": \"" + mod + "\", \"second_modifier\": \"" + second + "\"" + (nums.isEmpty() ? "" : ", " + nums) + "}";
    }

    // ================= parsing and clamping =================

    @Test void parsesAGoodDecreeAndClampsParameters() {
        var r = parser().parse(json("The small shall inherit.", "small", "none", "\"scale\": 0.05, \"interval_seconds\": 9999"));
        assertTrue(r.ok());
        assertEquals("The small shall inherit.", r.parsed().text());
        assertEquals(1, r.parsed().selected().size());
        assertEquals("small", r.parsed().selected().get(0).id());
        assertEquals(Map.of("scale", 0.4), r.parsed().selected().get(0).params(), "below min is clamped up; foreign params are dropped");
        assertEquals(0.75, parser().parse(json("x", "small", "none", "\"scale\": 99")).parsed().selected().get(0).params().get("scale"));
        assertEquals(0.5, parser().parse(json("x", "small", "none", "")).parsed().selected().get(0).params().get("scale"), "missing -> default");
        assertEquals(0.5, parser().parse(json("x", "small", "none", "\"scale\": \"huge\"")).parsed().selected().get(0).params().get("scale"), "non-number -> default");
        assertEquals(120.0, parser().parse(json("x", "rain", "none", "\"interval_seconds\": 1e9")).parsed().selected().get(0).params().get("interval_seconds"));
    }

    @Test void secondModifierMustBeKnownDifferentAndMutuallyCompatible() {
        assertEquals(2, parser().parse(json("x", "small", "rain", "")).parsed().selected().size());
        assertEquals(1, parser().parse(json("x", "small", "giants", "")).parsed().selected().size(), "incompatible pair: second dropped");
        assertEquals(1, parser().parse(json("x", "small", "small", "")).parsed().selected().size());
        assertEquals(1, parser().parse(json("x", "small", "op", "")).parsed().selected().size());
        assertEquals(1, parser().parse(json("x", "small", "none", "")).parsed().selected().size());
    }

    @Test void secondModifierGetsItsOwnClampedParams() {
        var sel = parser().parse(json("x", "small", "rain", "\"scale\": 0.6, \"interval_seconds\": 1")).parsed().selected();
        assertEquals(0.6, sel.get(0).params().get("scale"));
        assertEquals(20.0, sel.get(1).params().get("interval_seconds"));
    }

    @Test void rejectsUnknownModifierMalformedAndUnsafeAnswers() {
        for (String bad : new String[] {null, "", "no json", "{decree: 'x'}", json("x", "op", "none", ""), json("x", "", "none", ""), "[]",
                "{\"decree\":\"x\"}", "{\"modifier\":\"small\"}", json("", "small", "none", ""), json("   ", "small", "none", ""),
                json("Buy our rank in the store", "small", "none", ""), json("fuck this", "small", "none", ""), json("see https://x.example", "small", "none", "")}) {
            assertFalse(parser().parse(bad).ok(), String.valueOf(bad));
        }
    }

    @Test void textIsSanitisedAndTruncated() {
        var r = parser().parse("{\"decree\": \"§cRed\\nwords " + "word ".repeat(200) + "\", \"modifier\": \"small\", \"second_modifier\": \"none\"}");
        assertTrue(r.ok());
        assertFalse(r.parsed().text().contains("§"));
        assertFalse(r.parsed().text().contains("\n"));
        assertTrue(r.parsed().text().codePointCount(0, r.parsed().text().length()) <= 300);
        assertTrue(r.parsed().text().endsWith("…"));
    }

    @Test void hostileExtraFieldsAreIgnored() {
        var r = parser().parse("{\"decree\":\"ok\",\"modifier\":\"small\",\"second_modifier\":\"none\",\"command\":\"op Steve\",\"gamerule\":\"keepInventory\",\"scale\":0.5}");
        assertTrue(r.ok());
        assertEquals(1, r.parsed().selected().size());
    }

    // ================= fallback =================

    @Test void fallbackIsUsedWhenThereIsNoAnswerOrItIsInvalid() {
        var chooser = new DecreeChooser(registry, parser());
        for (String raw : new String[] {null, "", "garbage", json("x", "nonexistent", "none", "")}) {
            var c = chooser.choose(raw, null, new Random(1));
            assertEquals("fallback", c.source(), String.valueOf(raw));
            assertEquals(1, c.selected().size());
            assertTrue(registry.containsKey(c.selected().get(0).id()));
            assertFalse(c.text().isBlank());
            assertTrue(c.text().length() <= 300);
            assertEquals(registry.get(c.selected().get(0).id()).defaults(), c.selected().get(0).params(), "defaults, inside bounds");
        }
        assertEquals("model", chooser.choose(json("Be small.", "small", "none", ""), null, new Random(1)).source());
    }

    @Test void fallbackAvoidsYesterdaysModifierAlmostAlways() {
        var chooser = new DecreeChooser(registry, parser());
        var rnd = new Random(42);
        int repeats = 0;
        for (int i = 0; i < 2000; i++) if (chooser.fallback("rain", rnd).selected().get(0).id().equals("rain")) repeats++;
        assertTrue(repeats < 2000 * 0.08, "weight x0.1 -> about 3 % repeats, got " + repeats);
        assertTrue(repeats > 0, "but not impossible");
    }

    @Test void fallbackIsWeighted() {
        var heavy = new FakeMod("heavy", calls, world, List.of(), Map.of()) { @Override public int weight() { return 1000; } };
        registry.put("heavy", heavy);
        var chooser = new DecreeChooser(registry, parser());
        var rnd = new Random(7);
        int h = 0;
        for (int i = 0; i < 1000; i++) if (chooser.fallback(null, rnd).selected().get(0).id().equals("heavy")) h++;
        assertTrue(h > 900);
    }

    @Test void forcedDecreeUsesDefaultsAndHonoursCompatibility() {
        var chooser = new DecreeChooser(registry, parser());
        assertEquals(2, chooser.forced(List.of("small", "rain")).selected().size());
        assertEquals(1, chooser.forced(List.of("small", "giants")).selected().size());
        assertNull(chooser.forced(List.of("op")));
        assertNull(chooser.forced(List.of()));
        assertEquals("forced", chooser.forced(List.of("SMALL")).source());
    }

    // ================= schedule =================

    @Test void scheduleFiresOncePerBerlinDayFromTheConfiguredHour() {
        LocalDate d = LocalDate.of(2026, 10, 9);
        assertFalse(DecreeSchedule.isDue(berlin(2026, 10, 9, 19, 59), d.minusDays(1), 20));
        assertTrue(DecreeSchedule.isDue(berlin(2026, 10, 9, 20, 0), d.minusDays(1), 20));
        assertFalse(DecreeSchedule.isDue(berlin(2026, 10, 9, 20, 0), d, 20), "already fired today");
        assertFalse(DecreeSchedule.isDue(berlin(2026, 10, 10, 0, 30), d, 20), "after midnight but before 20:00");
        assertTrue(DecreeSchedule.isDue(berlin(2026, 10, 10, 20, 0), d, 20));
        assertTrue(DecreeSchedule.isDue(berlin(2026, 10, 9, 21, 45), d.minusDays(1), 20), "catches up after downtime");
        assertTrue(DecreeSchedule.isDue(berlin(2026, 10, 9, 20, 0), null, 20));
    }

    @Test void scheduleUsesBerlinTimeAcrossTheDstChange() {
        // Berlin leaves DST on 2026-10-25 at 03:00: 20:00 Berlin is 18:00 UTC on the 24th (CEST) and 19:00 UTC on the 25th (CET).
        assertFalse(DecreeSchedule.isDue(Instant.parse("2026-10-24T17:59:00Z"), LocalDate.of(2026, 10, 23), 20));
        assertTrue(DecreeSchedule.isDue(Instant.parse("2026-10-24T18:00:00Z"), LocalDate.of(2026, 10, 23), 20));
        assertFalse(DecreeSchedule.isDue(Instant.parse("2026-10-25T18:59:00Z"), LocalDate.of(2026, 10, 24), 20), "19:59 CET");
        assertTrue(DecreeSchedule.isDue(Instant.parse("2026-10-25T19:00:00Z"), LocalDate.of(2026, 10, 24), 20), "20:00 CET");
    }

    @Test void firstRunDoesNotFireASurpriseDecree() {
        assertEquals(LocalDate.of(2026, 10, 9), DecreeSchedule.initialLastFired(berlin(2026, 10, 9, 22, 10), 20), "after 20:00: tonight counts as done");
        assertEquals(LocalDate.of(2026, 10, 8), DecreeSchedule.initialLastFired(berlin(2026, 10, 9, 12, 0), 20), "before 20:00: tonight still happens");
    }

    // ================= engine lifecycle and restart =================

    @Test void activateEnablesPersistsAndMarksTodayUsed() {
        var e = engine();
        e.restore();
        e.ensureInitialised(20);
        var d = e.activate(List.of(new ActiveDecree.Selected("small", Map.of("scale", 0.5))), "Be small.", "model", true);
        assertTrue(world.get("small"));
        assertEquals(LocalDate.of(2026, 10, 9), e.lastFired());
        assertEquals(d.startedAt() + Duration.ofHours(24).toMillis(), d.expiresAt());
        var saved = store.load();
        assertEquals("2026-10-09", saved.lastFired());
        assertEquals("Be small.", saved.active().text());
    }

    @Test void forcedDecreeDoesNotUseUpTodaysScheduledSlot() {
        var e = engine();
        e.restore();
        e.ensureInitialised(20);              // 20:00:05 -> tonight counted as done
        LocalDate before = e.lastFired();
        e.activate(List.of(new ActiveDecree.Selected("rain", Map.of())), "t", "forced", false);
        assertEquals(before, e.lastFired());
    }

    @Test void newDecreeFullyReplacesTheOldOne() {
        var e = engine();
        e.restore();
        e.activate(List.of(new ActiveDecree.Selected("small", Map.of("scale", 0.5))), "a", "model", true);
        e.activate(List.of(new ActiveDecree.Selected("rain", Map.of("interval_seconds", 30.0))), "b", "model", true);
        assertEquals(List.of("enable:small:{scale=0.5}", "disable:small", "enable:rain:{interval_seconds=30.0}"), calls);
        assertFalse(world.get("small"), "old modifier restored");
        assertTrue(world.get("rain"));
    }

    @Test void restartMidDecreeReEnablesWithTheSameParamsAndOriginalRestoreData() {
        world.put("merciful", false);                     // the world's original value
        var first = engine();
        first.restore();
        first.activate(List.of(new ActiveDecree.Selected("merciful", Map.of()), new ActiveDecree.Selected("small", Map.of("scale", 0.6))), "txt", "model", true);
        assertTrue(world.get("merciful"));
        calls.clear();

        clock.advance(Duration.ofHours(3));               // server restarts 3 h later: a brand-new engine, same files
        var second = engine();
        second.restore();
        assertEquals(List.of("enable:merciful:{}", "enable:small:{scale=0.6}"), calls, "same params re-applied, not re-rolled");
        assertEquals("txt", second.active().orElseThrow().text());
        assertEquals(LocalDate.of(2026, 10, 9), second.lastFired(), "does not fire again today");

        second.clear();                                   // end of decree after the restart
        assertFalse(world.get("merciful"), "restored to the ORIGINAL value remembered across the restart");
        assertFalse(world.get("small"));
        assertNull(store.load().active());
    }

    @Test void restartDoesNotOverwriteTheRememberedOriginalValue() {
        world.put("merciful", false);
        var a = engine();
        a.restore();
        a.activate(List.of(new ActiveDecree.Selected("merciful", Map.of())), "t", "model", true);
        for (int i = 0; i < 3; i++) { var b = engine(); b.restore(); }   // several crashes/restarts: world value is already 'true'
        var c = engine();
        c.restore();
        c.clear();
        assertFalse(world.get("merciful"), "prev stays 'false' no matter how often enable ran");
    }

    @Test void decreeThatExpiredWhileTheServerWasDownIsRestoredOnStartup() {
        world.put("small", false);
        var a = engine();
        a.restore();
        a.activate(List.of(new ActiveDecree.Selected("small", Map.of("scale", 0.5))), "t", "model", true);
        clock.advance(Duration.ofHours(30));              // down for 30 h
        calls.clear();
        var b = engine();
        b.restore();
        assertEquals(List.of("disable:small"), calls, "disabled, never re-enabled");
        assertFalse(world.get("small"));
        assertTrue(b.active().isEmpty());
        assertNull(store.load().active());
        assertEquals("2026-10-09", store.load().lastFired(), "lastFired survives so the next scheduled decree still fires on its day");
    }

    @Test void crashBetweenPersistAndEnableStillRestores() {
        // simulate: decree saved, but enable never ran (crash). The next start enables it and clear() works.
        store.save(new DecreeStore.Data("2026-10-09", new ActiveDecree("t", "model", clock.instant().toEpochMilli(),
                clock.instant().plus(Duration.ofHours(24)).toEpochMilli(), List.of(new ActiveDecree.Selected("small", Map.of("scale", 0.5))), Map.of())));
        var e = engine();
        e.restore();
        assertTrue(world.get("small"));
        e.clear();
        assertFalse(world.get("small"));
    }

    @Test void tickExpiresTheDecreeAfter24Hours() {
        var e = engine();
        e.restore();
        e.activate(List.of(new ActiveDecree.Selected("small", Map.of())), "t", "model", true);
        clock.advance(Duration.ofHours(23));
        e.tick();
        assertTrue(e.active().isPresent());
        clock.advance(Duration.ofHours(2));
        e.tick();
        assertTrue(e.active().isEmpty());
        assertFalse(world.get("small"));
    }

    @Test void aFailingModifierDoesNotBreakTheOthersOrTheRestore() {
        registry.put("boom", new FakeMod("boom", calls, world, List.of(), Map.of()) {
            @Override public void enable(Map<String, Double> p, Map<String, String> s) { throw new IllegalStateException("kaput"); }
            @Override public void disable(Map<String, Double> p, Map<String, String> s) { throw new IllegalStateException("kaput"); }
        });
        var e = engine();
        e.restore();
        e.activate(List.of(new ActiveDecree.Selected("boom", Map.of()), new ActiveDecree.Selected("small", Map.of())), "t", "model", true);
        assertTrue(world.get("small"), "the other modifier still enabled");
        assertDoesNotThrow(e::clear);
        assertFalse(world.get("small"));
    }

    @Test void unknownModifierInASavedDecreeIsSkippedNotFatal() {
        store.save(new DecreeStore.Data("2026-10-09", new ActiveDecree("t", "model", clock.instant().toEpochMilli(),
                clock.instant().plus(Duration.ofHours(24)).toEpochMilli(),
                List.of(new ActiveDecree.Selected("removed_in_a_later_version", Map.of()), new ActiveDecree.Selected("small", Map.of())), Map.of())));
        var e = engine();
        assertDoesNotThrow(e::restore);
        assertTrue(world.get("small"));
    }

    @Test void corruptStoreFileStartsCleanInsteadOfCrashing() throws Exception {
        java.nio.file.Files.writeString(dir.resolve("decree.json"), "{ not json at all");
        var e = engine();
        assertDoesNotThrow(e::restore);
        assertTrue(e.active().isEmpty());
    }

    @Test void paramsAreAlwaysInsideBoundsForEveryRegisteredModifier() {
        for (Modifier m : registry.values()) {
            var d = m.clamp(Map.of("scale", Double.NaN, "interval_seconds", Double.POSITIVE_INFINITY));
            for (var b : m.bounds().entrySet()) {
                double v = d.get(b.getKey());
                assertTrue(v >= b.getValue().min() && v <= b.getValue().max(), m.id() + "." + b.getKey());
            }
        }
    }
}
