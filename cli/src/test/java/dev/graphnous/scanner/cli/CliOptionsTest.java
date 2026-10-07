package dev.graphnous.scanner.cli;

import dev.graphnous.scanner.docker.DockerWorkspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CliOptionsTest {

    @TempDir
    Path directory;

    private Path repository;

    @BeforeEach
    void setUp() throws IOException {
        repository = Files.createDirectories(directory.resolve("repo"));
    }

    @Test
    void usesDefaultsForAllOptions() {
        final var options = parse(repository.toString());

        assertThat(options.repository()).isEqualTo(repository);
        assertThat(options.output()).isEqualTo(Path.of("scan-results").toAbsolutePath());
        assertThat(options.dockerWorkspace()).isEqualTo(DockerWorkspace.hostDirectory());
        assertThat(options.verbose()).isFalse();
        assertThat(options.javaScannerImage()).isEqualTo(Scanners.JAVA_IMAGE);
        assertThat(options.typescriptScannerImage()).isEqualTo(Scanners.TYPESCRIPT_IMAGE);
    }

    @Test
    void readsAllOptions() {
        final var options = parse(
            "--output", directory.resolve("out").toString(),
            repository.toString(),
            "--java-scanner-image", "registry.example.com/java-scanner:dev",
            "--typescript-scanner-image", "registry.example.com/typescript-scanner:dev"
        );

        assertThat(options.repository()).isEqualTo(repository);
        assertThat(options.output()).isEqualTo(directory.resolve("out"));
        assertThat(options.javaScannerImage()).isEqualTo("registry.example.com/java-scanner:dev");
        assertThat(options.typescriptScannerImage()).isEqualTo("registry.example.com/typescript-scanner:dev");
    }

    @Test
    void readsDockerVolume() {
        final var options = parse(
            repository.toString(),
            "--docker-volume", "checkouts:/checkouts"
        );

        assertThat(options.dockerWorkspace())
            .isEqualTo(DockerWorkspace.volume("checkouts", Path.of("/checkouts")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"checkouts", "checkouts:", ":/checkouts"})
    void failsForMalformedDockerVolume(final String volume) {
        assertUsageError(
            "Expected --docker-volume <name>:<path>",
            repository.toString(), "--docker-volume", volume
        );
    }

    @Test
    void failsForRelativeDockerVolumePath() {
        assertUsageError(
            "Volume mount path must be absolute",
            repository.toString(), "--docker-volume", "checkouts:relative"
        );
    }

    @Test
    void readsVerbose() {
        assertThat(parse(repository.toString(), "--verbose").verbose()).isTrue();
    }

    @Test
    void normalizesTheRepositoryPath() {
        final var options = parse(repository.resolve("../repo/.").toString());

        assertThat(options.repository()).isEqualTo(repository);
    }

    @Test
    void failsWithoutRepository() {
        assertUsageError("Missing repository to scan");
    }

    @Test
    void failsForMissingRepositoryDirectory() {
        assertUsageError(
            "Repository is not a directory",
            directory.resolve("missing").toString()
        );
    }

    @Test
    void failsForMoreThanOneRepository() {
        assertUsageError(
            "Only one repository can be scanned",
            repository.toString(),
            directory.toString()
        );
    }

    @Test
    void failsForUnknownOption() {
        assertUsageError("Unknown option: --color", repository.toString(), "--color");
    }

    @Test
    void failsForMissingOptionValue() {
        assertUsageError("Missing value for --output", repository.toString(), "--output");
    }

    @Test
    void failsForTheRemovedProcessSandbox() {
        assertUsageError("Unknown option: --sandbox", repository.toString(), "--sandbox", "process");
    }

    @Test
    void recognizesHelp() {
        assertThat(CliOptions.isHelp(new String[] {"repo", "--help"})).isTrue();
        assertThat(CliOptions.isHelp(new String[] {"-h"})).isTrue();
        assertThat(CliOptions.isHelp(new String[] {"repo"})).isFalse();
    }

    private CliOptions parse(final String... args) {
        return CliOptions.parse(args);
    }

    private void assertUsageError(final String message, final String... args) {
        assertThatThrownBy(() -> parse(args))
            .isInstanceOf(CliOptions.UsageException.class)
            .hasMessageStartingWith(message);
    }
}
