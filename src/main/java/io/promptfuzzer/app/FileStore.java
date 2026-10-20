package io.promptfuzzer.app;

import io.promptfuzzer.core.Ids;
import io.promptfuzzer.core.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Stores JSON documents as one file per id in a directory. Writes go through a temp file and an atomic move so
 * a crash never leaves a half-written report. Ids are validated so a request can never escape the directory.
 */
public final class FileStore<T> {

    private final Path dir;
    private final Class<T> type;

    public FileStore(Path dir, Class<T> type) {
        this.dir = dir;
        this.type = type;
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create " + dir, e);
        }
    }

    public void save(String id, T value) {
        Path target = file(id);
        Path tmp = dir.resolve(id + ".json.tmp");
        try {
            Files.writeString(tmp, Json.pretty(value));
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot save " + target, e);
        }
    }

    public Optional<T> load(String id) {
        if (!Ids.isSafe(id)) {
            return Optional.empty();
        }
        Path f = file(id);
        return Files.exists(f) ? Optional.of(Json.read(f, type)) : Optional.empty();
    }

    public boolean delete(String id) {
        if (!Ids.isSafe(id)) {
            return false;
        }
        try {
            return Files.deleteIfExists(file(id));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot delete " + id, e);
        }
    }

    /** All documents, newest file first. Unreadable files are skipped. */
    public List<T> list() {
        List<T> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> sorted = files.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .toList();
            for (Path p : sorted) {
                try {
                    out.add(Json.read(p, type));
                } catch (RuntimeException e) {
                    System.err.println("Skipping unreadable " + p + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list " + dir, e);
        }
        return out;
    }

    private Path file(String id) {
        if (!Ids.isSafe(id)) {
            throw new IllegalArgumentException("Invalid id: " + id);
        }
        return dir.resolve(id + ".json");
    }
}
