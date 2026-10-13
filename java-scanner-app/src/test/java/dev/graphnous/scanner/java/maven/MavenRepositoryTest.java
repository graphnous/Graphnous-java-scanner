package dev.graphnous.scanner.java.maven;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MavenRepositoryTest {

    @TempDir
    Path directory;

    @Test
    void findsThePomsAndJarsOfArtifacts() {
        final var local = new LocalRepository(directory.resolve("m2"));
        final var pom = local.pom("org.example", "client", "1.0", "");
        final var jar = local.jar("org.example", "client", "1.0", Map.of("org/example/Client.java", "package org.example; public class Client { }"));

        final var repository = local.repository();

        assertThat(repository.exists()).isTrue();
        assertThat(repository.pom("org.example", "client", "1.0")).contains(pom);
        assertThat(repository.jar("org.example", "client", "1.0")).contains(jar);
        assertThat(repository.jar("org.example", "client", "2.0")).isEmpty();
        assertThat(repository.jar("org.example", "client", null)).isEmpty();
    }

    @Test
    void findsTheNewestVersionOfAnArtifact() {
        final var local = new LocalRepository(directory.resolve("m2"));
        List.of("1.9", "1.10", "1.10-SNAPSHOT", "1.2.3").forEach(version -> local.pom("org.example", "client", version, ""));

        assertThat(local.repository().newestVersion("org.example", "client")).contains("1.10");
        assertThat(local.repository().newestVersion("org.example", "missing")).isEmpty();
    }

    @Test
    void comparesVersionsByTheirNumbers() {
        assertThat(MavenRepository.compareVersions("1.10", "1.9")).isPositive();
        assertThat(MavenRepository.compareVersions("2.0", "2.0-SNAPSHOT")).isPositive();
        assertThat(MavenRepository.compareVersions("2.0", "2.0-M1")).isPositive();
        assertThat(MavenRepository.compareVersions("2.0.1", "2.0")).isPositive();
        assertThat(MavenRepository.compareVersions("1.0.1", "1.0-RC1")).isPositive();
        assertThat(MavenRepository.compareVersions("3.2", "3.2")).isZero();
    }

    @Test
    void staysInsideTheRepository() {
        final var local = new LocalRepository(directory.resolve("m2"));
        local.pom("org.example", "client", "1.0", "");

        assertThat(local.repository().pom("org.example", "client", "../../../../m2/org/example/client/1.0")).isEmpty();
        assertThat(local.repository().pom("..", "client", "1.0")).isEmpty();
    }

    @Test
    void isNoneWhenTheDirectoryDoesNotExist() {
        final var repository = MavenRepository.at(directory.resolve("missing"));

        assertThat(repository.exists()).isFalse();
        assertThat(repository.pom("org.example", "client", "1.0")).isEmpty();
    }
}
