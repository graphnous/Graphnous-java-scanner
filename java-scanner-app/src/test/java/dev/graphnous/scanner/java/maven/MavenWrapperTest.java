package dev.graphnous.scanner.java.maven;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MavenWrapperTest {

    @TempDir
    Path directory;

    @Test
    void readsTheVersionFromTheDistributionUrl() throws IOException {
        wrapper(directory, "distributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.9/apache-maven-3.9.9-bin.zip");

        assertThat(MavenWrapper.version(directory, directory)).contains("3.9.9");
    }

    @Test
    void usesTheWrapperOfTheProjectAModuleIsPartOf() throws IOException {
        wrapper(directory, "distributionUrl=https\\://repo.example.com/apache-maven/4.0.0-rc-2/apache-maven-4.0.0-rc-2-bin.tar.gz");

        assertThat(MavenWrapper.version(directory, directory.resolve("backend/core"))).contains("4.0.0-rc-2");
    }

    @Test
    void ignoresWrappersOutsideTheRepository() throws IOException {
        wrapper(directory, "distributionUrl=https://repo.example.com/apache-maven-3.9.9-bin.zip");

        final var repository = directory.resolve("repository");

        assertThat(MavenWrapper.version(repository, repository)).isEmpty();
    }

    @Test
    void returnsNothingWithoutAWrapperOrVersion() throws IOException {
        assertThat(MavenWrapper.version(directory, directory)).isEmpty();

        wrapper(directory, "wrapperVersion=3.3.2");

        assertThat(MavenWrapper.version(directory, directory)).isEmpty();
    }

    private static void wrapper(final Path project, final String content) throws IOException {
        final var properties = project.resolve(".mvn/wrapper/maven-wrapper.properties");
        Files.createDirectories(properties.getParent());
        Files.writeString(properties, content);
    }
}
