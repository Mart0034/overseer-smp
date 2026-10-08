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

    /** Tests that are about the cap use 3/day unless they say otherwise; the shipped default (5) has its own test. */
    private PrayerLimits limits(TestClock c, Map<String, Object> o) {
        var m = new java.util.HashMap<String, Object>(Map.of("prayers.per-player-per-day", 3));
        m.putAll(o);
        return new PrayerLimits(Settings.with(m), c);
    }

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

    // ================= refunds (a failed or rejected prayer must not cost the player a slot) =================

    @Test void shippedDefaultIsFivePrayersPerDay() {
        var c = new TestClock(berlin(2026, 10, 8, 12, 0));
        var l = new PrayerLimits(Settings.defaults(), c);
        for (int i = 0; i < 5; i++) {
            c.advance(Duration.ofSeconds(61));
            assertEquals(PrayerLimits.Result.OK, l.check(alice, 0).result(), "prayer " + (i + 1));
            l.record(alice);
        }
        c.advance(Duration.ofSeconds(61));
        assertEquals(PrayerLimits.Result.DAILY_PLAYER, l.check(alice, 0).result());
    }

    @Test void refundRestoresTheDailySlotTheGlobalCountAndTheCooldown() {
        var c = new TestClock(berlin(2026, 10, 8, 12, 0));
        var l = limits(c, Map.of());
        var t = l.record(alice);
        assertEquals(PrayerLimits.Result.COOLDOWN, l.check(alice, 0).result());
        assertEquals(1, l.globalToday());
        l.refund(t);
        assertEquals(0, l.globalToday());
        var r = l.check(alice, 0);
        assertEquals(PrayerLimits.Result.OK, r.result(), "the cooldown from the failed prayer is gone");
        assertEquals(3, r.remainingToday());
    }

    @Test void aPlayerWhoseEveryPrayerFailsNeverRunsOut() {
        var c = new TestClock(berlin(2026, 10, 8, 12, 0));
        var l = limits(c, Map.of());
        for (int i = 0; i < 20; i++) {
            assertEquals(PrayerLimits.Result.OK, l.check(alice, 0).result(), "attempt " + i);
            l.refund(l.record(alice));
        }
        assertEquals(0, l.globalToday());
    }

    @Test void refundKeepsEarlierSuccessfulPrayersCounted() {
        var c = new TestClock(berlin(2026, 10, 8, 12, 0));
        var l = limits(c, Map.of());
        l.record(alice);                                   // succeeded
        c.advance(Duration.ofSeconds(10));
        var failed = l.record(alice);                      // failed
        l.refund(failed);
        assertEquals(1, l.globalToday());
        var r = l.check(alice, 0);
        assertEquals(PrayerLimits.Result.COOLDOWN, r.result(), "back to the earlier prayer's cooldown");
        assertEquals(50, r.retrySeconds());
        assertEquals(2, r.remainingToday());
    }

    @Test void refundingTwiceOnlyRefundsOnce() {
        var c = new TestClock(berlin(2026, 10, 8, 12, 0));
        var l = limits(c, Map.of("prayers.cooldown-seconds", 0));
        l.record(alice);
        var t = l.record(alice);
        l.refund(t);
        l.refund(t);
        assertEquals(1, l.globalToday());
        assertEquals(2, l.check(alice, 0).remainingToday());
    }

    @Test void refundNeverGoesNegativeAndIgnoresNull() {
        var l = limits(new TestClock(berlin(2026, 10, 8, 12, 0)), Map.of());
        assertDoesNotThrow(() -> l.refund(null));
        assertEquals(0, l.globalToday());
    }

    @Test void refundAfterMidnightDoesNotTouchTheNewDay() {
        var c = new TestClock(berlin(2026, 10, 8, 23, 59));
        var l = limits(c, Map.of("prayers.cooldown-seconds", 0));
        var t = l.record(alice);                           // counted on 8 Oct
        c.advance(Duration.ofMinutes(2));                  // now 9 Oct
        l.record(bob);
        l.refund(t);
        assertEquals(1, l.globalToday(), "bob's prayer today is untouched");
    }

    @Test void refundOnlyAffectsThatPlayer() {
        var c = new TestClock(berlin(2026, 10, 8, 12, 0));
        var l = limits(c, Map.of("prayers.cooldown-seconds", 0));
        l.record(alice);
        var tb = l.record(bob);
        l.refund(tb);
        assertEquals(2, l.check(alice, 0).remainingToday());
        assertEquals(3, l.check(bob, 0).remainingToday());
    }
}
