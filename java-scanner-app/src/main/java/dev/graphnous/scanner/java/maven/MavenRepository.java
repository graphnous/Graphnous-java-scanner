package dev.graphnous.scanner.java.maven;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * A local Maven repository, such as {@code ~/.m2/repository}, with the
 * poms and jars Maven downloaded when it built the scanned project.
 * Artifacts are at {@code group/path/artifact/version/artifact-version.jar}.
 */
public final class MavenRepository {

    /**
     * No repository: nothing is found in it.
     */
    public static final MavenRepository NONE = new MavenRepository(null);

    private static final Pattern VERSION_PARTS = Pattern.compile("[.\\-]");

    private final Path root;

    /**
     * The poms read from the repository, as many dependencies share their
     * parents and BOMs.
     */
    private final Map<Path, Optional<MavenPom>> poms = new ConcurrentHashMap<>();

    private MavenRepository(final Path root) {
        this.root = root;
    }

    public static MavenRepository at(final Path root) {
        return Files.isDirectory(root) ? new MavenRepository(root) : NONE;
    }

    /**
     * The repository Maven uses by default, {@code ~/.m2/repository}, or
     * {@link #NONE} when there is none.
     */
    public static MavenRepository userDefault() {
        final var home = System.getProperty("user.home");

        return home == null || home.isBlank()
            ? NONE
            : at(Path.of(home, ".m2", "repository"));
    }

    public boolean exists() {
        return root != null;
    }

    public Optional<Path> pom(
        final String groupId,
        final String artifactId,
        final String version
    ) {
        return artifact(groupId, artifactId, version, "pom");
    }

    public Optional<Path> jar(
        final String groupId,
        final String artifactId,
        final String version
    ) {
        return artifact(groupId, artifactId, version, "jar");
    }

    /**
     * The pom with its parents, or nothing when it cannot be read.
     */
    public Optional<MavenPom> read(final Path pom) {
        // Not computeIfAbsent: reading a pom reads its parents, which
        // would update the map while it is being updated
        final var known = poms.get(pom);

        if (known != null) {
            return known;
        }

        Optional<MavenPom> read;

        try {
            read = Optional.of(MavenPom.read(pom, this));
        } catch (IllegalStateException e) {
            read = Optional.empty();
        }

        poms.put(pom, read);

        return read;
    }

    /**
     * The newest version of the artifact in the repository, for a
     * dependency whose version the poms do not tell.
     */
    public Optional<String> newestVersion(
        final String groupId,
        final String artifactId
    ) {
        return directory(groupId, artifactId).flatMap(directory -> {
            try (var versions = Files.list(directory)) {
                return versions
                    .filter(Files::isDirectory)
                    .map(version -> version.getFileName().toString())
                    .filter(version -> Files.isRegularFile(directory.resolve(version).resolve(artifactId + "-" + version + ".pom")))
                    .max(MavenRepository::compareVersions);
            } catch (IOException e) {
                return Optional.empty();
            }
        });
    }

    private Optional<Path> artifact(
        final String groupId,
        final String artifactId,
        final String version,
        final String extension
    ) {
        if (version == null || version.isBlank()) {
            return Optional.empty();
        }

        return directory(groupId, artifactId)
            .map(directory -> directory.resolve(version).resolve(artifactId + "-" + version + "." + extension).normalize())
            // A version such as ../../x must not leave the artifact
            .filter(path -> path.startsWith(root))
            .filter(Files::isRegularFile);
    }

    private Optional<Path> directory(
        final String groupId,
        final String artifactId
    ) {
        if (root == null || groupId.isBlank() || artifactId.isBlank()
            || artifactId.contains("/") || artifactId.contains("..")) {
            return Optional.empty();
        }

        final var directory = root.resolve(groupId.replace('.', '/')).resolve(artifactId).normalize();

        return directory.startsWith(root) && Files.isDirectory(directory)
            ? Optional.of(directory)
            : Optional.empty();
    }

    /**
     * Compares versions by their numeric parts, so 1.10 is newer than 1.9,
     * and a release newer than its snapshots and milestones, e.g. 2.0 is
     * newer than 2.0-SNAPSHOT and 2.0-M1.
     */
    static int compareVersions(
        final String first,
        final String second
    ) {
        final var firstParts = parts(first);
        final var secondParts = parts(second);

        for (int i = 0; i < Math.max(firstParts.size(), secondParts.size()); i++) {
            final var a = i < firstParts.size() ? firstParts.get(i) : null;
            final var b = i < secondParts.size() ? secondParts.get(i) : null;

            // A qualifier after the numbers makes a pre-release, and an
            // extra number a newer version
            if (a == null) {
                return b instanceof String ? 1 : -1;
            }
            if (b == null) {
                return a instanceof String ? -1 : 1;
            }

            final var comparison = compareParts(a, b);

            if (comparison != 0) {
                return comparison;
            }
        }

        return 0;
    }

    private static int compareParts(
        final Object a,
        final Object b
    ) {
        if (a instanceof Long x && b instanceof Long y) {
            return Long.compare(x, y);
        }

        // Numbers are newer than qualifiers, as 1.0.1 is newer than 1.0-RC1
        if (a instanceof Long) {
            return 1;
        }
        if (b instanceof Long) {
            return -1;
        }

        return ((String) a).compareToIgnoreCase((String) b);
    }

    private static List<Object> parts(final String version) {
        final var parts = new ArrayList<Object>();

        for (final var part : VERSION_PARTS.split(version)) {
            if (part.isEmpty()) {
                continue;
            }

            try {
                parts.add(Long.parseLong(part));
            } catch (NumberFormatException e) {
                parts.add(part);
            }
        }

        return parts;
    }
}
