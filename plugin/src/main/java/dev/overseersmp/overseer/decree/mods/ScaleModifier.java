package dev.overseersmp.overseer.decree.mods;

import java.util.Map;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Player size via a keyed ADD_SCALAR modifier on the scale attribute (never by overwriting the base value), so removing it
 * restores exactly the original size. Players who were offline when the decree ended are cleaned on their next join.
 */
public final class ScaleModifier extends BukkitModifier {
    private final String id, name, description, other;
    private final double min, max, def;
    private final NamespacedKey key;

    private ScaleModifier(JavaPlugin plugin, String id, String name, String description, String other, double min, double max, double def) {
        super(plugin);
        this.id = id; this.name = name; this.description = description; this.other = other;
        this.min = min; this.max = max; this.def = def;
        this.key = new NamespacedKey(plugin, "decree_" + id);
    }

    public static ScaleModifier small(JavaPlugin p) {
        return new ScaleModifier(p, "day_of_the_small", "Day of the Small", "Everyone is half their size. Mind the creepers.", "day_of_giants", 0.4, 0.75, 0.5);
    }

    public static ScaleModifier giants(JavaPlugin p) {
        return new ScaleModifier(p, "day_of_giants", "Day of Giants", "Everyone is half again as tall. Doorways will complain.", "day_of_the_small", 1.25, 2.0, 1.5);
    }

    @Override public String id() { return id; }
    @Override public String displayName() { return name; }
    @Override public String description() { return description; }
    @Override public Map<String, Bound> bounds() { return bounds("scale", min, max, def); }
    @Override protected Set<String> conflicts() { return Set.of(other); }

    @Override protected void onEnable(Map<String, String> state) { for (Player p : Bukkit.getOnlinePlayers()) apply(p); }

    @Override protected void onDisable(Map<String, String> state) { for (Player p : Bukkit.getOnlinePlayers()) cleanup(p); }

    @Override public void onJoin(Player p) { if (active) apply(p); else cleanup(p); }

    private void apply(Player p) {
        AttributeInstance a = p.getAttribute(Attribute.SCALE);
        if (a == null) return;
        a.removeModifier(key);
        a.addModifier(new AttributeModifier(key, param("scale") - 1.0, AttributeModifier.Operation.ADD_SCALAR));
    }

    private void cleanup(Player p) {
        AttributeInstance a = p.getAttribute(Attribute.SCALE);
        if (a != null) a.removeModifier(key);
    }
}
