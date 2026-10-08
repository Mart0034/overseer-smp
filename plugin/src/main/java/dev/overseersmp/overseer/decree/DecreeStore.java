package dev.overseersmp.overseer.decree;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Persists the active decree and the date the last scheduled decree fired (decree.json in the plugin folder). Writes are atomic. */
public final class DecreeStore {
    /** @param lastFired ISO date (Europe/Berlin) of the last scheduled decree, or null */
    public record Data(String lastFired, ActiveDecree active) {}

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path file;

    public DecreeStore(Path file) { this.file = file; }

    public Data load() {
        try {
            if (!Files.exists(file)) return new Data(null, null);
            Data d = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Data.class);
            return d == null ? new Data(null, null) : d;
        } catch (IOException | RuntimeException e) {
            return new Data(null, null);   // corrupt file: start clean rather than crash the server
        }
    }

    public void save(Data d) {
        try {
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(d), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
