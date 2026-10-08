package dev.overseersmp.overseer;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Daily per-player (+ vote bonus), cooldown and server-wide limits. "Day" rolls over at midnight Europe/Berlin. */
public final class PrayerLimits {
    public enum Result { OK, COOLDOWN, DAILY_PLAYER, DAILY_GLOBAL }

    public record Check(Result result, long retrySeconds, int remainingToday) {}

    /** Proof of a consumed prayer; hand it back to {@link #refund} if the prayer produced no answer. */
    public record Ticket(long id, UUID player, LocalDate day, Instant previousLast) {}

    public static final ZoneId ZONE = ZoneId.of("Europe/Berlin");

    private final Clock clock;
    private volatile Settings settings;
    private LocalDate day;
    private int global;
    private final Map<UUID, Integer> perPlayer = new HashMap<>();
    private final Map<UUID, Instant> last = new HashMap<>();
    private long nextTicket;
    private final java.util.Set<Long> refunded = new java.util.HashSet<>();

    public PrayerLimits(Settings settings, Clock clock) {
        this.settings = settings;
        this.clock = clock;
        this.day = today();
    }

    public void updateSettings(Settings s) { this.settings = s; }

    private LocalDate today() { return clock.instant().atZone(ZONE).toLocalDate(); }

    private void rollover() {
        LocalDate t = today();
        if (!t.equals(day)) {
            day = t;
            global = 0;
            perPlayer.clear();
            refunded.clear();
        }
    }

    /** Does not consume anything. Order: server cap, player daily cap, cooldown. */
    public synchronized Check check(UUID player, int voteBonus) {
        rollover();
        Settings s = settings;
        int used = perPlayer.getOrDefault(player, 0);
        int allowed = s.perPlayerPerDay + Math.max(0, voteBonus);
        int remaining = Math.max(0, allowed - used);
        if (global >= s.serverPerDay) return new Check(Result.DAILY_GLOBAL, secondsUntilMidnight(), remaining);
        if (used >= allowed) return new Check(Result.DAILY_PLAYER, secondsUntilMidnight(), 0);
        Instant l = last.get(player);
        if (l != null) {
            long wait = s.cooldownSeconds - Duration.between(l, clock.instant()).getSeconds();
            if (wait > 0) return new Check(Result.COOLDOWN, wait, remaining);
        }
        return new Check(Result.OK, 0, remaining);
    }

    /** Consume one prayer. Call right before the API request, after a successful {@link #check}. */
    public synchronized Ticket record(UUID player) {
        rollover();
        Ticket t = new Ticket(++nextTicket, player, day, last.get(player));
        perPlayer.merge(player, 1, Integer::sum);
        global++;
        last.put(player, clock.instant());
        return t;
    }

    /** Give the prayer back: the daily counts and the cooldown return to what they were. No-op if the day has rolled over. */
    public synchronized void refund(Ticket t) {
        rollover();
        if (t == null || !t.day().equals(day) || !refunded.add(t.id())) return;   // wrong day, or already refunded
        perPlayer.computeIfPresent(t.player(), (k, v) -> v <= 1 ? null : v - 1);
        global = Math.max(0, global - 1);
        if (t.previousLast() == null) last.remove(t.player());
        else last.put(t.player(), t.previousLast());
    }

    /** Restore today's counts after a restart. */
    public synchronized void seed(Map<UUID, Integer> counts, Map<UUID, Instant> lastTimes) {
        rollover();
        perPlayer.clear();
        perPlayer.putAll(counts);
        last.clear();
        last.putAll(lastTimes);
        global = counts.values().stream().mapToInt(Integer::intValue).sum();
    }

    public synchronized int globalToday() { rollover(); return global; }

    private long secondsUntilMidnight() {
        var now = clock.instant().atZone(ZONE);
        return Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay(ZONE)).getSeconds();
    }
}
