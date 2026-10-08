package dev.overseersmp.overseer.decree.mods;

import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Slow falling + jump boost as INFINITE-duration effects. Only infinite effects are ever removed, so a player's normal
 * (timed) potions are never touched. tick() re-applies after deaths, milk and relogs.
 */
public final class FeatherDayModifier extends BukkitModifier {
    public FeatherDayModifier(JavaPlugin plugin) { super(plugin); }

    @Override public String id() { return "feather_day"; }
    @Override public String displayName() { return "Feather Day"; }
    @Override public String description() { return "Gravity forgets you: you fall slowly and leap higher."; }
    @Override public Map<String, Bound> bounds() { return bounds("jump_level", 0, 1, 0, "0 = Jump Boost I, 1 = Jump Boost II"); }

    @Override protected void onEnable(Map<String, String> state) { for (Player p : Bukkit.getOnlinePlayers()) apply(p); }

    @Override protected void onDisable(Map<String, String> state) { for (Player p : Bukkit.getOnlinePlayers()) cleanup(p); }

    @Override public void tick() { if (active) for (Player p : Bukkit.getOnlinePlayers()) apply(p); }

    @Override public void onJoin(Player p) { if (active) apply(p); else cleanup(p); }

    private void apply(Player p) {
        int jump = (int) Math.round(param("jump_level"));
        give(p, PotionEffectType.SLOW_FALLING, 0);
        give(p, PotionEffectType.JUMP_BOOST, jump);
    }

    private static void give(Player p, PotionEffectType t, int amp) {
        PotionEffect e = p.getPotionEffect(t);
        if (e != null && !e.isInfinite()) return;               // a real, timed potion: leave it alone
        if (e != null && e.getAmplifier() == amp) return;       // already ours
        p.addPotionEffect(new PotionEffect(t, PotionEffect.INFINITE_DURATION, amp, true, false, true));
    }

    private static void cleanup(Player p) {
        for (PotionEffectType t : new PotionEffectType[] {PotionEffectType.SLOW_FALLING, PotionEffectType.JUMP_BOOST}) {
            PotionEffect e = p.getPotionEffect(t);
            if (e != null && e.isInfinite()) p.removePotionEffect(t);
        }
    }
}
