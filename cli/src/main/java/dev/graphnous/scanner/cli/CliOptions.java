package dev.graphnous.scanner.cli;

import dev.graphnous.scanner.docker.DockerWorkspace;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public record CliOptions(
    Path repository,
    Path output,
    Sandbox sandbox,
    DockerWorkspace dockerWorkspace,
    Path javaScanner,
    Path typescriptScanner,
    boolean verbose
) {

    public enum Sandbox {
        PROCESS,
        DOCKER
    }

    static final String USAGE = """
        Usage: graphnous-scanner <repository> [options]

        Scans the repository and writes one result file per detected project.

        Options:
          --output <dir>               Directory for the results (default: scan-results)
          --sandbox <process|docker>   Where scanners run (default: process)
          --docker-volume <name>:<path>
                                       With --sandbox docker: the repository is checked out in
                                       this Docker volume, mounted here at <path>; scanner
                                       containers get the volume at the same path
          --java-scanner <jar>         Java scanner (default: scanners/java-scanner.jar next to the CLI)
          --typescript-scanner <js>    TypeScript scanner (default: scanners/scanner.js next to the CLI)
          --verbose                    Show every scanned file instead of one line per module
          --help                       Show this help
        """;

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
     * @param scannersDirectory where the scanners are looked up by default
     * @throws UsageException when the arguments are invalid
     */
    static CliOptions parse(
        final String[] args,
        final Path scannersDirectory
    ) {
        Path repository = null;
        var output = Path.of(DEFAULT_OUTPUT);
        var sandbox = Sandbox.PROCESS;
        DockerWorkspace dockerVolume = null;
        var javaScanner = scannersDirectory.resolve("java-scanner.jar");
        var typescriptScanner = scannersDirectory.resolve("scanner.js");
        var verbose = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--output" -> output = Path.of(value(args, ++i, "--output"));
                case "--sandbox" -> sandbox = sandbox(value(args, ++i, "--sandbox"));
                case "--docker-volume" -> dockerVolume = dockerVolume(value(args, ++i, "--docker-volume"));
                case "--java-scanner" -> javaScanner = Path.of(value(args, ++i, "--java-scanner"));
                case "--typescript-scanner" -> typescriptScanner = Path.of(value(args, ++i, "--typescript-scanner"));
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

        if (dockerVolume != null && sandbox != Sandbox.DOCKER) {
            throw new UsageException("--docker-volume requires --sandbox docker");
        }

        requireScanner(javaScanner, "--java-scanner");
        requireScanner(typescriptScanner, "--typescript-scanner");

        return new CliOptions(
            repository.toAbsolutePath().normalize(),
            output.toAbsolutePath().normalize(),
            sandbox,
            dockerVolume == null ? DockerWorkspace.hostDirectory() : dockerVolume,
            javaScanner.toAbsolutePath().normalize(),
            typescriptScanner.toAbsolutePath().normalize(),
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

    private static Sandbox sandbox(final String value) {
        try {
            return Sandbox.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new UsageException("Unknown sandbox: " + value + " (expected process or docker)");
        }
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

    private static void requireScanner(
        final Path scanner,
        final String option
    ) {
        if (!Files.isRegularFile(scanner)) {
            throw new UsageException(
                "Scanner not found: " + scanner
                + " (build it with ./mvnw package, or pass " + option + ")"
            );
        }
    }

    static class UsageException extends RuntimeException {

        UsageException(final String message) {
            super(message);
        }
    }
}
