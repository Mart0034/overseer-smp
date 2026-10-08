package dev.overseersmp.overseer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** SQLite storage (plugin data folder). All methods are synchronized on one connection; call them off the main thread. */
public final class Database implements AutoCloseable {
    /** One row of the prayers table. {@code counted} = 1 when the prayer used up a daily slot (reached the API stage). */
    public record PrayerRow(long ts, UUID uuid, String name, String prayer, String reply, String action, String effect,
                            int favorDelta, int inputTokens, int outputTokens, long latencyMs, String status, boolean counted) {}

    private final Connection c;

    public Database(String jdbcUrl) throws SQLException {
        try { Class.forName("org.sqlite.JDBC"); } catch (ClassNotFoundException ignored) { /* DriverManager may still find it */ }
        c = DriverManager.getConnection(jdbcUrl);
        try (Statement s = c.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("CREATE TABLE IF NOT EXISTS players(uuid TEXT PRIMARY KEY, name TEXT, first_seen INTEGER, last_seen INTEGER, fame_optin INTEGER DEFAULT 0, favor INTEGER DEFAULT 0)");
            s.execute("CREATE TABLE IF NOT EXISTS prayers(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, uuid TEXT, name TEXT, prayer TEXT, reply TEXT, action TEXT, effect TEXT, favor_delta INTEGER, input_tokens INTEGER, output_tokens INTEGER, latency_ms INTEGER, status TEXT, counted INTEGER)");
            s.execute("CREATE INDEX IF NOT EXISTS prayers_uuid_ts ON prayers(uuid, ts)");
            s.execute("CREATE TABLE IF NOT EXISTS api_usage(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, uuid TEXT, model TEXT, input_tokens INTEGER, output_tokens INTEGER, cost_usd REAL)");
        }
    }

    public synchronized void touchPlayer(UUID uuid, String name, long nowMs) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(
                "INSERT INTO players(uuid,name,first_seen,last_seen) VALUES(?,?,?,?) ON CONFLICT(uuid) DO UPDATE SET name=excluded.name,last_seen=excluded.last_seen")) {
            p.setString(1, uuid.toString()); p.setString(2, name); p.setLong(3, nowMs); p.setLong(4, nowMs);
            p.executeUpdate();
        }
    }

    public synchronized int favor(UUID uuid) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("SELECT favor FROM players WHERE uuid=?")) {
            p.setString(1, uuid.toString());
            try (ResultSet r = p.executeQuery()) { return r.next() ? r.getInt(1) : 0; }
        }
    }

    /** Adds delta, clamps to -100..100, returns the new value. */
    public synchronized int addFavor(UUID uuid, int delta) throws SQLException {
        int v = Math.max(-100, Math.min(100, favor(uuid) + delta));
        try (PreparedStatement p = c.prepareStatement("UPDATE players SET favor=? WHERE uuid=?")) {
            p.setInt(1, v); p.setString(2, uuid.toString());
            p.executeUpdate();
        }
        return v;
    }

    public synchronized void logPrayer(PrayerRow r) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(
                "INSERT INTO prayers(ts,uuid,name,prayer,reply,action,effect,favor_delta,input_tokens,output_tokens,latency_ms,status,counted) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            p.setLong(1, r.ts()); p.setString(2, r.uuid().toString()); p.setString(3, r.name()); p.setString(4, r.prayer());
            p.setString(5, r.reply()); p.setString(6, r.action()); p.setString(7, r.effect()); p.setInt(8, r.favorDelta());
            p.setInt(9, r.inputTokens()); p.setInt(10, r.outputTokens()); p.setLong(11, r.latencyMs()); p.setString(12, r.status());
            p.setInt(13, r.counted() ? 1 : 0);
            p.executeUpdate();
        }
    }

    public synchronized void logUsage(long ts, UUID uuid, String model, int in, int out, double cost) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("INSERT INTO api_usage(ts,uuid,model,input_tokens,output_tokens,cost_usd) VALUES(?,?,?,?,?,?)")) {
            p.setLong(1, ts); p.setString(2, uuid.toString()); p.setString(3, model); p.setInt(4, in); p.setInt(5, out); p.setDouble(6, cost);
            p.executeUpdate();
        }
    }

    /** The player's last n answered prayers (status ok), oldest first. */
    public synchronized List<String> recentPrayers(UUID uuid, int n) throws SQLException {
        List<String> out = new ArrayList<>();
        if (n <= 0) return out;
        try (PreparedStatement p = c.prepareStatement("SELECT prayer FROM prayers WHERE uuid=? AND status='ok' ORDER BY id DESC LIMIT ?")) {
            p.setString(1, uuid.toString()); p.setInt(2, n);
            try (ResultSet r = p.executeQuery()) { while (r.next()) out.add(0, r.getString(1)); }
        }
        return out;
    }

    /** Counted prayers per player since the instant, plus each player's latest counted prayer time. */
    public synchronized Map<UUID, Integer> countedSince(long sinceMs, Map<UUID, Instant> lastOut) throws SQLException {
        Map<UUID, Integer> counts = new HashMap<>();
        try (PreparedStatement p = c.prepareStatement("SELECT uuid, COUNT(*), MAX(ts) FROM prayers WHERE counted=1 AND ts>=? GROUP BY uuid")) {
            p.setLong(1, sinceMs);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) {
                    UUID u = UUID.fromString(r.getString(1));
                    counts.put(u, r.getInt(2));
                    lastOut.put(u, Instant.ofEpochMilli(r.getLong(3)));
                }
            }
        }
        return counts;
    }

    public synchronized double costSince(long sinceMs) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("SELECT COALESCE(SUM(cost_usd),0) FROM api_usage WHERE ts>=?")) {
            p.setLong(1, sinceMs);
            try (ResultSet r = p.executeQuery()) { return r.next() ? r.getDouble(1) : 0; }
        }
    }

    /** {prayers, counted, input tokens, output tokens} since the instant. */
    public synchronized long[] statsSince(long sinceMs) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("SELECT COUNT(*), COALESCE(SUM(counted),0), COALESCE(SUM(input_tokens),0), COALESCE(SUM(output_tokens),0) FROM prayers WHERE ts>=?")) {
            p.setLong(1, sinceMs);
            try (ResultSet r = p.executeQuery()) { r.next(); return new long[] {r.getLong(1), r.getLong(2), r.getLong(3), r.getLong(4)}; }
        }
    }

    @Override public synchronized void close() {
        try { c.close(); } catch (SQLException ignored) { /* shutting down */ }
    }
}
