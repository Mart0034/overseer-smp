package dev.overseersmp.overseer;

import java.time.Clock;
import java.time.YearMonth;

/** Local estimate of the month's API spend. Stops calls once the configured cap is reached (on top of the console limit). */
public final class CostTracker {
    private final Clock clock;
    private volatile Settings settings;
    private YearMonth month;
    private double spent;

    public CostTracker(Settings settings, Clock clock) {
        this.settings = settings;
        this.clock = clock;
        this.month = current();
    }

    public void updateSettings(Settings s) { this.settings = s; }

    private YearMonth current() { return YearMonth.from(clock.instant().atZone(PrayerLimits.ZONE)); }

    public static double cost(Settings s, int inputTokens, int outputTokens) {
        return inputTokens * s.priceInPerMtok / 1_000_000.0 + outputTokens * s.priceOutPerMtok / 1_000_000.0;
    }

    public synchronized void seed(double monthToDate) { roll(); spent = monthToDate; }

    public synchronized double add(int inputTokens, int outputTokens) {
        roll();
        double c = cost(settings, inputTokens, outputTokens);
        spent += c;
        return c;
    }

    public synchronized double spent() { roll(); return spent; }

    public synchronized boolean canSpend() { roll(); return spent < settings.monthlyCapUsd; }

    private void roll() {
        YearMonth m = current();
        if (!m.equals(month)) { month = m; spent = 0; }
    }
}
