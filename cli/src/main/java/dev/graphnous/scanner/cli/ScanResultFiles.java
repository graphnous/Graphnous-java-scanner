package dev.graphnous.scanner.cli;

import dev.graphnous.scanner.ScanResultWriter;
import dev.graphnous.scanner.model.ScanResultSchema;
import dev.graphnous.scanner.model.ScanTarget;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * Writes each scan result to its own file, named after its target,
 * e.g. {@code java-root.json} or {@code typescript-apps-web.json}.
 */
class ScanResultFiles {

    private final ScanResultWriter writer;

    ScanResultFiles(final ScanResultWriter writer) {
        this.writer = writer;
    }

    List<Path> write(
        final List<ScanResultSchema> results,
        final Path directory
    ) {
        createDirectory(directory);

        final var used = new HashSet<String>();
        final var files = new ArrayList<Path>();

        for (final var result : results) {
            final var file = directory.resolve(uniqueName(fileName(result.getTarget()), used));

            writer.write(result, file);
            files.add(file);
        }

        return files;
    }

    static String fileName(final ScanTarget target) {
        final var language = target.getLanguage() == null
            ? "unknown"
            : target.getLanguage().value().toLowerCase(Locale.ROOT);

        final var path = target.getPath() == null || target.getPath().equals(".")
            ? "root"
            : target.getPath()
                .replaceAll("[\\\\/]+", "-")
                .replaceAll("[^A-Za-z0-9._-]", "_");

        return language + "-" + path;
    }

    private static String uniqueName(
        final String name,
        final HashSet<String> used
    ) {
        var candidate = name;

        for (int i = 2; !used.add(candidate); i++) {
            candidate = name + "-" + i;
        }

        return candidate + ".json";
    }

    private static void createDirectory(final Path directory) {
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new UncheckedIOException(
                "Failed to create output directory " + directory,
                e
            );
        }
    }
}
