package dev.overseersmp.overseer.decree;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Owns the active decree: enabling and disabling modifiers, persisting everything needed to restore after a restart.
 * Plain Java (no Bukkit), so the lifecycle is unit-tested with fake modifiers.
 */
public final class DecreeEngine {
    public static final Duration LENGTH = Duration.ofHours(24);

    /** Map that persists the whole decree every time a modifier writes restore data. */
    private final class StateBag extends LinkedHashMap<String, String> {
        StateBag(Map<String, String> init) { super(init == null ? Map.of() : init); }
        @Override public String put(String k, String v) { String r = super.put(k, v); persist(); return r; }
        @Override public String remove(Object k) { String r = super.remove(k); persist(); return r; }
    }

    private final Map<String, Modifier> registry;
    private final DecreeStore store;
    private final Clock clock;
    private final Consumer<String> log;
    private ActiveDecree active;
    private StateBag state;
    private LocalDate lastFired;

    public DecreeEngine(Map<String, Modifier> registry, DecreeStore store, Clock clock, Consumer<String> log) {
        this.registry = registry;
        this.store = store;
        this.clock = clock;
        this.log = log;
        this.lastFired = null;
    }

    public synchronized Optional<ActiveDecree> active() { return Optional.ofNullable(active); }

    public synchronized LocalDate lastFired() { return lastFired; }

    public Map<String, Modifier> registry() { return registry; }

    /** Startup: re-enable a decree that is still running, or restore the world if it expired while the server was down. */
    public synchronized void restore() {
        DecreeStore.Data d = store.load();
        lastFired = d.lastFired() == null ? null : LocalDate.parse(d.lastFired());
        active = d.active();
        if (active == null) return;
        state = new StateBag(active.state());
        if (!clock.instant().isBefore(Instant.ofEpochMilli(active.expiresAt()))) {
            log.accept("Decree expired while the server was down; restoring the world");
            disableAll();
            active = null;
            state = null;
            persist();
        } else {
            log.accept("Restoring decree after restart: " + ids(active));
            enableAll();
        }
    }

    /** First run only: avoid an immediate surprise decree (see {@link DecreeSchedule#initialLastFired}). */
    public synchronized void ensureInitialised(int hour) {
        if (lastFired == null) {
            lastFired = DecreeSchedule.initialLastFired(clock.instant(), hour);
            persist();
        }
    }

    /** Replace whatever is running with this decree. {@code scheduled} marks today's slot as used. */
    public synchronized ActiveDecree activate(List<ActiveDecree.Selected> selected, String text, String source, boolean scheduled) {
        if (active != null) disableAll();
        Instant now = clock.instant();
        state = new StateBag(null);
        active = new ActiveDecree(text, source, now.toEpochMilli(), now.plus(LENGTH).toEpochMilli(), List.copyOf(selected), state);
        if (scheduled) lastFired = DecreeSchedule.today(now);
        persist();      // persisted BEFORE enabling, so a crash mid-enable is cleaned up by restore()
        enableAll();
        return active;
    }

    public synchronized void clear() {
        if (active == null) return;
        disableAll();
        active = null;
        state = null;
        persist();
    }

    public synchronized void tick() {
        if (active == null) return;
        if (!clock.instant().isBefore(Instant.ofEpochMilli(active.expiresAt()))) { clear(); return; }
        for (var s : active.selected()) {
            Modifier m = registry.get(s.id());
            if (m == null) continue;
            try { m.tick(); } catch (RuntimeException e) { log.accept("Modifier " + s.id() + " tick failed: " + e); }
        }
    }

    private void enableAll() {
        for (var s : active.selected()) {
            Modifier m = registry.get(s.id());
            if (m == null) { log.accept("Unknown modifier in decree: " + s.id()); continue; }
            try { m.enable(s.params(), state); } catch (RuntimeException e) { log.accept("Modifier " + s.id() + " enable failed: " + e); }
        }
    }

    private void disableAll() {
        if (active == null) return;
        for (var s : active.selected()) {
            Modifier m = registry.get(s.id());
            if (m == null) continue;
            try { m.disable(s.params(), state != null ? state : new LinkedHashMap<>()); } catch (RuntimeException e) { log.accept("Modifier " + s.id() + " disable failed: " + e); }
        }
    }

    private void persist() {
        ActiveDecree toSave = active == null ? null
                : new ActiveDecree(active.text(), active.source(), active.startedAt(), active.expiresAt(), active.selected(), new LinkedHashMap<>(state));
        store.save(new DecreeStore.Data(lastFired == null ? null : lastFired.toString(), toSave));
    }

    private static String ids(ActiveDecree d) { return d.selected().stream().map(ActiveDecree.Selected::id).toList().toString(); }
}
