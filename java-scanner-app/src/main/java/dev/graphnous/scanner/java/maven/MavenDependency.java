package dev.graphnous.scanner.java.maven;

/**
 * A dependency declared in a pom.
 *
 * @param version the resolved version, or {@code null} when it is unknown
 * @param scope   the scope, {@code compile} when the pom declares none
 */
public record MavenDependency(
    String groupId,
    String artifactId,
    String version,
    String scope
) {
}
