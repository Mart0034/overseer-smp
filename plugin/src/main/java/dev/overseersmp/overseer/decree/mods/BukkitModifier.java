package dev.overseersmp.overseer.decree.mods;

import dev.overseersmp.overseer.decree.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Shared plumbing for the real (Bukkit) modifiers. All methods run on the main server thread. */
public abstract class BukkitModifier implements Modifier {
    protected final JavaPlugin plugin;
    protected volatile boolean active;
    protected volatile Map<String, Double> params = Map.of();

    protected BukkitModifier(JavaPlugin plugin) { this.plugin = plugin; }

    @Override public final void enable(Map<String, Double> p, Map<String, String> state) {
        params = p;
        active = true;
        onEnable(state);
    }

    @Override public final void disable(Map<String, Double> p, Map<String, String> state) {
        active = false;
        onDisable(state);
    }

    protected void onEnable(Map<String, String> state) {}

    protected void onDisable(Map<String, String> state) {}

    /** Called for every modifier when a player joins: apply if active, strip stale effects if not. */
    public void onJoin(Player p) {}

    public boolean isActive() { return active; }

    protected double param(String name) {
        Double v = params.get(name);
        return v != null ? v : bounds().get(name).def();
    }

    /** Modifiers that cannot be combined with this one. */
    protected Set<String> conflicts() { return Set.of(); }

    @Override public boolean compatibleWith(String otherId) { return !otherId.equals(id()) && !conflicts().contains(otherId); }

    protected static Map<String, Bound> bounds(Object... nameMinMaxDef) {
        Map<String, Bound> m = new LinkedHashMap<>();
        for (int i = 0; i < nameMinMaxDef.length; i += 4) {
            m.put((String) nameMinMaxDef[i], new Bound(((Number) nameMinMaxDef[i + 1]).doubleValue(), ((Number) nameMinMaxDef[i + 2]).doubleValue(),
                    ((Number) nameMinMaxDef[i + 3]).doubleValue()));
        }
        return m;
    }
}
