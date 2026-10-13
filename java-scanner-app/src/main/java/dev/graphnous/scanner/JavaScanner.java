package dev.graphnous.scanner;

import dev.graphnous.scanner.java.maven.MavenClasspath;
import dev.graphnous.scanner.java.maven.MavenPom;
import dev.graphnous.scanner.java.maven.MavenRepository;
import dev.graphnous.scanner.java.maven.MavenWrapper;
import dev.graphnous.scanner.language.JavaLanguageLevel;
import dev.graphnous.scanner.maven.MavenModuleDetector;
import dev.graphnous.scanner.model.File;
import dev.graphnous.scanner.model.Module;
import dev.graphnous.scanner.model.ScanResultSchema;
import dev.graphnous.scanner.model.ScanTarget;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class JavaScanner implements Scanner {

    private Map<ScanTarget.BuildSystem, ModuleDetector> moduleDetectors;

    private final JavaFileDetector fileDetector;

    private final ScanLog log;

    private final List<Path> classpath;

    private final MavenRepository mavenRepository;

    public JavaScanner() {
        this(null);
    }

    public JavaScanner(final String javaVersion) {
        this(javaVersion, ScanLog.console(false));
    }

    /**
     * @param javaVersion the Java version of the scanned project, which
     *                    decides the syntax its sources are parsed with;
     *                    {@code null} for the newest supported version
     */
    public JavaScanner(
        final String javaVersion,
        final ScanLog log
    ) {
        this(javaVersion, log, List.of(), MavenRepository.NONE);
    }

    /**
     * @param classpath       the jars of the libraries the project uses,
     *                        e.g. as {@code mvn dependency:build-classpath}
     *                        lists them, to resolve calls into them
     * @param mavenRepository the local Maven repository the build of the
     *                        project downloaded its libraries to, to find
     *                        the jars of the dependencies of its poms
     */
    public JavaScanner(
        final String javaVersion,
        final ScanLog log,
        final List<Path> classpath,
        final MavenRepository mavenRepository
    ) {
        this.log = log;
        this.classpath = List.copyOf(classpath);
        this.mavenRepository = mavenRepository;
        this.moduleDetectors = Map.of(
            ScanTarget.BuildSystem.MAVEN,
            new MavenModuleDetector(log)
        );

        this.fileDetector = new JavaFileDetector(
            new JavaClassParser(JavaLanguageLevel.of(javaVersion)),
            log
        );
    }

    @Override
    public ScanResultSchema scan(Path path, ScanTarget target) {
        final var schema = new ScanResultSchema();
        schema.setTarget(target);
        schema.setVersion("2");
        schema.setFormat("graphnous-scan-result");

        schema.setModules(new ArrayList<>());

        if (target.getBuildSystem() == ScanTarget.BuildSystem.MAVEN
            && target.getBuildSystemVersion() == null) {
            MavenWrapper.version(path, path.resolve(target.getPath()))
                .ifPresent(target::setBuildSystemVersion);
        }

        final var moduleDetector = this.moduleDetectors.get(target.getBuildSystem());
        final var modules = moduleDetector.detectModules(path, target);

        // A first pass over all modules, so types used across modules and
        // files resolve to the types the target declares
        final var index = fileDetector.index(path, target, modules, libraries(path, target, modules));

        log.detail("Indexed " + JavaFileDetector.classes(index.size()));

        modules
            .forEach(module -> {
                log.detail("Module " + module.getPath());

                fileDetector.detect(path, target, module, index);

                log.info(
                    "Module " + module.getPath() + ": "
                    + files(module.getFiles()) + ", "
                    + JavaFileDetector.classes(classCount(module))
                );

                schema.getModules().add(module);
            });

        return schema;
    }

    /**
     * The jars of the classpath, followed by the jars of the dependencies
     * of the Maven modules found in the local Maven repository, and the
     * sources a build of the modules generated.
     */
    private List<Path> libraries(
        final Path path,
        final ScanTarget target,
        final List<Module> modules
    ) {
        final var libraries = new LinkedHashSet<Path>();

        classpath.forEach(entry -> {
            if (Files.isRegularFile(entry) && entry.getFileName().toString().endsWith(".jar")) {
                libraries.add(entry);
            } else {
                log.warn("Skipping classpath entry " + entry + ": not a jar");
            }
        });

        if (target.getBuildSystem() == ScanTarget.BuildSystem.MAVEN && mavenRepository.exists()) {
            final var targetPath = path.resolve(target.getPath());

            final var poms = modules.stream()
                .map(module -> targetPath.resolve(module.getPath()).resolve("pom.xml").normalize())
                .map(pom -> {
                    try {
                        return Optional.of(MavenPom.read(pom, mavenRepository));
                    } catch (IllegalStateException e) {
                        // Reported when the module was detected
                        return Optional.<MavenPom>empty();
                    }
                })
                .flatMap(Optional::stream)
                .toList();

            final var scanned = poms.stream()
                .map(pom -> pom.groupId().orElse("") + ":" + pom.artifactId().orElse(""))
                .collect(Collectors.toSet());

            final var found = MavenClasspath.of(poms, scanned, mavenRepository);

            libraries.addAll(found);
            log.detail("Found " + found.size() + (found.size() == 1 ? " library" : " libraries") + " in the local Maven repository");
        }

        if (!libraries.isEmpty()) {
            log.info("Resolving calls into " + libraries.size() + (libraries.size() == 1 ? " library" : " libraries"));
        }

        final var generated = generatedSources(path, target, modules);

        if (!generated.isEmpty()) {
            libraries.addAll(generated);
            log.detail("Found " + generated.size() + " generated source " + (generated.size() == 1 ? "directory" : "directories"));
        }

        return List.copyOf(libraries);
    }

    /**
     * The directories a Maven build generates sources in, one per plugin,
     * e.g. {@code target/generated-sources/openapi}. Their types resolve
     * the types the sources use, but they are not scanned, as they are not
     * written by hand.
     */
    private static List<Path> generatedSources(
        final Path path,
        final ScanTarget target,
        final List<Module> modules
    ) {
        if (target.getBuildSystem() != ScanTarget.BuildSystem.MAVEN) {
            return List.of();
        }

        final var targetPath = path.resolve(target.getPath());

        return modules.stream()
            .map(module -> targetPath.resolve(module.getPath()).resolve("target").normalize())
            .flatMap(output -> Stream.of(output.resolve("generated-sources"), output.resolve("generated-test-sources")))
            .filter(Files::isDirectory)
            .flatMap(directory -> {
                try (var plugins = Files.list(directory)) {
                    return plugins.filter(Files::isDirectory).sorted().toList().stream();
                } catch (IOException e) {
                    return Stream.empty();
                }
            })
            .toList();
    }

    static int classCount(final Module module) {
        return module.getFiles().stream()
            .mapToInt(file -> file.getClasses().size())
            .sum();
    }

    static String files(final int count) {
        return count + (count == 1 ? " file" : " files");
    }

    /**
     * The number of files, with how many of them are test code when there
     * are any, e.g. "21 files (5 test)".
     */
    static String files(final List<File> files) {
        final var tests = files.stream()
            .filter(file -> file.getSourceSet() == File.SourceSet.TEST)
            .count();

        return files(files.size()) + (tests == 0 ? "" : " (" + tests + " test)");
    }
}
