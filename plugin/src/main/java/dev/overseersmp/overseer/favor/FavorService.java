package dev.overseersmp.overseer.favor;

import dev.overseersmp.overseer.Action;
import dev.overseersmp.overseer.Effect;
import dev.overseersmp.overseer.EffectApplier;
import dev.overseersmp.overseer.EffectCatalog;
import dev.overseersmp.overseer.OverseerPlugin;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/** Favor titles in chat, /favor, /fame, the top-10 hologram at spawn, and the decree-time gifts. Favor itself lives in the database. */
public final class FavorService implements Listener {
    private final OverseerPlugin plugin;
    private final ExecutorService io;
    private final Map<UUID, Integer> cache = new ConcurrentHashMap<>();
    private final HologramBoard board;
    private final Random random = new Random();

    public FavorService(OverseerPlugin plugin, ExecutorService io) {
        this.plugin = plugin;
        this.io = io;
        this.board = new HologramBoard("overseer_top", plugin.getLogger());
    }

    /** Main thread, from onEnable. */
    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        for (Player p : Bukkit.getOnlinePlayers()) load(p.getUniqueId());
        Bukkit.getScheduler().runTaskTimer(plugin, this::refreshBoard, 200L, 20L * 60 * 5);   // first draw after 10 s, then every 5 min
    }

    public void stop() { board.hide(); }

    // ---- cache
    public void load(UUID id) {
        io.execute(() -> {
            try { cache.put(id, plugin.db().favor(id)); } catch (SQLException e) { plugin.getLogger().warning("DB: " + e.getMessage()); }
        });
    }

    public int cached(UUID id) { return cache.getOrDefault(id, 0); }

    /** Any thread. Stores the new favor and tells the player when they cross into another title. */
    public void update(UUID id, int favor) {
        Integer old = cache.put(id, favor);
        FavorTitle before = FavorTitle.forFavor(old == null ? 0 : old), now = FavorTitle.forFavor(favor);
        if (before != now) {
            boolean up = now.ordinal() > before.ordinal();
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player p = Bukkit.getPlayer(id);
                if (p != null) p.sendMessage(Component.text(up ? "Your standing rises: you are now " + article(now) + " " + now.display + "."
                        : "Your standing falls: you are now " + article(now) + " " + now.display + ".", up ? NamedTextColor.GOLD : NamedTextColor.GRAY));
            });
        }
    }

    private static String article(FavorTitle t) { return "aeiou".indexOf(Character.toLowerCase(t.display.charAt(0))) >= 0 ? "an" : "a"; }

    // ---- chat prefix: "[Faithful] " in the title's colour, in front of whatever format EssentialsChat produced
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        FavorTitle t = FavorTitle.forFavor(cached(e.getPlayer().getUniqueId()));
        var prefix = Component.text("[" + t.display + "] ", net.kyori.adventure.text.format.NamedTextColor.NAMES.valueOrThrow(t.color));
        var previous = e.renderer();
        e.renderer((source, name, message, viewer) -> prefix.append(previous.render(source, name, message, viewer)));
    }

    // ---- /favor [player]
    public void favorCommand(CommandSender sender, String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player p)) { sender.sendMessage("Usage: /favor <player>"); return; }
            int f = cached(p.getUniqueId());
            FavorTitle t = FavorTitle.forFavor(f);
            String more = t.next() == null ? "You have reached the highest standing." : t.toNext(f) + " more favor to become " + article(t.next()) + " " + t.next().display + ".";
            p.sendMessage(Component.text("Your favor: " + f + " (" + t.display + "). " + more, NamedTextColor.GOLD));
            return;
        }
        String name = args[0];
        io.execute(() -> {
            String msg;
            try {
                Object[] row = plugin.db().favorByName(name);
                if (row == null) msg = "The Overseer knows no pilgrim named " + name + ".";
                else msg = row[0] + ": favor " + row[1] + " (" + FavorTitle.forFavor((Integer) row[1]).display + ").";
            } catch (SQLException ex) { msg = "The Overseer is silent."; }
            final String out = msg;
            Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(Component.text(out, NamedTextColor.GOLD)));
        });
    }

    // ---- /fame on|off|status
    public void fameCommand(Player p, String[] args) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase();
        UUID id = p.getUniqueId();
        io.execute(() -> {
            String msg;
            try {
                switch (sub) {
                    case "on" -> { plugin.db().setFame(id, true); msg = "Fame is ON: videos may show your name."; }
                    case "off" -> { plugin.db().setFame(id, false); msg = "Fame is OFF: in videos you appear as \"a pilgrim\"."; }
                    default -> msg = "Fame is " + (plugin.db().fame(id) ? "ON" : "OFF") + ". Use /fame on or /fame off. (Videos show your name only if it is ON.)";
                }
            } catch (SQLException ex) { msg = "The Overseer is silent."; }
            final String out = msg;
            Bukkit.getScheduler().runTask(plugin, () -> p.sendMessage(Component.text(out, NamedTextColor.GOLD)));
        });
    }

    // ---- leaderboard hologram at spawn
    public void refreshBoard() {
        if (!board.available()) return;
        io.execute(() -> {
            try {
                List<LeaderboardText.Row> rows = new ArrayList<>();
                for (Object[] r : plugin.db().topFavorRows(LeaderboardText.SIZE)) rows.add(new LeaderboardText.Row((String) r[0], (Integer) r[1]));
                List<String> lines = LeaderboardText.lines(rows);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Location spawn = Bukkit.getWorlds().get(0).getSpawnLocation().clone().add(0.5, 4.0, 0.5);
                    board.show(spawn, lines);
                });
            } catch (SQLException e) { plugin.getLogger().warning("Leaderboard: " + e.getMessage()); }
        });
    }

    // ---- decree-time gifts (main thread)
    public int decreeGifts() {
        int n = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            var gift = FavorGifts.pick(cached(p.getUniqueId()), random);
            if (gift.isEmpty()) continue;
            Effect fx = EffectCatalog.resolve(gift.get().action(), gift.get().effectId(), plugin.settings());
            if (fx instanceof Effect.None) continue;
            EffectApplier.apply(p, fx);
            p.sendMessage(Component.text(gift.get().message(), gift.get().action() == Action.BLESS ? NamedTextColor.GOLD : NamedTextColor.DARK_RED));
            n++;
        }
        return n;
    }
}
