package dev.graphnous.scanner.java.maven;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/**
 * Finds the {@code pom.xml} files below a directory, skipping the
 * {@code target} build output of Maven projects, which can contain
 * copies of poms (generated projects, packaged artifacts, test fixtures).
 */
public final class MavenPomFinder {

    private static final String POM = "pom.xml";

    private static final String OUTPUT_DIRECTORY = "target";

    private MavenPomFinder() {
    }

    public static List<Path> find(final Path root) {
        final var poms = new ArrayList<Path>();

        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(
                    final Path directory,
                    final BasicFileAttributes attributes
                ) {
                    return isOutput(root, directory)
                        ? FileVisitResult.SKIP_SUBTREE
                        : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(
                    final Path file,
                    final BasicFileAttributes attributes
                ) {
                    if (attributes.isRegularFile()
                        && POM.equals(file.getFileName().toString())) {
                        poms.add(file);
                    }

                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(
                "Failed to find pom.xml files in " + root,
                e
            );
        }

        return poms;
    }

    private static boolean isOutput(
        final Path root,
        final Path directory
    ) {
        return !directory.equals(root)
            && OUTPUT_DIRECTORY.equals(directory.getFileName().toString())
            && Files.isRegularFile(directory.resolveSibling(POM));
    }
}
