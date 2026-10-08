package dev.overseersmp.overseer.favor;

import java.lang.reflect.Method;
import java.util.List;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;

/**
 * Shows lines as a DecentHolograms hologram, through DecentHolograms' public DHAPI by reflection (its Maven repository is not
 * reachable, and this way the plugin still works when DecentHolograms is absent). The hologram is not saved to DH's files:
 * it is rebuilt on every refresh, so there are never stale copies.
 */
public final class HologramBoard {
    private final String name;
    private final Logger log;
    private Method create, remove;
    private boolean warned;

    public HologramBoard(String name, Logger log) {
        this.name = name;
        this.log = log;
    }

    public boolean available() {
        if (create != null) return true;
        if (!Bukkit.getPluginManager().isPluginEnabled("DecentHolograms")) return false;
        try {
            Class<?> api = Class.forName("eu.decentsoftware.holograms.api.DHAPI");
            create = api.getMethod("createHologram", String.class, Location.class, boolean.class, List.class);
            remove = api.getMethod("removeHologram", String.class);
            return true;
        } catch (ReflectiveOperationException | LinkageError e) {
            if (!warned) { log.warning("DecentHolograms API not usable: " + e.getClass().getSimpleName()); warned = true; }
            return false;
        }
    }

    /** Main thread. Replaces the hologram with these lines at this location. */
    public void show(Location where, List<String> lines) {
        if (!available()) return;
        try {
            try { remove.invoke(null, name); } catch (ReflectiveOperationException ignored) { /* none yet */ }
            create.invoke(null, name, where, false, lines);
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (!warned) { log.warning("Could not update the leaderboard hologram: " + e); warned = true; }
        }
    }

    public void hide() {
        if (remove == null) return;
        try { remove.invoke(null, name); } catch (ReflectiveOperationException | RuntimeException ignored) { /* shutting down */ }
    }
}
