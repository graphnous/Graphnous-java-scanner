package dev.graphnous.scanner;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.graphnous.scanner.java.maven.MavenRemote;
import dev.graphnous.scanner.java.maven.MavenRepository;
import dev.graphnous.scanner.language.JavaLanguageLevel;
import dev.graphnous.scanner.model.ScanTarget;

import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

public class Main {

    /**
     * Where the image of the scanner looks for a Maven repository first,
     * so a volume mounted there is used without an argument.
     */
    static final Path IMAGE_MAVEN_REPOSITORY = Path.of("/maven-repository");

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

        final var result = new JavaScanner(
            options.javaVersion(),
            log,
            options.classpath(),
            mavenRepository(options, log)
        ).scan(options.repository(), target);

        final var modules = result.getModules();

        log.info(
            "Scanned " + modules.size() + (modules.size() == 1 ? " module, " : " modules, ")
            + JavaScanner.files(modules.stream().flatMap(module -> module.getFiles().stream()).toList()) + ", "
            + JavaFileDetector.classes(modules.stream().mapToInt(JavaScanner::classCount).sum())
        );

        new ScanResultWriter(new ObjectMapper()).write(result, options.output());
    }

    /**
     * The local Maven repository: the one given, else the one of the image
     * when it exists, else {@code ~/.m2/repository}. To download into, it
     * is created when it does not exist.
     */
    static MavenRepository mavenRepository(
        final Options options,
        final ScanLog log
    ) {
        final var location = options.mavenRepository() != null
            ? options.mavenRepository()
            : Files.isDirectory(IMAGE_MAVEN_REPOSITORY)
                ? IMAGE_MAVEN_REPOSITORY
                : Path.of(System.getProperty("user.home", ""), ".m2", "repository");

        if (!options.downloadDependencies()) {
            return MavenRepository.at(location);
        }

        log.detail("Downloading missing libraries from " + options.mavenRemote() + " into " + location);

        return MavenRepository.create(location).downloadingFrom(new MavenRemote(options.mavenRemote()));
    }

    static Options parseArguments(final String[] args) {
        Path repository = null;
        String target = null;
        Path output = null;
        String javaVersion = null;
        List<Path> classpath = List.of();
        Path mavenRepository = null;
        var downloadDependencies = false;
        var mavenRemote = MavenRemote.MAVEN_CENTRAL;
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
                case "--classpath" -> {
                    // Separated as the java command separates them: by :
                    // on Linux and macOS, by ; on Windows
                    classpath = Arrays.stream(requireValue(args, ++i, "--classpath").split(File.pathSeparator))
                        .filter(entry -> !entry.isBlank())
                        .map(Path::of)
                        .toList();
                }
                case "--maven-repository" -> {
                    mavenRepository = Path.of(
                        requireValue(args, ++i, "--maven-repository")
                    );
                }
                case "--download-dependencies" -> downloadDependencies = true;
                case "--maven-remote" -> {
                    mavenRemote = URI.create(
                        requireValue(args, ++i, "--maven-remote")
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
            classpath,
            mavenRepository,
            downloadDependencies,
            mavenRemote,
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

    record Options(
        Path repository,
        String target,
        Path output,
        String javaVersion,
        List<Path> classpath,
        Path mavenRepository,
        boolean downloadDependencies,
        URI mavenRemote,
        boolean verbose
    ) {}
}