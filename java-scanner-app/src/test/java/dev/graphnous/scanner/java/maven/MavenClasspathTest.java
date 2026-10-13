package dev.graphnous.scanner.java.maven;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MavenClasspathTest {

    @TempDir
    Path directory;

    @Test
    void findsTheDependenciesAndTheDependenciesTheyPullIn() throws IOException {
        final var local = new LocalRepository(directory.resolve("m2"));
        local.pom("org.example", "web", "1.0", dependencies(
            dependency("org.example", "http", "2.0", null),
            dependency("org.example", "test-kit", "1.0", "test"),
            dependency("org.example", "servlet", "1.0", "provided"),
            "<dependency><groupId>org.example</groupId><artifactId>extra</artifactId><version>1.0</version><optional>true</optional></dependency>"
        ));
        local.pom("org.example", "http", "2.0", "");
        List.of("web", "http", "test-kit", "servlet", "extra").forEach(artifact -> jar(local, artifact, artifact.equals("http") ? "2.0" : "1.0"));

        final var module = module(dependencies(dependency("org.example", "web", "1.0", null)));

        // Test, provided and optional dependencies of a dependency are not
        // pulled in
        assertThat(names(MavenClasspath.of(List.of(module), Set.of(), local.repository())))
            .containsExactly("web-1.0.jar", "http-2.0.jar");
    }

    @Test
    void takesTheVersionNearestToTheModule() throws IOException {
        final var local = new LocalRepository(directory.resolve("m2"));
        local.pom("org.example", "web", "1.0", dependencies(dependency("org.example", "json", "1.0", null)));
        local.pom("org.example", "json", "1.0", "");
        local.pom("org.example", "json", "2.0", "");
        jar(local, "web", "1.0");
        jar(local, "json", "1.0");
        jar(local, "json", "2.0");

        final var module = module(dependencies(
            dependency("org.example", "web", "1.0", null),
            dependency("org.example", "json", "2.0", null)
        ));

        assertThat(names(MavenClasspath.of(List.of(module), Set.of(), local.repository())))
            .containsExactly("web-1.0.jar", "json-2.0.jar");
    }

    @Test
    void appliesTheVersionsTheModuleManagesToWhatItPullsIn() throws IOException {
        final var local = new LocalRepository(directory.resolve("m2"));
        local.pom("org.example", "web", "1.0", dependencies(dependency("org.example", "json", "1.0", null)));
        local.pom("org.example", "json", "1.0", "");
        local.pom("org.example", "json", "3.0", "");
        jar(local, "web", "1.0");
        jar(local, "json", "1.0");
        jar(local, "json", "3.0");

        final var module = module("""
            <dependencyManagement>
                <dependencies>
                    %s
                </dependencies>
            </dependencyManagement>
            %s
            """.formatted(
                dependency("org.example", "json", "3.0", null),
                dependencies(dependency("org.example", "web", "1.0", null))
            ));

        assertThat(names(MavenClasspath.of(List.of(module), Set.of(), local.repository())))
            .containsExactly("web-1.0.jar", "json-3.0.jar");
    }

    @Test
    void takesVersionsFromImportedBomsAndParentsInTheRepository() throws IOException {
        final var local = new LocalRepository(directory.resolve("m2"));
        local.pom("org.example", "platform", "5.0", """
            <packaging>pom</packaging>
            <dependencyManagement>
                <dependencies>
                    %s
                </dependencies>
            </dependencyManagement>
            """.formatted(dependency("org.example", "web", "1.0", null)));
        local.pom("org.example", "starter-parent", "5.0", """
            <packaging>pom</packaging>
            <properties>
                <platform.version>5.0</platform.version>
            </properties>
            <dependencyManagement>
                <dependencies>
                    <dependency>
                        <groupId>org.example</groupId>
                        <artifactId>platform</artifactId>
                        <version>${platform.version}</version>
                        <type>pom</type>
                        <scope>import</scope>
                    </dependency>
                </dependencies>
            </dependencyManagement>
            """);
        local.pom("org.example", "web", "1.0", "");
        jar(local, "web", "1.0");

        // The parent is not in the project, only in the repository
        final var module = module("""
            <parent>
                <groupId>org.example</groupId>
                <artifactId>starter-parent</artifactId>
                <version>5.0</version>
            </parent>
            %s
            """.formatted(dependencies(dependency("org.example", "web", null, null))), local.repository());

        assertThat(module.dependencies()).containsExactly(new MavenDependency("org.example", "web", "1.0", "compile"));
        assertThat(names(MavenClasspath.of(List.of(module), Set.of(), local.repository())))
            .containsExactly("web-1.0.jar");
    }

    @Test
    void leavesOutWhatADependencyExcludesFromAllItPullsIn() throws IOException {
        final var local = new LocalRepository(directory.resolve("m2"));
        local.pom("org.example", "web", "1.0", dependencies(
            dependency("org.example", "http", "1.0", null),
            dependency("org.example", "json", "1.0", null)
        ));
        local.pom("org.example", "http", "1.0", dependencies(
            dependency("org.example", "logging", "1.0", null),
            dependency("org.other", "codec", "1.0", null)
        ));
        local.pom("org.example", "json", "1.0", "");
        local.pom("org.example", "logging", "1.0", "");
        local.pom("org.other", "codec", "1.0", "");
        List.of("web", "http", "json", "logging").forEach(artifact -> jar(local, artifact, "1.0"));
        local.jar("org.other", "codec", "1.0", Map.of("org/other/Codec.java", "package org.other; public class Codec { }"));

        // The exclusions of web apply to what http pulls in as well
        final var module = module(dependencies("""
            <dependency>
                <groupId>org.example</groupId>
                <artifactId>web</artifactId>
                <version>1.0</version>
                <exclusions>
                    <exclusion><groupId>org.example</groupId><artifactId>json</artifactId></exclusion>
                    <exclusion><groupId>org.example</groupId><artifactId>logging</artifactId></exclusion>
                    <exclusion><groupId>org.other</groupId><artifactId>*</artifactId></exclusion>
                </exclusions>
            </dependency>
            """));

        assertThat(names(MavenClasspath.of(List.of(module), Set.of(), local.repository())))
            .containsExactly("web-1.0.jar", "http-1.0.jar");
    }

    @Test
    void pullsInWhatOneDependencyExcludesThroughAnother() throws IOException {
        final var local = new LocalRepository(directory.resolve("m2"));
        local.pom("org.example", "web", "1.0", dependencies(dependency("org.example", "json", "1.0", null)));
        local.pom("org.example", "rest", "1.0", dependencies(dependency("org.example", "json", "1.0", null)));
        local.pom("org.example", "json", "1.0", "");
        List.of("web", "rest", "json").forEach(artifact -> jar(local, artifact, "1.0"));

        final var module = module(dependencies(
            """
            <dependency>
                <groupId>org.example</groupId><artifactId>web</artifactId><version>1.0</version>
                <exclusions><exclusion><groupId>*</groupId><artifactId>*</artifactId></exclusion></exclusions>
            </dependency>
            """,
            dependency("org.example", "rest", "1.0", null)
        ));

        assertThat(names(MavenClasspath.of(List.of(module), Set.of(), local.repository())))
            .containsExactly("web-1.0.jar", "rest-1.0.jar", "json-1.0.jar");
    }

    @Test
    void takesTheExclusionsTheModuleManages() throws IOException {
        final var local = new LocalRepository(directory.resolve("m2"));
        local.pom("org.example", "web", "1.0", dependencies(dependency("org.example", "json", "1.0", null)));
        local.pom("org.example", "json", "1.0", "");
        List.of("web", "json").forEach(artifact -> jar(local, artifact, "1.0"));

        final var module = module("""
            <dependencyManagement>
                <dependencies>
                    <dependency>
                        <groupId>org.example</groupId><artifactId>web</artifactId><version>1.0</version>
                        <exclusions><exclusion><groupId>org.example</groupId><artifactId>json</artifactId></exclusion></exclusions>
                    </dependency>
                </dependencies>
            </dependencyManagement>
            %s
            """.formatted(dependencies(dependency("org.example", "web", null, null))));

        assertThat(module.dependencies()).containsExactly(
            new MavenDependency("org.example", "web", "1.0", "compile", Set.of("org.example:json"))
        );
        assertThat(names(MavenClasspath.of(List.of(module), Set.of(), local.repository())))
            .containsExactly("web-1.0.jar");
    }

    @Test
    void takesTheNewestVersionInTheRepositoryWhenThePomsDoNotTell() throws IOException {
        final var local = new LocalRepository(directory.resolve("m2"));
        local.pom("org.example", "web", "1.0", "");
        local.pom("org.example", "web", "1.2", "");
        jar(local, "web", "1.0");
        jar(local, "web", "1.2");

        final var module = module(dependencies(dependency("org.example", "web", null, null)));

        assertThat(names(MavenClasspath.of(List.of(module), Set.of(), local.repository())))
            .containsExactly("web-1.2.jar");
    }

    @Test
    void leavesOutTheModulesThatAreScanned() throws IOException {
        final var local = new LocalRepository(directory.resolve("m2"));
        local.pom("com.example", "domain", "1.0", "");
        jar(local, "web", "1.0");
        local.pom("org.example", "web", "1.0", "");
        local.jar("com.example", "domain", "1.0", Map.of("com/example/Domain.java", "package com.example; public class Domain { }"));

        final var module = module(dependencies(
            dependency("com.example", "domain", "1.0", null),
            dependency("org.example", "web", "1.0", null)
        ));

        assertThat(names(MavenClasspath.of(List.of(module), Set.of("com.example:domain"), local.repository())))
            .containsExactly("web-1.0.jar");
    }

    @Test
    void findsNothingWithoutARepository() throws IOException {
        final var module = module(dependencies(dependency("org.example", "web", "1.0", null)));

        assertThat(MavenClasspath.of(List.of(module), Set.of(), MavenRepository.NONE)).isEmpty();
    }

    private MavenPom module(final String content) throws IOException {
        return module(content, MavenRepository.NONE);
    }

    private MavenPom module(final String content, final MavenRepository repository) throws IOException {
        final var pom = directory.resolve("project/pom.xml");
        Files.createDirectories(pom.getParent());
        Files.writeString(pom, """
            <project>
                <groupId>com.example</groupId>
                <artifactId>shop</artifactId>
                <version>1.0</version>
                %s
            </project>
            """.formatted(content));

        return MavenPom.read(pom, repository);
    }

    private static void jar(final LocalRepository local, final String artifactId, final String version) {
        final var name = Character.toUpperCase(artifactId.charAt(0)) + artifactId.substring(1).replace("-", "");

        local.jar("org.example", artifactId, version, Map.of(
            "org/example/" + name + ".java", "package org.example; public class " + name + " { }"
        ));
    }

    private static String dependencies(final String... dependencies) {
        return "<dependencies>" + String.join("", dependencies) + "</dependencies>";
    }

    private static String dependency(
        final String groupId,
        final String artifactId,
        final String version,
        final String scope
    ) {
        return "<dependency><groupId>" + groupId + "</groupId><artifactId>" + artifactId + "</artifactId>"
            + (version == null ? "" : "<version>" + version + "</version>")
            + (scope == null ? "" : "<scope>" + scope + "</scope>")
            + "</dependency>";
    }

    private static List<String> names(final List<Path> jars) {
        return jars.stream().map(jar -> jar.getFileName().toString()).toList();
    }
}
