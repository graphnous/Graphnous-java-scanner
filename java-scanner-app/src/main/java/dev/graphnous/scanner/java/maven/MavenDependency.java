package dev.graphnous.scanner.java.maven;

import java.util.HashSet;
import java.util.Set;

/**
 * A dependency declared in a pom.
 *
 * @param version    the resolved version, or {@code null} when it is unknown
 * @param scope      the scope, {@code compile} when the pom declares none
 * @param exclusions the dependencies it pulls in that are left out, as
 *                   {@code groupId:artifactId}, where either may be
 *                   {@code *} for any
 */
public record MavenDependency(
    String groupId,
    String artifactId,
    String version,
    String scope,
    Set<String> exclusions
) {

    public MavenDependency {
        exclusions = Set.copyOf(exclusions);
    }

    public MavenDependency(
        final String groupId,
        final String artifactId,
        final String version,
        final String scope
    ) {
        this(groupId, artifactId, version, scope, Set.of());
    }

    /**
     * Whether the dependency leaves out the artifact among the
     * dependencies it pulls in.
     */
    public boolean excludes(
        final String groupId,
        final String artifactId
    ) {
        return exclusions.contains(groupId + ":" + artifactId)
            || exclusions.contains("*:" + artifactId)
            || exclusions.contains(groupId + ":*")
            || exclusions.contains("*:*");
    }

    /**
     * The dependency with the exclusions of the dependency that pulls it
     * in added to its own, as they apply to all it pulls in in turn.
     */
    MavenDependency inheriting(final Set<String> inherited) {
        if (inherited.isEmpty()) {
            return this;
        }

        final var all = new HashSet<>(exclusions);
        all.addAll(inherited);

        return new MavenDependency(groupId, artifactId, version, scope, all);
    }

    MavenDependency withVersion(final String version) {
        return new MavenDependency(groupId, artifactId, version, scope, exclusions);
    }
}
