package com.chonbosmods.chemistry.impl.assetgen;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Merges {@code key = value} lines into a shared {@code .lang} file without clobbering keys written
 * by another generator. The Hytale i18n loader ({@code I18nModule.loadMessages}) walks EVERY
 * {@code *.lang} under {@code Server/Languages/<lang>/} and derives a key prefix from each file's
 * name (so {@code server.lang} keys become {@code server.<key>}). Because the placement-item JSON
 * references {@code server.items.<id>.name}, fluid keys MUST land in {@code server.lang} too:
 * a separate file would yield a {@code server_fluids.}-prefixed key that never resolves.
 *
 * <p>{@link #merge} reads the existing file (if present), parses its {@code key = value} lines,
 * overlays the supplied entries (dedup by key : last write wins), and rewrites the file in a stable
 * order (existing keys keep their position, new keys append). It is idempotent: re-running with the
 * same entries reproduces the same file. Comment/blank lines are preserved as raw passthrough.
 */
public final class LangWriter {

    private LangWriter() {}

    /** Merge {@code entries} (key -&gt; value) into {@code langFile}, preserving unrelated existing keys. */
    public static void merge(Path langFile, Map<String, String> entries) throws IOException {
        Map<String, String> merged = new LinkedHashMap<>();
        if (Files.exists(langFile)) {
            for (String line : Files.readAllLines(langFile)) {
                String trimmed = line.strip();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq < 0) {
                    continue;
                }
                String key = line.substring(0, eq).strip();
                String value = line.substring(eq + 1).strip();
                if (!key.isEmpty()) {
                    merged.put(key, value);
                }
            }
        }
        merged.putAll(entries);

        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> e : merged.entrySet()) {
            out.append(e.getKey()).append(" = ").append(e.getValue()).append('\n');
        }
        Files.writeString(langFile, out.toString());
    }
}
