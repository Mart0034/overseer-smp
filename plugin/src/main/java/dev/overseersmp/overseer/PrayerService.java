package dev.overseersmp.overseer;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** The prayer pipeline: filter -> limits -> budget -> (async) prompt + API call -> validate -> log -> (main thread) broadcast + effect. */
public final class PrayerService {
    static final String SILENT = "The Overseer is silent.";

    private final OverseerPlugin plugin;
    private final ExecutorService io;

    public PrayerService(OverseerPlugin plugin, ExecutorService io) {
        this.plugin = plugin;
        this.io = io;
    }

    /** Main thread. */
    public void pray(Player p, String raw) {
        Settings s = plugin.settings();
        UUID id = p.getUniqueId();
        String text = InputFilter.clean(raw);

        if (s.silenced || !s.hasKey()) {
            tell(p, SILENT, NamedTextColor.GRAY);
            return;
        }
        InputFilter.Verdict v = plugin.inputFilter().check(text);
        if (v != InputFilter.Verdict.OK) {
            tell(p, switch (v) {
                case EMPTY -> "You pray to nothing, pilgrim. Try words.";
                case TOO_LONG -> "Brevity, pilgrim. The heavens tire of long speeches (" + s.maxPrayerChars + " characters at most).";
                default -> "The heavens do not hear such words, mortal.";
            }, NamedTextColor.GRAY);
            log(p, text.length() > 400 ? text.substring(0, 400) : text, null, null, null, 0, 0, 0, 0, "filtered:" + v, false);
            return;
        }
        PrayerLimits.Check c = plugin.limits().check(id, 0);
        if (c.result() != PrayerLimits.Result.OK) {
            tell(p, switch (c.result()) {
                case COOLDOWN -> "Patience, pilgrim. The Overseer will hear you again in " + c.retrySeconds() + " seconds.";
                case DAILY_PLAYER -> "You have prayed enough for one day. The Overseer's ear returns at dawn.";
                default -> "The heavens are crowded today. Return tomorrow.";
            }, NamedTextColor.GRAY);
            log(p, text, null, null, null, 0, 0, 0, 0, "limited:" + c.result(), false);
            return;
        }
        if (!plugin.cost().canSpend()) {
            tell(p, SILENT, NamedTextColor.GRAY);
            log(p, text, null, null, null, 0, 0, 0, 0, "budget", false);
            return;
        }
        plugin.limits().record(id);
        tell(p, "You raise your voice to the heavens...", NamedTextColor.DARK_GRAY);
        String name = p.getName();
        io.execute(() -> runAsync(id, name, text, s));
    }

    /** I/O thread: read context, call the API, validate, log, then hand the result to the main thread. */
    private void runAsync(UUID id, String name, String text, Settings s) {
        long t0 = System.currentTimeMillis();
        try {
            int favor = plugin.db().favor(id);
            List<String> recent = plugin.db().recentPrayers(id, s.history);
            String system = PromptBuilder.system(plugin.persona(), plugin.decreeText(), favor, recent);
            String user = PromptBuilder.user(name, text);
            plugin.client().complete(s, system, user).whenCompleteAsync((reply, err) -> finish(id, name, text, s, t0, reply, err), io);
        } catch (SQLException | RuntimeException e) {
            finish(id, name, text, s, t0, null, e);
        }
    }

    private void finish(UUID id, String name, String text, Settings s, long t0, AnthropicClient.Reply reply, Throwable err) {
        long now = System.currentTimeMillis();
        try {
            if (err != null || reply == null) {
                String why = err == null ? "no reply" : (err instanceof java.util.concurrent.CompletionException && err.getCause() != null ? err.getCause() : err).getMessage();
                plugin.getLogger().warning("Prayer API failure: " + why);
                dbLog(new Database.PrayerRow(now, id, name, text, null, null, null, 0, 0, 0, now - t0, "error:" + clip(why), true));
                toPlayer(id, SILENT);
                return;
            }
            double cost = plugin.cost().add(reply.inputTokens(), reply.outputTokens());
            plugin.db().logUsage(now, id, s.model, reply.inputTokens(), reply.outputTokens(), cost);
            ResponseParser.Result r = new ResponseParser(s, plugin.contentFilter()).parse(reply.text());
            if (!r.ok()) {
                plugin.getLogger().warning("Rejected model reply: " + r.problem());
                dbLog(new Database.PrayerRow(now, id, name, text, clip(reply.text()), null, null, 0, reply.inputTokens(), reply.outputTokens(), reply.latencyMs(), "invalid:" + r.problem(), true));
                toPlayer(id, SILENT);
                return;
            }
            Decision d = r.decision();
            plugin.db().addFavor(id, d.favorDelta());
            dbLog(new Database.PrayerRow(now, id, name, text, d.reply(), d.action().name().toLowerCase(), d.effectId() == null ? "none" : d.effectId(),
                    d.favorDelta(), reply.inputTokens(), reply.outputTokens(), reply.latencyMs(), "ok", true));
            Bukkit.getScheduler().runTask(plugin, () -> deliver(id, name, d));
        } catch (SQLException | RuntimeException e) {
            plugin.getLogger().warning("Prayer handling failed: " + e.getClass().getSimpleName());
            toPlayer(id, SILENT);
        }
    }

    /** Main thread. */
    private void deliver(UUID id, String name, Decision d) {
        Component line = Component.text("[The Overseer]", NamedTextColor.GOLD, TextDecoration.BOLD)
                .append(Component.text(" → ", NamedTextColor.DARK_GRAY).decoration(TextDecoration.BOLD, false))
                .append(Component.text(name, NamedTextColor.WHITE).decoration(TextDecoration.BOLD, false))
                .append(Component.text(": ", NamedTextColor.DARK_GRAY).decoration(TextDecoration.BOLD, false))
                .append(Component.text(d.reply(), NamedTextColor.YELLOW).decoration(TextDecoration.BOLD, false).decorate(TextDecoration.ITALIC));
        Bukkit.getServer().sendMessage(line);
        Bukkit.getServer().playSound(Sound.sound(Key.key("minecraft:block.bell.use"), Sound.Source.MASTER, 0.7f, 0.6f));
        Player p = Bukkit.getPlayer(id);
        if (p != null && p.isOnline() && !(d.effect() instanceof Effect.None)) EffectApplier.apply(p, d.effect());
    }

    private void toPlayer(UUID id, String msg) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = Bukkit.getPlayer(id);
            if (p != null) tell(p, msg, NamedTextColor.GRAY);
        });
    }

    private static void tell(Player p, String msg, NamedTextColor color) {
        p.sendMessage(Component.text(msg, color, TextDecoration.ITALIC));
    }

    private void log(Player p, String prayer, String reply, String action, String effect, int fd, int in, int out, long ms, String status, boolean counted) {
        long now = System.currentTimeMillis();
        io.execute(() -> dbLog(new Database.PrayerRow(now, p.getUniqueId(), p.getName(), prayer, reply, action, effect, fd, in, out, ms, status, counted)));
    }

    private void dbLog(Database.PrayerRow row) {
        try { plugin.db().logPrayer(row); } catch (SQLException e) { plugin.getLogger().warning("DB write failed: " + e.getMessage()); }
    }

    private static String clip(String s) {
        if (s == null) return null;
        String c = s.replaceAll("\\s+", " ");
        return c.length() > 300 ? c.substring(0, 300) : c;
    }
}
