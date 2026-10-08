package dev.overseersmp.overseer;

import java.util.Map;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.entity.Chicken;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/** Applies an already-validated {@link Effect}. Main thread only. Nothing here damages, takes items or teleports. */
public final class EffectApplier {
    private EffectApplier() {}

    public static void apply(Player p, Effect effect) {
        switch (effect) {
            case Effect.None n -> { }
            case Effect.Potion e -> {
                PotionEffectType t = Registry.EFFECT.get(NamespacedKey.minecraft(e.key()));
                if (t != null) p.addPotionEffect(new PotionEffect(t, e.seconds() * 20, e.amplifier(), false, true, true));
            }
            case Effect.Gift g -> {
                Material m = Material.matchMaterial(g.material());
                if (m != null) {
                    Map<Integer, ItemStack> left = p.getInventory().addItem(new ItemStack(m, g.amount()));
                    left.values().forEach(i -> p.getWorld().dropItemNaturally(p.getLocation(), i));
                }
            }
            case Effect.Chicken c -> p.getWorld().spawn(p.getLocation().add(0, 3, 0), Chicken.class, ch -> {
                ch.setPersistent(false);
                ch.setRemoveWhenFarAway(true);
            });
            case Effect.Lightning l -> {
                Location loc = p.getLocation();
                p.getWorld().strikeLightningEffect(loc);   // visual + sound only: no damage, no fire
                p.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, loc.clone().add(0, 1, 0), 40, 0.5, 1, 0.5, 0.1);
            }
        }
    }
}
