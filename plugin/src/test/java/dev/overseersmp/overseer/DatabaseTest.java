package dev.overseersmp.overseer;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DatabaseTest {
    private Database db;
    private final UUID u = UUID.randomUUID();

    @BeforeEach void open() throws Exception { db = new Database("jdbc:sqlite::memory:"); }

    @AfterEach void close() { db.close(); }

    private Database.PrayerRow row(long ts, String prayer, String status, boolean counted) {
        return new Database.PrayerRow(ts, u, "Steve", prayer, "reply", "bless", "speed", 2, 100, 20, 300, status, counted);
    }

    @Test void favorIsClamped() throws Exception {
        db.touchPlayer(u, "Steve", 1000);
        assertEquals(0, db.favor(u));
        assertEquals(10, db.addFavor(u, 10));
        assertEquals(100, db.addFavor(u, 500));
        assertEquals(-100, db.addFavor(u, -500));
    }

    @Test void touchPlayerUpdatesNameAndKeepsFavor() throws Exception {
        db.touchPlayer(u, "Steve", 1000);
        db.addFavor(u, 5);
        db.touchPlayer(u, "Steve2", 2000);
        assertEquals(5, db.favor(u));
    }

    @Test void recentPrayersAreOldestFirstAndOnlyAnswered() throws Exception {
        db.logPrayer(row(1, "one", "ok", true));
        db.logPrayer(row(2, "filtered one", "filtered:BLOCKED", false));
        db.logPrayer(row(3, "two", "ok", true));
        db.logPrayer(row(4, "three", "ok", true));
        db.logPrayer(row(5, "four", "error:HTTP 500", true));
        db.logPrayer(row(6, "five", "ok", true));
        assertEquals(java.util.List.of("two", "three", "five"), db.recentPrayers(u, 3));
        assertTrue(db.recentPrayers(u, 0).isEmpty());
    }

    @Test void countsOnlySlotConsumingPrayersSinceTheCutoff() throws Exception {
        db.logPrayer(row(100, "old", "ok", true));
        db.logPrayer(row(1000, "a", "ok", true));
        db.logPrayer(row(2000, "b", "error:x", true));
        db.logPrayer(row(3000, "c", "filtered:BLOCKED", false));
        db.logPrayer(row(4000, "d", "limited:COOLDOWN", false));
        var last = new HashMap<UUID, Instant>();
        var counts = db.countedSince(500, last);
        assertEquals(2, counts.get(u));
        assertEquals(Instant.ofEpochMilli(2000), last.get(u));
    }

    @Test void usageAndStats() throws Exception {
        db.logUsage(10, u, "m", 100, 20, 0.5);
        db.logUsage(5000, u, "m", 100, 20, 0.25);
        assertEquals(0.25, db.costSince(1000), 1e-9);
        assertEquals(0.75, db.costSince(0), 1e-9);
        db.logPrayer(row(1000, "a", "ok", true));
        db.logPrayer(row(2000, "b", "filtered:EMPTY", false));
        long[] st = db.statsSince(0);
        assertEquals(2, st[0]);
        assertEquals(1, st[1]);
        assertEquals(200, st[2]);
        assertEquals(40, st[3]);
    }

    @Test void hostileTextIsStoredVerbatimAndHarmlessly() throws Exception {
        String evil = "'); DROP TABLE prayers; --";
        db.logPrayer(row(1, evil, "ok", true));
        assertEquals(java.util.List.of(evil), db.recentPrayers(u, 3));
    }

    @Test void refundedPrayersAreNotCountedAfterARestart() throws Exception {
        db.logPrayer(row(1000, "ok one", "ok", true));
        db.logPrayer(row(2000, "api failed", "error:HTTP 529 overloaded_error", false));   // refunded: counted=false
        db.logPrayer(row(3000, "bad reply", "invalid:unsafe reply", false));              // refunded
        db.logPrayer(row(4000, "ok two", "ok", true));
        var last = new HashMap<UUID, Instant>();
        assertEquals(2, db.countedSince(0, last).get(u), "only the two answered prayers use up slots when counts are restored");
        assertEquals(Instant.ofEpochMilli(4000), last.get(u));
    }

    @Test void decreeTablesAndSummaryQueries() throws Exception {
        db.touchPlayer(u, "Steve", 5000);
        db.addFavor(u, 7);
        db.logDeath(u, 6000);
        db.logDeath(u, 7000);
        db.logPrayer(row(8000, "a prayer", "ok", true));
        db.logDecree(9000, "[]", "text", "model", 100, 50);
        assertEquals(1, db.count("deaths", "ts", 6500));
        assertEquals(1, db.count("players", "first_seen", 4000));
        assertEquals(java.util.List.of("Steve (7)"), db.topFavor(3));
        assertEquals(java.util.List.of("a prayer"), db.prayersSince(0, 5));
    }
}
