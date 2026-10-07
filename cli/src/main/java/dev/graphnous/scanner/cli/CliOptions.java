package dev.graphnous.scanner.cli;

import dev.graphnous.scanner.docker.DockerWorkspace;

import java.nio.file.Files;
import java.nio.file.Path;

public record CliOptions(
    Path repository,
    Path output,
    DockerWorkspace dockerWorkspace,
    String javaScannerImage,
    String typescriptScannerImage,
    boolean verbose
) {

    static final String USAGE = """
        Usage: graphnous-scanner <repository> [options]

        Scans the repository and writes one result file per detected project.
        Each scanner runs in a Docker container of its image.

        Options:
          --output <dir>               Directory for the results (default: scan-results)
          --docker-volume <name>:<path>
                                       The repository is checked out in this Docker volume,
                                       mounted here at <path>; scanner containers get the
                                       volume at the same path
          --java-scanner-image <image>
                                       Java scanner image (default: %s)
          --typescript-scanner-image <image>
                                       TypeScript scanner image (default: %s)
          --verbose                    Show every scanned file instead of one line per module
          --help                       Show this help
        """.formatted(Scanners.JAVA_IMAGE, Scanners.TYPESCRIPT_IMAGE);

    static final String DEFAULT_OUTPUT = "scan-results";

    static boolean isHelp(final String[] args) {
        for (final var arg : args) {
            if (arg.equals("--help") || arg.equals("-h")) {
                return true;
            }
        }

        return false;
    }

    /**
     * @throws UsageException when the arguments are invalid
     */
    static CliOptions parse(final String[] args) {
        Path repository = null;
        var output = Path.of(DEFAULT_OUTPUT);
        DockerWorkspace dockerVolume = null;
        var javaScannerImage = Scanners.JAVA_IMAGE;
        var typescriptScannerImage = Scanners.TYPESCRIPT_IMAGE;
        var verbose = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--output" -> output = Path.of(value(args, ++i, "--output"));
                case "--docker-volume" -> dockerVolume = dockerVolume(value(args, ++i, "--docker-volume"));
                case "--java-scanner-image" -> javaScannerImage = value(args, ++i, "--java-scanner-image");
                case "--typescript-scanner-image" -> typescriptScannerImage = value(args, ++i, "--typescript-scanner-image");
                case "--verbose" -> verbose = true;
                default -> {
                    if (args[i].startsWith("-")) {
                        throw new UsageException("Unknown option: " + args[i]);
                    }

                    if (repository != null) {
                        throw new UsageException("Only one repository can be scanned, got " + repository + " and " + args[i]);
                    }

                    repository = Path.of(args[i]);
                }
            }
        }

        if (repository == null) {
            throw new UsageException("Missing repository to scan");
        }

        if (!Files.isDirectory(repository)) {
            throw new UsageException("Repository is not a directory: " + repository);
        }

        return new CliOptions(
            repository.toAbsolutePath().normalize(),
            output.toAbsolutePath().normalize(),
            dockerVolume == null ? DockerWorkspace.hostDirectory() : dockerVolume,
            javaScannerImage,
            typescriptScannerImage,
            verbose
        );
    }

    private static String value(
        final String[] args,
        final int index,
        final String option
    ) {
        if (index >= args.length) {
            throw new UsageException("Missing value for " + option);
        }

        return args[index];
    }

    private static DockerWorkspace dockerVolume(final String value) {
        final var separator = value.indexOf(':');

        if (separator <= 0 || separator == value.length() - 1) {
            throw new UsageException("Expected --docker-volume <name>:<path>, got " + value);
        }

        try {
            return DockerWorkspace.volume(
                value.substring(0, separator),
                Path.of(value.substring(separator + 1))
            );
        } catch (IllegalArgumentException e) {
            throw new UsageException(e.getMessage());
        }
    }

    static class UsageException extends RuntimeException {

        UsageException(final String message) {
            super(message);
        }
    }
}
