package dev.overseersmp.overseer.favor;

/** Title bands for favor (-100..100): Heretic <= -50, Doubter < 0, Pilgrim 0-24, Faithful 25-49, Devout 50-74, Prophet >= 75. */
public enum FavorTitle {
    HERETIC("Heretic", "dark_red", Integer.MIN_VALUE),
    DOUBTER("Doubter", "gray", -49),
    PILGRIM("Pilgrim", "white", 0),
    FAITHFUL("Faithful", "green", 25),
    DEVOUT("Devout", "aqua", 50),
    PROPHET("Prophet", "gold", 75);

    public final String display;
    /** Adventure named colour. */
    public final String color;
    /** Lowest favor in the band. */
    public final int from;

    FavorTitle(String display, String color, int from) {
        this.display = display;
        this.color = color;
        this.from = from;
    }

    public static FavorTitle forFavor(int favor) {
        FavorTitle best = HERETIC;
        for (FavorTitle t : values()) if (favor >= t.from && t.from > Integer.MIN_VALUE) best = t;
        return favor <= -50 ? HERETIC : best;
    }

    /** The next band up, or null at the top. */
    public FavorTitle next() {
        return ordinal() + 1 < values().length ? values()[ordinal() + 1] : null;
    }

    /** Favor still needed to reach the next band (0 at the top). For Heretic, the next band starts at -49. */
    public int toNext(int favor) {
        FavorTitle n = next();
        return n == null ? 0 : Math.max(0, n.from - favor);
    }
}
