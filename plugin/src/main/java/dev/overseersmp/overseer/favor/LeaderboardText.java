package dev.overseersmp.overseer.favor;

import java.util.ArrayList;
import java.util.List;

/** Lines of the top-10 hologram (DecentHolograms colour codes with &). */
public final class LeaderboardText {
    public record Row(String name, int favor) {}

    public static final int SIZE = 10;

    private LeaderboardText() {}

    public static List<String> lines(List<Row> top) {
        List<String> out = new ArrayList<>();
        out.add("&6&l✦ Top Pilgrims ✦");
        for (int i = 0; i < SIZE; i++) {
            if (i < top.size()) {
                Row r = top.get(i);
                out.add("&e" + (i + 1) + ". &f" + safe(r.name()) + " &7- &6" + r.favor() + " &8(" + FavorTitle.forFavor(r.favor()).display + ")");
            } else {
                out.add("&e" + (i + 1) + ". &8-");
            }
        }
        out.add("&8Pray with /pray");
        return out;
    }

    /** Names go into formatting-code text: keep only characters a Minecraft name can contain. */
    static String safe(String n) {
        if (n == null) return "?";
        String s = n.replaceAll("[^A-Za-z0-9_. ]", "");
        return s.length() > 16 ? s.substring(0, 16) : s;
    }
}
