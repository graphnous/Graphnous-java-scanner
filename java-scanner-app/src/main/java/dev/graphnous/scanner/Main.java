package dev.graphnous.scanner;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.graphnous.scanner.language.JavaLanguageLevel;
import dev.graphnous.scanner.model.ScanTarget;

import java.nio.file.Path;

public class Main {

    static void main(final String[] args) {
        final var options = parseArguments(args);
        final var log = ScanLog.console(options.verbose());

        final var target = new ScanTarget();
        target.setPath(options.target());
        target.setLanguage(ScanTarget.Language.JAVA);
        target.setBuildSystem(ScanTarget.BuildSystem.MAVEN);

        // The result requires a language version; without one the sources
        // are parsed as the newest supported version, so report that
        target.setLanguageVersion(
            options.javaVersion() == null
                ? JavaLanguageLevel.version(JavaLanguageLevel.of(null))
                : options.javaVersion()
        );

        log.info(
            "GraphNous Java Scanner: " + target.getPath() + " in " + options.repository()
            + " (Java " + (options.javaVersion() == null ? "version unknown, parsing as " + target.getLanguageVersion() : options.javaVersion()) + ")"
        );
        log.detail("Output: " + options.output());

        final var result = new JavaScanner(options.javaVersion(), log)
            .scan(options.repository(), target);

        final var modules = result.getModules();

        log.info(
            "Scanned " + modules.size() + (modules.size() == 1 ? " module, " : " modules, ")
            + JavaScanner.files(modules.stream().flatMap(module -> module.getFiles().stream()).toList()) + ", "
            + JavaFileDetector.classes(modules.stream().mapToInt(JavaScanner::classCount).sum())
        );

        new ScanResultWriter(new ObjectMapper()).write(result, options.output());
    }

    private static Options parseArguments(final String[] args) {
        Path repository = null;
        String target = null;
        Path output = null;
        String javaVersion = null;
        var verbose = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--path" -> {
                    repository = Path.of(
                        requireValue(args, ++i, "--path")
                    );
                }
                case "--target" -> {
                    target = requireValue(
                        args,
                        ++i,
                        "--target"
                    );
                }
                case "--output" -> {
                    output = Path.of(
                        requireValue(args, ++i, "--output")
                    );
                }
                case "--java-version" -> {
                    javaVersion = requireValue(
                        args,
                        ++i,
                        "--java-version"
                    );
                }
                case "--verbose" -> verbose = true;
                default -> throw new IllegalArgumentException(
                    "Unknown argument: " + args[i]
                );
            }
        }

        if (repository == null) {
            throw new IllegalArgumentException(
                "Missing required argument: --path"
            );
        }

        if (target == null) {
            target = ".";
        }

        // An empty version is an unknown one, so a caller can always pass it
        if (javaVersion != null && javaVersion.isBlank()) {
            javaVersion = null;
        }

        if (output == null) {
            throw new IllegalArgumentException(
                "Missing required argument: --output"
            );
        }

        return new Options(
            repository,
            target,
            output,
            javaVersion,
            verbose
        );
    }

    private static String requireValue(
        final String[] args,
        final int index,
        final String argument
    ) {
        if (index >= args.length) {
            throw new IllegalArgumentException(
                "Missing value for " + argument
            );
        }

        return args[index];
    }

    private record Options(
        Path repository,
        String target,
        Path output,
        String javaVersion,
        boolean verbose
    ) {}
}