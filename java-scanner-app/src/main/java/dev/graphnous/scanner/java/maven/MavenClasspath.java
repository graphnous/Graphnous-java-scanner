package dev.graphnous.scanner.java.maven;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The jars of the libraries the modules of a project use, found in the
 * local Maven repository Maven downloaded them to: the dependencies of
 * the modules in every scope, and the dependencies those pull in, as
 * Maven resolves them: the nearest version wins, unless the
 * {@code dependencyManagement} of the module manages it, and what a
 * dependency excludes is left out of all it pulls in.
 * <p>
 * This is not Maven: a version the poms do not tell, e.g. because a
 * parent or BOM is missing, is taken to be the newest version in the
 * repository. The result is meant for resolving the types the sources
 * use, for which a jar too many does no harm. With a remote repository,
 * the poms and jars are downloaded into the repository as they are needed.
 */
public final class MavenClasspath {

    /**
     * Bounds the artifacts visited, against a repository with a cycle or
     * an unexpectedly large dependency tree.
     */
    private static final int MAX_ARTIFACTS = 5_000;

    private MavenClasspath() {
    }

    /**
     * @param modules the poms of the modules
     * @param skipped {@code groupId:artifactId} of the modules themselves,
     *                whose sources are scanned rather than their jars
     */
    public static List<Path> of(
        final Collection<MavenPom> modules,
        final Set<String> skipped,
        final MavenRepository repository
    ) {
        if (!repository.exists()) {
            return List.of();
        }

        final var visited = new HashSet<>(skipped);
        final var artifacts = new ArrayList<List<String>>();

        var level = modules.stream()
            .flatMap(module -> module.dependencies().stream())
            // Neither has a jar in the repository
            .filter(dependency -> !dependency.scope().equals("system") && !dependency.scope().equals("import"))
            .toList();
        var direct = true;

        // Level by level, so the version nearest to the modules wins, and
        // the poms of a level can be downloaded at once
        while (!level.isEmpty() && visited.size() < MAX_ARTIFACTS) {
            final var candidates = new ArrayList<MavenDependency>();

            for (final var dependency : level) {
                if (visited.add(dependency.groupId() + ":" + dependency.artifactId())) {
                    // The modules manage the versions of the dependencies
                    // they pull in; their own already have the managed one
                    candidates.add(dependency.withVersion(
                        (direct ? Optional.<String>empty() : managedVersion(modules, dependency))
                            .orElse(dependency.version())
                    ));
                }
            }

            repository.prefetch(
                candidates.stream()
                    .filter(dependency -> dependency.version() != null)
                    .map(MavenClasspath::coordinates)
                    .toList(),
                "pom"
            );

            final var next = new ArrayList<MavenDependency>();

            for (final var dependency : candidates) {
                Optional.ofNullable(dependency.version())
                    .filter(version -> repository.pom(dependency.groupId(), dependency.artifactId(), version).isPresent())
                    .or(() -> repository.newestVersion(dependency.groupId(), dependency.artifactId()))
                    .ifPresent(version -> {
                        artifacts.add(List.of(dependency.groupId(), dependency.artifactId(), version));

                        // What the dependency, or one that pulls it in,
                        // excludes is left out of all it pulls in
                        repository.pom(dependency.groupId(), dependency.artifactId(), version)
                            .flatMap(repository::read)
                            .ifPresent(pom -> pom.transitiveDependencies()
                                .stream()
                                .filter(pulled -> !dependency.excludes(pulled.groupId(), pulled.artifactId()))
                                .map(pulled -> pulled.inheriting(dependency.exclusions()))
                                .forEach(next::add));
                    });
            }

            level = next;
            direct = false;
        }

        repository.prefetch(artifacts, "jar");

        final var jars = new LinkedHashSet<Path>();

        artifacts.forEach(artifact -> repository.jar(artifact.get(0), artifact.get(1), artifact.get(2)).ifPresent(jars::add));

        return List.copyOf(jars);
    }

    private static Optional<String> managedVersion(
        final Collection<MavenPom> modules,
        final MavenDependency dependency
    ) {
        return modules.stream()
            .map(module -> module.managedVersion(dependency.groupId(), dependency.artifactId()))
            .flatMap(Optional::stream)
            .findFirst();
    }

    private static List<String> coordinates(final MavenDependency dependency) {
        return List.of(dependency.groupId(), dependency.artifactId(), dependency.version());
    }
}
