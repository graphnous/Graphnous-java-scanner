package dev.graphnous.scanner.java.maven;

import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/**
 * A local Maven repository for tests, with poms and jars compiled from
 * sources, at the paths Maven puts them.
 */
public final class LocalRepository {

    private final Path root;

    public LocalRepository(final Path root) {
        this.root = root;
    }

    public Path root() {
        return root;
    }

    public MavenRepository repository() {
        return MavenRepository.at(root);
    }

    /**
     * Adds the pom of the artifact, with the dependencies given as
     * {@code <dependency>} elements, or any other content of the project.
     */
    public Path pom(
        final String groupId,
        final String artifactId,
        final String version,
        final String content
    ) {
        return write(
            groupId, artifactId, version, "pom",
            ("""
                <project>
                    <groupId>%s</groupId>
                    <artifactId>%s</artifactId>
                    <version>%s</version>
                    %s
                </project>
                """).formatted(groupId, artifactId, version, content)
        );
    }

    /**
     * Adds a jar of the artifact with the classes compiled from the
     * sources, by their path, e.g. {@code org/example/Client.java}.
     */
    public Path jar(
        final String groupId,
        final String artifactId,
        final String version,
        final Map<String, String> sources
    ) {
        try {
            // Next to the repository, so it goes with the directory of the test
            final var work = Files.createDirectories(root.resolveSibling("build").resolve(artifactId + "-" + version));
            final var files = new ArrayList<String>();

            for (final var source : sources.entrySet()) {
                final var file = work.resolve("src").resolve(source.getKey());
                Files.createDirectories(file.getParent());
                Files.writeString(file, source.getValue());
                files.add(file.toString());
            }

            final var classes = Files.createDirectories(work.resolve("classes"));
            final var arguments = new ArrayList<String>(java.util.List.of("-d", classes.toString()));
            arguments.addAll(files);

            if (ToolProvider.getSystemJavaCompiler().run(null, null, null, arguments.toArray(String[]::new)) != 0) {
                throw new IllegalStateException("Could not compile " + sources.keySet());
            }

            final var jar = path(groupId, artifactId, version, "jar");
            Files.createDirectories(jar.getParent());

            try (var output = new JarOutputStream(Files.newOutputStream(jar));
                 var classFiles = Files.walk(classes)) {
                for (final var classFile : classFiles.filter(Files::isRegularFile).toList()) {
                    output.putNextEntry(new JarEntry(classes.relativize(classFile).toString().replace('\\', '/')));
                    output.write(Files.readAllBytes(classFile));
                    output.closeEntry();
                }
            }

            return jar;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path write(
        final String groupId,
        final String artifactId,
        final String version,
        final String extension,
        final String content
    ) {
        try {
            final var path = path(groupId, artifactId, version, extension);
            Files.createDirectories(path.getParent());
            Files.writeString(path, content);

            return path;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path path(
        final String groupId,
        final String artifactId,
        final String version,
        final String extension
    ) {
        return root.resolve(groupId.replace('.', '/'))
            .resolve(artifactId)
            .resolve(version)
            .resolve(artifactId + "-" + version + "." + extension);
    }
}
