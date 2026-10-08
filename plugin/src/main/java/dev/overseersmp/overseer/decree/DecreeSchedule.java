package dev.overseersmp.overseer.decree;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** When the daily decree is due: from {@code hour}:00 Europe/Berlin, once per Berlin calendar day (catches up after downtime). */
public final class DecreeSchedule {
    public static final ZoneId ZONE = ZoneId.of("Europe/Berlin");

    private DecreeSchedule() {}

    public static LocalDate today(Instant now) { return now.atZone(ZONE).toLocalDate(); }

    public static boolean isDue(Instant now, LocalDate lastFired, int hour) {
        var z = now.atZone(ZONE);
        if (z.getHour() < hour) return false;
        return lastFired == null || z.toLocalDate().isAfter(lastFired);
    }

    /**
     * Value for "lastFired" on first run, so installing the plugin after 20:00 does not fire a surprise decree:
     * tonight is treated as already done; before 20:00 yesterday is, so tonight's decree still happens.
     */
    public static LocalDate initialLastFired(Instant now, int hour) {
        var z = now.atZone(ZONE);
        return z.getHour() >= hour ? z.toLocalDate() : z.toLocalDate().minusDays(1);
    }
}
