package dev.overseersmp.overseer.decree.mods;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Monster;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * More hostile mobs and more drops, at night in the overworld only. Purely event-driven: when inactive the listener does
 * nothing, so there is no state to restore. Extra mobs use SpawnReason.CUSTOM (so they are not multiplied again) and are
 * skipped when 40 monsters are already within 32 blocks.
 */
public final class BloodMoonModifier extends BukkitModifier implements Listener {
    public BloodMoonModifier(JavaPlugin plugin) { super(plugin); }

    @Override public String id() { return "blood_moon"; }
    @Override public String displayName() { return "Blood Moon"; }
    @Override public String description() { return "At night more monsters rise, and they carry more loot."; }
    @Override public Map<String, Bound> bounds() { return bounds("spawn_multiplier", 1.0, 2.0, 1.5, "1.5 means about 50% more hostile mobs at night, 2.0 means double", "drop_multiplier", 1.0, 2.0, 1.5, "1.5 means about 50% more loot from night mobs, 2.0 means double"); }

    private static boolean night(World w) {
        long t = w.getTime();
        return w.getEnvironment() == World.Environment.NORMAL && t >= 13000 && t < 23000;
    }

    @EventHandler(ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        if (!active || e.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL || !(e.getEntity() instanceof Monster)) return;
        World w = e.getLocation().getWorld();
        if (!night(w) || ThreadLocalRandom.current().nextDouble() >= param("spawn_multiplier") - 1.0) return;
        Location l = e.getLocation();
        if (w.getNearbyEntities(l, 32, 16, 32, x -> x instanceof Monster).size() >= 40) return;
        w.spawnEntity(l, e.getEntityType(), CreatureSpawnEvent.SpawnReason.CUSTOM);
    }

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        if (!active || !(e.getEntity() instanceof Monster) || e.getEntity().getKiller() == null || !night(e.getEntity().getWorld())) return;
        double extra = param("drop_multiplier") - 1.0;
        var rnd = ThreadLocalRandom.current();
        for (ItemStack s : new ArrayList<>(e.getDrops())) if (rnd.nextDouble() < extra) e.getDrops().add(s.clone());
    }
}
