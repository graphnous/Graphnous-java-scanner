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
    private Path scanners;

    @BeforeEach
    void setUp() throws IOException {
        repository = Files.createDirectories(directory.resolve("repo"));
        scanners = Files.createDirectories(directory.resolve("scanners"));

        Files.writeString(scanners.resolve("java-scanner.jar"), "");
        Files.writeString(scanners.resolve("scanner.js"), "");
    }

    @Test
    void usesDefaultsForAllOptions() {
        final var options = parse(repository.toString());

        assertThat(options.repository()).isEqualTo(repository);
        assertThat(options.output()).isEqualTo(Path.of("scan-results").toAbsolutePath());
        assertThat(options.sandbox()).isEqualTo(CliOptions.Sandbox.PROCESS);
        assertThat(options.dockerWorkspace()).isEqualTo(DockerWorkspace.hostDirectory());
        assertThat(options.verbose()).isFalse();
        assertThat(options.javaScanner()).isEqualTo(scanners.resolve("java-scanner.jar"));
        assertThat(options.typescriptScanner()).isEqualTo(scanners.resolve("scanner.js"));
    }

    @Test
    void readsAllOptions() throws IOException {
        final var javaScanner = Files.writeString(directory.resolve("custom.jar"), "");
        final var typescriptScanner = Files.writeString(directory.resolve("custom.js"), "");

        final var options = parse(
            "--output", directory.resolve("out").toString(),
            "--sandbox", "docker",
            repository.toString(),
            "--java-scanner", javaScanner.toString(),
            "--typescript-scanner", typescriptScanner.toString()
        );

        assertThat(options.repository()).isEqualTo(repository);
        assertThat(options.output()).isEqualTo(directory.resolve("out"));
        assertThat(options.sandbox()).isEqualTo(CliOptions.Sandbox.DOCKER);
        assertThat(options.javaScanner()).isEqualTo(javaScanner);
        assertThat(options.typescriptScanner()).isEqualTo(typescriptScanner);
    }

    @Test
    void readsDockerVolume() {
        final var options = parse(
            repository.toString(),
            "--sandbox", "docker",
            "--docker-volume", "checkouts:/checkouts"
        );

        assertThat(options.dockerWorkspace())
            .isEqualTo(DockerWorkspace.volume("checkouts", Path.of("/checkouts")));
    }

    @Test
    void failsForDockerVolumeWithoutDockerSandbox() {
        assertUsageError(
            "--docker-volume requires --sandbox docker",
            repository.toString(),
            "--docker-volume", "checkouts:/checkouts"
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"checkouts", "checkouts:", ":/checkouts"})
    void failsForMalformedDockerVolume(final String volume) {
        assertUsageError(
            "Expected --docker-volume <name>:<path>",
            repository.toString(), "--sandbox", "docker", "--docker-volume", volume
        );
    }

    @Test
    void failsForRelativeDockerVolumePath() {
        assertUsageError(
            "Volume mount path must be absolute",
            repository.toString(), "--sandbox", "docker", "--docker-volume", "checkouts:relative"
        );
    }

    @Test
    void readsVerbose() {
        assertThat(parse(repository.toString(), "--verbose").verbose()).isTrue();
    }

    @Test
    void acceptsSandboxInAnyCase() {
        assertThat(parse(repository.toString(), "--sandbox", "DOCKER").sandbox())
            .isEqualTo(CliOptions.Sandbox.DOCKER);
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
    void failsForUnknownSandbox() {
        assertUsageError("Unknown sandbox: vm", repository.toString(), "--sandbox", "vm");
    }

    @Test
    void failsWhenAScannerIsMissing() throws IOException {
        Files.delete(scanners.resolve("java-scanner.jar"));

        assertUsageError("Scanner not found: " + scanners.resolve("java-scanner.jar"), repository.toString());
    }

    @Test
    void recognizesHelp() {
        assertThat(CliOptions.isHelp(new String[] {"repo", "--help"})).isTrue();
        assertThat(CliOptions.isHelp(new String[] {"-h"})).isTrue();
        assertThat(CliOptions.isHelp(new String[] {"repo"})).isFalse();
    }

    private CliOptions parse(final String... args) {
        return CliOptions.parse(args, scanners);
    }

    private void assertUsageError(final String message, final String... args) {
        assertThatThrownBy(() -> parse(args))
            .isInstanceOf(CliOptions.UsageException.class)
            .hasMessageStartingWith(message);
    }
}
