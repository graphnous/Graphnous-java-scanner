package dev.graphnous.scanner;

import com.github.javaparser.Problem;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Thrown when a Java source file contains syntax errors.
 */
public class JavaParseException extends RuntimeException {

    private final Path sourcePath;

    private final List<Problem> problems;

    public JavaParseException(
        final Path sourcePath,
        final List<Problem> problems
    ) {
        super(
            "Failed to parse Java file: " + sourcePath
            + problems.stream()
                .map(Problem::getVerboseMessage)
                .collect(Collectors.joining("\n  ", "\n  ", ""))
        );

        this.sourcePath = sourcePath;
        this.problems = List.copyOf(problems);
    }

    public Path getSourcePath() {
        return sourcePath;
    }

    public List<Problem> getProblems() {
        return problems;
    }
}
