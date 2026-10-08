package dev.overseersmp.overseer.decree.mods;

import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.GameRules;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * keepInventory for every world. The previous value of each world is written to the persisted decree state BEFORE the rule
 * is changed, so even a crash right after enabling (or a restart mid-decree) restores the world's original setting.
 */
public final class MercifulDeathModifier extends BukkitModifier {
    private static final String PREFIX = "merciful_death.prev.";

    public MercifulDeathModifier(JavaPlugin plugin) { super(plugin); }

    @Override public String id() { return "merciful_death"; }
    @Override public String displayName() { return "Merciful Death"; }
    @Override public String description() { return "Death takes nothing from you today. Keep your inventory."; }
    @Override public Map<String, Bound> bounds() { return Map.of(); }

    @Override protected void onEnable(Map<String, String> state) {
        for (World w : Bukkit.getWorlds()) {
            String key = PREFIX + w.getName();
            if (!state.containsKey(key)) state.put(key, String.valueOf(Boolean.TRUE.equals(w.getGameRuleValue(GameRules.KEEP_INVENTORY))));
            w.setGameRule(GameRules.KEEP_INVENTORY, true);
        }
    }

    @Override protected void onDisable(Map<String, String> state) {
        for (World w : Bukkit.getWorlds()) {
            String key = PREFIX + w.getName();
            String prev = state.get(key);
            if (prev == null) continue;                    // we never changed this world
            w.setGameRule(GameRules.KEEP_INVENTORY, Boolean.parseBoolean(prev));
            state.remove(key);
        }
    }
}
