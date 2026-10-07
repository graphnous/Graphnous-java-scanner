package dev.graphnous.scanner.java.maven;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * Reads the Maven version a project builds with from its Maven wrapper,
 * {@code .mvn/wrapper/maven-wrapper.properties}.
 */
public final class MavenWrapper {

    private static final Path PROPERTIES = Path.of(".mvn", "wrapper", "maven-wrapper.properties");

    /**
     * The version in a distribution URL such as
     * {@code .../apache-maven/3.9.9/apache-maven-3.9.9-bin.zip}.
     */
    private static final Pattern VERSION = Pattern.compile("apache-maven-(\\d[^/]*?)-bin\\.(?:zip|tar\\.gz)$");

    private MavenWrapper() {
    }

    /**
     * The Maven version of the wrapper of the project, or of the nearest
     * directory above it within the repository, as a module uses the
     * wrapper of the project it is part of.
     */
    public static Optional<String> version(
        final Path repository,
        final Path project
    ) {
        final var root = repository.toAbsolutePath().normalize();

        for (var directory = project.toAbsolutePath().normalize();
             directory != null && directory.startsWith(root);
             directory = directory.getParent()) {
            final var properties = directory.resolve(PROPERTIES);

            if (Files.isRegularFile(properties)) {
                return distributionVersion(properties);
            }
        }

        return Optional.empty();
    }

    private static Optional<String> distributionVersion(final Path file) {
        final var properties = new Properties();

        try (final var input = Files.newInputStream(file)) {
            properties.load(input);
        } catch (IOException e) {
            return Optional.empty();
        }

        return Optional.ofNullable(properties.getProperty("distributionUrl"))
            .map(url -> VERSION.matcher(url.trim()))
            .filter(java.util.regex.Matcher::find)
            .map(matcher -> matcher.group(1));
    }
}
