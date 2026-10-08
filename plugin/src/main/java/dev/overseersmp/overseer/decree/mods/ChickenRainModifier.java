package dev.overseersmp.overseer.decree.mods;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Chicken;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Harmless chickens fall near players standing in the open in the overworld. Chickens take no fall damage, are not persistent
 * (they vanish on restart), live at most 45 s, are capped, and are tagged so any stray can be swept up.
 */
public final class ChickenRainModifier extends BukkitModifier {
    private static final long LIFETIME_MS = 45_000;
    private static final int MAX_ALIVE = 30;
    private final NamespacedKey tag;
    private final Map<UUID, Long> alive = new HashMap<>();
    private double sinceLast;

    public ChickenRainModifier(JavaPlugin plugin) {
        super(plugin);
        this.tag = new NamespacedKey(plugin, "decree_chicken");
    }

    @Override public String id() { return "chicken_rain"; }
    @Override public String displayName() { return "Chicken Rain"; }
    @Override public String description() { return "It is raining chickens. They are harmless, and judging you."; }
    @Override public Map<String, Bound> bounds() { return bounds("interval_seconds", 20, 120, 45); }

    @Override protected void onEnable(Map<String, String> state) { sinceLast = 0; sweep(); }

    @Override protected void onDisable(Map<String, String> state) { sweep(); alive.clear(); }

    /** Called every 5 s. */
    @Override public void tick() {
        if (!active) return;
        long now = System.currentTimeMillis();
        alive.entrySet().removeIf(e -> {
            Entity c = Bukkit.getEntity(e.getKey());
            if (c == null || !c.isValid()) return true;
            if (now - e.getValue() > LIFETIME_MS) { c.remove(); return true; }
            return false;
        });
        sinceLast += 5;
        if (sinceLast < param("interval_seconds")) return;
        sinceLast = 0;
        List<Player> open = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            World w = p.getWorld();
            if (w.getEnvironment() == World.Environment.NORMAL && w.getHighestBlockYAt(p.getLocation()) <= p.getLocation().getBlockY() + 1) open.add(p);
        }
        if (open.isEmpty()) return;
        var rnd = ThreadLocalRandom.current();
        Player target = open.get(rnd.nextInt(open.size()));
        int n = 1 + rnd.nextInt(3);
        for (int i = 0; i < n && alive.size() < MAX_ALIVE; i++) {
            Location l = target.getLocation().add(rnd.nextDouble(-5, 5), 10, rnd.nextDouble(-5, 5));
            l.setY(Math.min(l.getY(), target.getWorld().getMaxHeight() - 2));
            Chicken c = target.getWorld().spawn(l, Chicken.class, ch -> {
                ch.setPersistent(false);
                ch.setRemoveWhenFarAway(true);
                ch.getPersistentDataContainer().set(tag, PersistentDataType.BYTE, (byte) 1);
            });
            alive.put(c.getUniqueId(), now);
        }
    }

    /** Remove every tagged chicken in loaded chunks. */
    private void sweep() {
        for (World w : Bukkit.getWorlds())
            for (Chicken c : w.getEntitiesByClass(Chicken.class))
                if (c.getPersistentDataContainer().has(tag, PersistentDataType.BYTE)) c.remove();
    }
}
