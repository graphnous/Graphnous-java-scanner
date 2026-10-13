package dev.graphnous.scanner.java.maven;

import java.nio.file.Path;
import java.util.ArrayDeque;
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
 * {@code dependencyManagement} of the module manages it.
 * <p>
 * This is not Maven: a version the poms do not tell, e.g. because a
 * parent or BOM is missing, is taken to be the newest version in the
 * repository, and exclusions are not applied. The result is meant for
 * resolving the types the sources use, for which a jar too many does no
 * harm.
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
        final var queue = new ArrayDeque<MavenDependency>();
        final var jars = new LinkedHashSet<Path>();

        modules.forEach(module -> module.dependencies()
            .stream()
            // Neither has a jar in the repository
            .filter(dependency -> !dependency.scope().equals("system") && !dependency.scope().equals("import"))
            .forEach(queue::add));

        final var depth = queue.size();
        var polled = 0;

        // Breadth first, so the version nearest to the modules wins
        while (!queue.isEmpty() && visited.size() < MAX_ARTIFACTS) {
            final var dependency = queue.poll();
            final var direct = polled++ < depth;

            if (!visited.add(dependency.groupId() + ":" + dependency.artifactId())) {
                continue;
            }

            // The modules manage the versions of the dependencies they pull
            // in; their own dependencies already have the managed version
            final var managed = direct
                ? Optional.<String>empty()
                : modules.stream()
                    .map(module -> module.managedVersion(dependency.groupId(), dependency.artifactId()))
                    .flatMap(Optional::stream)
                    .findFirst();

            final var version = managed.or(() -> Optional.ofNullable(dependency.version()))
                .filter(candidate -> repository.pom(dependency.groupId(), dependency.artifactId(), candidate).isPresent())
                .or(() -> repository.newestVersion(dependency.groupId(), dependency.artifactId()));

            version.ifPresent(found -> {
                repository.jar(dependency.groupId(), dependency.artifactId(), found).ifPresent(jars::add);

                repository.pom(dependency.groupId(), dependency.artifactId(), found)
                    .flatMap(repository::read)
                    .ifPresent(pom -> queue.addAll(pom.transitiveDependencies()));
            });
        }

        return List.copyOf(jars);
    }
}
