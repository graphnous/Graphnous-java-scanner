package dev.graphnous.scanner;

import dev.graphnous.scanner.java.maven.MavenWrapper;
import dev.graphnous.scanner.language.JavaLanguageLevel;
import dev.graphnous.scanner.maven.MavenModuleDetector;
import dev.graphnous.core.model.File;
import dev.graphnous.core.model.Module;
import dev.graphnous.core.model.ScanResult;
import dev.graphnous.core.model.ScanTarget;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class JavaScanner implements Scanner {

    private Map<ScanTarget.BuildSystem, ModuleDetector> moduleDetectors;

    private final JavaFileDetector fileDetector;

    private final ScanLog log;

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
        this.log = log;
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
    public ScanResult scan(Path path, ScanTarget target) {
        final var schema = new ScanResult();
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
        final var index = fileDetector.index(path, target, modules);

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
