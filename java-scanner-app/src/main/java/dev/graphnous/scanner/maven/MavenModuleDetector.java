package dev.graphnous.scanner.maven;

import dev.graphnous.scanner.ModuleDetector;
import dev.graphnous.scanner.ScanLog;
import dev.graphnous.scanner.java.maven.MavenDependency;
import dev.graphnous.scanner.java.maven.MavenPom;
import dev.graphnous.scanner.java.maven.MavenPomFinder;
import dev.graphnous.core.model.Dependency;
import dev.graphnous.core.model.Module;
import dev.graphnous.core.model.ScanTarget;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class MavenModuleDetector implements ModuleDetector {

    private final ScanLog log;

    public MavenModuleDetector() {
        this(ScanLog.console(false));
    }

    public MavenModuleDetector(final ScanLog log) {
        this.log = log;
    }

    @Override
    public List<Module> detectModules(
        final Path path,
        final ScanTarget target
    ) {
        final var targetPath = path.resolve(target.getPath());

        return MavenPomFinder.find(targetPath)
            .stream()
            .map(pom -> {
                final var module = new Module();

                final var modulePath = targetPath
                    .relativize(pom.getParent())
                    .toString();

                module.setPath(
                    modulePath.isBlank() ? "." : modulePath
                );

                module.setFiles(new ArrayList<>());

                describe(module, pom);

                return module;
            })
            .toList();
    }

    /**
     * Sets the name of the module, its artifact id, and its dependencies.
     * A pom that cannot be read still makes a module, named after its
     * directory.
     */
    private void describe(
        final Module module,
        final Path pom
    ) {
        final var directory = pom.toAbsolutePath().normalize().getParent().getFileName();
        final var fallbackName = directory == null ? module.getPath() : directory.toString();

        try {
            final var mavenPom = MavenPom.read(pom);

            module.setName(mavenPom.artifactId().orElse(fallbackName));

            module.setDependencies(
                mavenPom.dependencies()
                    .stream()
                    .map(MavenModuleDetector::dependency)
                    .toList()
            );
        } catch (IllegalStateException e) {
            log.warn(
                "Could not read " + Path.of(module.getPath()).resolve("pom.xml").normalize()
                + ": " + rootCause(e).getMessage()
            );

            module.setName(fallbackName);
        }
    }

    private static Throwable rootCause(final Throwable throwable) {
        var cause = throwable;

        while (cause.getCause() != null) {
            cause = cause.getCause();
        }

        return cause;
    }

    private static Dependency dependency(final MavenDependency mavenDependency) {
        final var dependency = new Dependency();

        dependency.setName(mavenDependency.groupId() + ":" + mavenDependency.artifactId());
        dependency.setVersion(mavenDependency.version());
        dependency.setScope(mavenDependency.scope());

        return dependency;
    }

    @Override
    public ScanTarget.BuildSystem forSystem() {
        return ScanTarget.BuildSystem.MAVEN;
    }
}
