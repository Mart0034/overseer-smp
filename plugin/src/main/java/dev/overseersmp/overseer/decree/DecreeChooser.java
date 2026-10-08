package dev.overseersmp.overseer.decree;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Picks the decree: the model's answer if it validates, otherwise weighted-random with template text. Never returns nothing. */
public final class DecreeChooser {
    public record Choice(List<ActiveDecree.Selected> selected, String text, String source) {}

    private final Map<String, Modifier> registry;
    private final DecreeParser parser;

    public DecreeChooser(Map<String, Modifier> registry, DecreeParser parser) {
        this.registry = registry;
        this.parser = parser;
    }

    /** @param modelRaw the raw model text, or null if there was no usable answer (silenced, no key, over budget, API error) */
    public Choice choose(String modelRaw, String previousId, Random rnd) {
        if (modelRaw != null) {
            DecreeParser.Result r = parser.parse(modelRaw);
            if (r.ok()) return new Choice(r.parsed().selected(), r.parsed().text(), "model");
        }
        return fallback(previousId, rnd);
    }

    /** Weighted random, with yesterday's modifier strongly down-weighted so the days feel different. */
    public Choice fallback(String previousId, Random rnd) {
        List<Modifier> mods = new ArrayList<>(registry.values());
        double total = 0;
        double[] w = new double[mods.size()];
        for (int i = 0; i < w.length; i++) {
            w[i] = Math.max(0, mods.get(i).weight()) * (mods.get(i).id().equals(previousId) ? 0.1 : 1.0);
            total += w[i];
        }
        Modifier pick = mods.get(0);
        if (total > 0) {
            double x = rnd.nextDouble() * total;
            for (int i = 0; i < w.length; i++) {
                x -= w[i];
                if (x < 0) { pick = mods.get(i); break; }
            }
        }
        return new Choice(List.of(new ActiveDecree.Selected(pick.id(), pick.defaults())), template(pick), "fallback");
    }

    /** Admin-forced decree: the given ids at default parameters. Unknown ids are ignored; null if none are valid. */
    public Choice forced(List<String> ids) {
        List<ActiveDecree.Selected> sel = new ArrayList<>();
        for (String id : ids) {
            Modifier m = registry.get(id.trim().toLowerCase(java.util.Locale.ROOT));
            if (m == null || sel.stream().anyMatch(s -> s.id().equals(m.id()))) continue;
            boolean ok = sel.stream().allMatch(s -> registry.get(s.id()).compatibleWith(m.id()) && m.compatibleWith(s.id()));
            if (ok) sel.add(new ActiveDecree.Selected(m.id(), m.defaults()));
        }
        if (sel.isEmpty()) return null;
        String text = sel.size() == 1 ? template(registry.get(sel.get(0).id()))
                : "Hear me, mortals: " + String.join(" and ", sel.stream().map(s -> registry.get(s.id()).displayName()).toList()) + " shall rule until the next dusk.";
        return new Choice(List.copyOf(sel), text, "forced");
    }

    static String template(Modifier m) {
        return "Hear me, mortals. Until the next dusk: " + m.displayName() + ". " + m.description();
    }
}
