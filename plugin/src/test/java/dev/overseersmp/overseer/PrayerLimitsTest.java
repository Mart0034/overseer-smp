package dev.overseersmp.overseer;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PrayerLimitsTest {
    /** Settable clock. */
    static final class TestClock extends Clock {
        Instant now;
        TestClock(Instant start) { now = start; }
        void advance(Duration d) { now = now.plus(d); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId z) { return this; }
        @Override public Instant instant() { return now; }
    }

    private static Instant berlin(int y, int m, int d, int h, int min) {
        return ZonedDateTime.of(y, m, d, h, min, 0, 0, PrayerLimits.ZONE).toInstant();
    }

    private final UUID alice = UUID.randomUUID(), bob = UUID.randomUUID();

    private PrayerLimits limits(TestClock c, Map<String, Object> o) { return new PrayerLimits(Settings.with(o), c); }

    @Test void threePrayersPerDayThenBlocked() {
        var c = new TestClock(berlin(2026, 10, 8, 12, 0));
        var l = limits(c, Map.of("prayers.cooldown-seconds", 0));
        for (int i = 0; i < 3; i++) {
            assertEquals(PrayerLimits.Result.OK, l.check(alice, 0).result());
            l.record(alice);
        }
        var blocked = l.check(alice, 0);
        assertEquals(PrayerLimits.Result.DAILY_PLAYER, blocked.result());
        assertEquals(0, blocked.remainingToday());
        assertEquals(PrayerLimits.Result.OK, l.check(bob, 0).result(), "other players are unaffected");
    }

    @Test void votesAddExtraPrayers() {
        var c = new TestClock(berlin(2026, 10, 8, 12, 0));
        var l = limits(c, Map.of("prayers.cooldown-seconds", 0));
        for (int i = 0; i < 3; i++) l.record(alice);
        assertEquals(PrayerLimits.Result.DAILY_PLAYER, l.check(alice, 0).result());
        assertEquals(PrayerLimits.Result.OK, l.check(alice, 1).result());
        l.record(alice);
        assertEquals(PrayerLimits.Result.DAILY_PLAYER, l.check(alice, 1).result());
        assertEquals(PrayerLimits.Result.OK, l.check(alice, 2).result());
    }

    @Test void cooldownBlocksThenReleases() {
        var c = new TestClock(berlin(2026, 10, 8, 12, 0));
        var l = limits(c, Map.of());
        l.record(alice);
        var r = l.check(alice, 0);
        assertEquals(PrayerLimits.Result.COOLDOWN, r.result());
        assertEquals(60, r.retrySeconds());
        c.advance(Duration.ofSeconds(59));
        assertEquals(PrayerLimits.Result.COOLDOWN, l.check(alice, 0).result());
        assertEquals(1, l.check(alice, 0).retrySeconds());
        c.advance(Duration.ofSeconds(1));
        assertEquals(PrayerLimits.Result.OK, l.check(alice, 0).result());
    }

    @Test void checkDoesNotConsume() {
        var l = limits(new TestClock(berlin(2026, 10, 8, 12, 0)), Map.of("prayers.cooldown-seconds", 0));
        for (int i = 0; i < 20; i++) assertEquals(PrayerLimits.Result.OK, l.check(alice, 0).result());
        assertEquals(0, l.globalToday());
    }

    @Test void serverWideCap() {
        var c = new TestClock(berlin(2026, 10, 8, 12, 0));
        var l = limits(c, Map.of("prayers.server-per-day", 2, "prayers.cooldown-seconds", 0));
        l.record(alice);
        l.record(bob);
        assertEquals(PrayerLimits.Result.DAILY_GLOBAL, l.check(UUID.randomUUID(), 0).result());
    }

    @Test void dayRollsOverAtMidnightInBerlin() {
        var c = new TestClock(berlin(2026, 10, 8, 23, 59));
        var l = limits(c, Map.of("prayers.cooldown-seconds", 0, "prayers.server-per-day", 3));
        for (int i = 0; i < 3; i++) l.record(alice);
        var blocked = l.check(alice, 0);
        assertEquals(PrayerLimits.Result.DAILY_GLOBAL, blocked.result());
        assertEquals(60, blocked.retrySeconds());
        c.advance(Duration.ofMinutes(2));
        assertEquals(PrayerLimits.Result.OK, l.check(alice, 0).result());
        assertEquals(0, l.globalToday());
    }

    @Test void seedRestoresTodaysCountsAfterARestart() {
        var c = new TestClock(berlin(2026, 10, 8, 12, 0));
        var l = limits(c, Map.of());
        l.seed(Map.of(alice, 3), Map.of(alice, c.now.minusSeconds(10)));
        assertEquals(PrayerLimits.Result.DAILY_PLAYER, l.check(alice, 0).result());
        assertEquals(PrayerLimits.Result.OK, l.check(bob, 0).result());
        assertEquals(3, l.globalToday());
    }

    @Test void fivePrayersInARowTriggerCooldownThenDailyLimit() {
        var c = new TestClock(berlin(2026, 10, 8, 12, 0));
        var l = limits(c, Map.of());   // defaults: 3/day, 60 s cooldown
        int ok = 0;
        PrayerLimits.Result last = null;
        for (int i = 0; i < 5; i++) {
            var r = l.check(alice, 0);
            last = r.result();
            if (r.result() == PrayerLimits.Result.OK) { l.record(alice); ok++; }
        }
        assertEquals(1, ok, "only the first immediate prayer passes; the rest hit the cooldown");
        assertEquals(PrayerLimits.Result.COOLDOWN, last);
        for (int i = 0; i < 4; i++) {
            c.advance(Duration.ofSeconds(61));
            var r = l.check(alice, 0);
            if (r.result() == PrayerLimits.Result.OK) { l.record(alice); ok++; }
        }
        assertEquals(3, ok);
        assertEquals(PrayerLimits.Result.DAILY_PLAYER, l.check(alice, 0).result());
    }

    @Test void settingsCanBeUpdatedLive() {
        var c = new TestClock(berlin(2026, 10, 8, 12, 0));
        var l = limits(c, Map.of("prayers.cooldown-seconds", 0));
        for (int i = 0; i < 3; i++) l.record(alice);
        l.updateSettings(Settings.with(Map.of("prayers.cooldown-seconds", 0, "prayers.per-player-per-day", 5)));
        assertEquals(PrayerLimits.Result.OK, l.check(alice, 0).result());
    }
}
