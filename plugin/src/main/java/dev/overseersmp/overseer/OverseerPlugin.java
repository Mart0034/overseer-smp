package dev.overseersmp.overseer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class OverseerPlugin extends JavaPlugin implements Listener {
    private volatile Settings settings;
    private volatile ContentFilter contentFilter;
    private volatile InputFilter inputFilter;
    private volatile String persona = "";
    private Database db;
    private PrayerLimits limits;
    private CostTracker cost;
    private final AnthropicClient client = new AnthropicClient();
    private ExecutorService io;
    private PrayerService prayers;
    private Path silenceFlag;

    @Override public void onEnable() {
        saveDefaultConfig();
        saveResource("persona.md", false);   // copied once; edit plugins/Overseer/persona.md and /overseer reload to tune
        silenceFlag = getDataFolder().toPath().resolve("silenced");
        io = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "Overseer-IO"); t.setDaemon(true); return t; });
        try {
            db = new Database("jdbc:sqlite:" + getDataFolder().toPath().resolve("overseer.db").toAbsolutePath());
        } catch (SQLException e) {
            getLogger().severe("Cannot open database: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        load();
        limits = new PrayerLimits(settings, Clock.systemUTC());
        cost = new CostTracker(settings, Clock.systemUTC());
        seedFromDb();
        prayers = new PrayerService(this, io);
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("Enabled. model=" + settings.model + ", API key " + (settings.hasKey() ? "configured" : "MISSING (the Overseer will be silent)")
                + (settings.silenced ? ", SILENCED" : ""));
    }

    @Override public void onDisable() {
        if (io != null) io.shutdown();
        if (db != null) db.close();
    }

    /** (Re)read config.yml and persona.md. */
    private void load() {
        Map<String, Object> flat = new HashMap<>(getConfig().getValues(true));
        if (Files.exists(silenceFlag)) flat.put("silenced", true);
        Settings s = Settings.from(flat);
        settings = s;
        contentFilter = new ContentFilter(s.extraWords);
        inputFilter = new InputFilter(s, contentFilter);
        try {
            persona = Files.readString(getDataFolder().toPath().resolve("persona.md"), StandardCharsets.UTF_8);
        } catch (IOException e) {
            try (InputStream in = getResource("persona.md")) {
                persona = in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException ex) { persona = ""; }
        }
        if (limits != null) limits.updateSettings(s);
        if (cost != null) cost.updateSettings(s);
    }

    private void seedFromDb() {
        ZonedDateTime now = ZonedDateTime.now(PrayerLimits.ZONE);
        long dayStart = now.toLocalDate().atStartOfDay(PrayerLimits.ZONE).toInstant().toEpochMilli();
        long monthStart = now.toLocalDate().withDayOfMonth(1).atStartOfDay(PrayerLimits.ZONE).toInstant().toEpochMilli();
        try {
            Map<UUID, Instant> last = new HashMap<>();
            limits.seed(db.countedSince(dayStart, last), last);
            cost.seed(db.costSince(monthStart));
        } catch (SQLException e) {
            getLogger().warning("Could not restore today's counters: " + e.getMessage());
        }
    }

    // ---- accessors for the service
    Settings settings() { return settings; }
    ContentFilter contentFilter() { return contentFilter; }
    InputFilter inputFilter() { return inputFilter; }
    String persona() { return persona; }
    Database db() { return db; }
    PrayerLimits limits() { return limits; }
    CostTracker cost() { return cost; }
    AnthropicClient client() { return client; }
    /** v0.2 will return the active decree; v0.1 has none. */
    String decreeText() { return ""; }

    // ---- events
    @EventHandler public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        long now = System.currentTimeMillis();
        io.execute(() -> { try { db.touchPlayer(p.getUniqueId(), p.getName(), now); } catch (SQLException ex) { getLogger().warning("DB: " + ex.getMessage()); } });
        p.sendMessage(Component.text("Pray to the Overseer with /pray <message>. Prayers are public and may appear in our videos.", NamedTextColor.GOLD));
    }

    // ---- commands
    @Override public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("pray")) {
            if (!(sender instanceof Player p)) { sender.sendMessage("Only players can pray."); return true; }
            if (args.length == 0) { p.sendMessage(Component.text("Usage: /pray <message>", NamedTextColor.GRAY)); return true; }
            prayers.pray(p, String.join(" ", args));
            return true;
        }
        return admin(sender, args);
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (cmd.getName().equalsIgnoreCase("overseer") && args.length == 1) return List.of("reload", "stats", "silence", "forcedecree");
        return List.of();
    }

    private boolean admin(CommandSender sender, String[] args) {
        String sub = args.length == 0 ? "stats" : args[0].toLowerCase();
        switch (sub) {
            case "reload" -> {
                reloadConfig();
                load();
                sender.sendMessage("Overseer reloaded. " + settings);
            }
            case "silence" -> {
                boolean nowSilent = !Files.exists(silenceFlag);
                try {
                    if (nowSilent) Files.writeString(silenceFlag, "silenced");
                    else Files.deleteIfExists(silenceFlag);
                } catch (IOException e) { sender.sendMessage("Could not change the silence flag: " + e.getMessage()); return true; }
                load();
                sender.sendMessage("The Overseer is now " + (nowSilent ? "SILENCED (no API calls)." : "listening again."));
            }
            case "forcedecree" -> sender.sendMessage("Decrees arrive in v0.2.");
            default -> {
                long dayStart = ZonedDateTime.now(PrayerLimits.ZONE).toLocalDate().atStartOfDay(PrayerLimits.ZONE).toInstant().toEpochMilli();
                io.execute(() -> {
                    try {
                        long[] st = db.statsSince(dayStart);
                        String msg = String.format("Today: %d prayers (%d used a slot), %d in / %d out tokens. Month spend ~$%.4f of $%.2f cap. %s",
                                st[0], st[1], st[2], st[3], cost.spent(), settings.monthlyCapUsd, settings.silenced ? "SILENCED." : "Listening.");
                        Bukkit.getScheduler().runTask(this, () -> sender.sendMessage(msg));
                    } catch (SQLException e) { sender.sendMessage("Stats failed: " + e.getMessage()); }
                });
            }
        }
        return true;
    }
}
