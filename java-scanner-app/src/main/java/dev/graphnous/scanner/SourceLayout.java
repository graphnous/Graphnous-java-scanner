package dev.graphnous.scanner;

import dev.graphnous.scanner.java.maven.MavenPom;
import dev.graphnous.scanner.model.File;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Where the production and test sources of a module are, to tell which
 * of the two a file is.
 */
final class SourceLayout {

    private final Path modulePath;

    /**
     * The source directories the build configures, deepest first, so a
     * directory nested in another decides for its files.
     */
    private final List<Map.Entry<Path, File.SourceSet>> directories;

    private SourceLayout(
        final Path modulePath,
        final List<Path> main,
        final List<Path> test
    ) {
        this.modulePath = modulePath.toAbsolutePath().normalize();

        this.directories = Stream.concat(
                main.stream().map(directory -> Map.entry(absolute(directory), File.SourceSet.MAIN)),
                test.stream().map(directory -> Map.entry(absolute(directory), File.SourceSet.TEST))
            )
            .sorted(Comparator.comparingInt(
                (Map.Entry<Path, File.SourceSet> entry) -> entry.getKey().getNameCount()
            ).reversed())
            .toList();
    }

    /**
     * The layout of the module: the source directories its {@code pom.xml}
     * configures, if any. A pom that cannot be read configures none; the
     * module detector already warns about it.
     */
    static SourceLayout of(final Path modulePath) {
        final var pom = modulePath.resolve("pom.xml");

        if (Files.isRegularFile(pom)) {
            try {
                final var mavenPom = MavenPom.read(pom);

                return new SourceLayout(
                    modulePath,
                    mavenPom.sourceDirectories(),
                    mavenPom.testSourceDirectories()
                );
            } catch (IllegalStateException ignored) {
                // Falls back to the conventional layout
            }
        }

        return new SourceLayout(modulePath, List.of(), List.of());
    }

    /**
     * Whether the file is production or test code: from the deepest source
     * directory the build configures that contains it, or else from the
     * conventional directory it is in below the module, see
     * {@link #conventional}.
     */
    File.SourceSet sourceSet(final Path file) {
        final var path = absolute(file);

        return directories.stream()
            .filter(directory -> path.startsWith(directory.getKey()))
            .map(Map.Entry::getValue)
            .findFirst()
            .orElseGet(() -> conventional(path));
    }

    /**
     * {@code src/main} for production code, and {@code src/test} or
     * another test source set such as Gradle's {@code src/integrationTest}
     * or {@code src/testFixtures} for test code. {@code null} for files
     * outside these directories.
     */
    private File.SourceSet conventional(final Path file) {
        if (!file.startsWith(modulePath)) {
            return null;
        }

        final var relative = modulePath.relativize(file);

        // src/<source set>/<language>/..., so at least three segments
        if (relative.getNameCount() < 3 || !relative.getName(0).toString().equals("src")) {
            return null;
        }

        final var name = relative.getName(1).toString();

        if (name.equals("main")) {
            return File.SourceSet.MAIN;
        }

        if (name.startsWith("test") || name.endsWith("Test")) {
            return File.SourceSet.TEST;
        }

        return null;
    }

    private static Path absolute(final Path path) {
        return path.toAbsolutePath().normalize();
    }
}
