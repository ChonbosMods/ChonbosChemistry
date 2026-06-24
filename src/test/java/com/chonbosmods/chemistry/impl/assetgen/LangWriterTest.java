package com.chonbosmods.chemistry.impl.assetgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link LangWriter#merge} is the one piece of genuinely new parsing logic in the world-fluid task:
 * the two generators rely on it to share a single {@code server.lang} without clobbering each
 * other's keys. These hermetic {@code @TempDir} tests pin its contract directly (the generators
 * only exercise it integration-style).
 */
class LangWriterTest {

    private static Map<String, String> entries(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    /** Parse a written lang file back into key -> value (mirroring how the engine reads it). */
    private static Map<String, String> read(Path file) throws IOException {
        Map<String, String> m = new LinkedHashMap<>();
        for (String line : Files.readAllLines(file)) {
            int eq = line.indexOf('=');
            if (eq >= 0) {
                m.put(line.substring(0, eq).strip(), line.substring(eq + 1).strip());
            }
        }
        return m;
    }

    @Test
    void mergeIntoMissingFileCreatesItWithEntries(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("server.lang");
        assertFalse(Files.exists(file));

        LangWriter.merge(file, entries("items.A.name", "Alpha", "items.B.name", "Beta"));

        assertTrue(Files.exists(file));
        Map<String, String> got = read(file);
        assertEquals("Alpha", got.get("items.A.name"));
        assertEquals("Beta", got.get("items.B.name"));
        assertEquals(2, got.size());
    }

    @Test
    void mergePreservesUnrelatedExistingKey(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("server.lang");
        Files.writeString(file, "items.Solid.name = Iron\n");

        LangWriter.merge(file, entries("items.Fluid.name", "Water"));

        Map<String, String> got = read(file);
        assertEquals("Iron", got.get("items.Solid.name"), "the solid generator's key must survive");
        assertEquals("Water", got.get("items.Fluid.name"));
        assertEquals(2, got.size());
    }

    @Test
    void mergeLastWriteWinsOnKeyCollision(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("server.lang");
        Files.writeString(file, "items.X.name = OldValue\n");

        LangWriter.merge(file, entries("items.X.name", "NewValue"));

        Map<String, String> got = read(file);
        assertEquals("NewValue", got.get("items.X.name"));
        assertEquals(1, got.size(), "the colliding key is overwritten, not duplicated");
    }

    @Test
    void mergeIsIdempotent(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("server.lang");
        Map<String, String> e = entries("items.A.name", "Alpha", "items.B.name", "Beta");

        LangWriter.merge(file, e);
        String first = Files.readString(file);
        LangWriter.merge(file, e);
        String second = Files.readString(file);

        assertEquals(first, second, "re-merging the same entries reproduces identical content");
    }

    @Test
    void valueContainingEqualsRoundTrips(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("server.lang");

        // Split on the FIRST '=': key is "a", value keeps the rest ("b=c").
        LangWriter.merge(file, entries("a", "b=c"));
        // And a pre-existing line with an '=' in the value survives a re-read + re-write.
        LangWriter.merge(file, entries("d", "e"));

        Map<String, String> got = read(file);
        assertEquals("b=c", got.get("a"));
        assertEquals("e", got.get("d"));
        assertEquals(2, got.size());
    }

    @Test
    void blankAndCommentLinesAreIgnored(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("server.lang");
        Files.writeString(file, """
            # a comment line
            items.Keep.name = Kept

            #another comment
            """);

        LangWriter.merge(file, entries("items.New.name", "Fresh"));

        Map<String, String> got = read(file);
        assertEquals("Kept", got.get("items.Keep.name"));
        assertEquals("Fresh", got.get("items.New.name"));
        assertEquals(2, got.size(), "comments and blanks contribute no keys");
        assertFalse(Files.readString(file).contains("#"), "comment lines are dropped on rewrite");
    }
}
