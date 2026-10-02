package dev.graphnous.scanner.java.maven;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MavenPomFinderTest {

    @TempDir
    Path repository;

    @Test
    void findsPomsAtAnyDepth() throws IOException {
        pom(".");
        pom("core");
        pom("services/api");

        assertThat(MavenPomFinder.find(repository)).containsExactlyInAnyOrder(
            repository.resolve("pom.xml"),
            repository.resolve("core/pom.xml"),
            repository.resolve("services/api/pom.xml")
        );
    }

    @Test
    void skipsTargetDirectoriesOfMavenProjects() throws IOException {
        pom(".");
        pom("target/generated-project");
        pom("target/classes/META-INF/maven/dev.graphnous/core");
        pom("core");
        pom("core/target/it/sample");

        assertThat(MavenPomFinder.find(repository)).containsExactlyInAnyOrder(
            repository.resolve("pom.xml"),
            repository.resolve("core/pom.xml")
        );
    }

    @Test
    void keepsTargetDirectoriesOutsideMavenProjects() throws IOException {
        pom("target");
        pom("apps/target/module");

        assertThat(MavenPomFinder.find(repository)).containsExactlyInAnyOrder(
            repository.resolve("target/pom.xml"),
            repository.resolve("apps/target/module/pom.xml")
        );
    }

    @Test
    void searchesTheRootEvenWhenItIsATargetDirectory() throws IOException {
        pom(".");
        pom("target");
        pom("target/nested");

        assertThat(MavenPomFinder.find(repository.resolve("target"))).containsExactlyInAnyOrder(
            repository.resolve("target/pom.xml"),
            repository.resolve("target/nested/pom.xml")
        );
    }

    @Test
    void ignoresDirectoriesNamedPomXml() throws IOException {
        Files.createDirectories(repository.resolve("pom.xml"));

        assertThat(MavenPomFinder.find(repository)).isEmpty();
    }

    @Test
    void failsForAMissingRoot() {
        assertThatThrownBy(() -> MavenPomFinder.find(repository.resolve("missing")))
            .isInstanceOf(UncheckedIOException.class)
            .hasMessageContaining("missing");
    }

    private void pom(final String directory) throws IOException {
        final var path = repository.resolve(directory);
        Files.createDirectories(path);
        Files.writeString(path.resolve("pom.xml"), "<project/>");
    }
}
