package dev.graphnous.scanner.cli;

import dev.graphnous.scanner.docker.DockerWorkspace;
import dev.graphnous.core.model.ScanTarget;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ScannersTest {

    @Test
    void runsEachScannerFromItsImage() {
        final var scanners = Scanners.of(options("java:dev", "typescript:dev", false));

        assertThat(scanners).hasSize(2);
        assertThat(scanners.get(0).image()).isEqualTo("java:dev");
        assertThat(scanners.get(0).supports(target(ScanTarget.Language.JAVA, null))).isTrue();
        assertThat(scanners.get(1).image()).isEqualTo("typescript:dev");
        assertThat(scanners.get(1).supports(target(ScanTarget.Language.TYPESCRIPT, null))).isTrue();
    }

    @Test
    void passesTheTargetToTheJavaScanner() {
        final var java = Scanners.of(options(Scanners.JAVA_IMAGE, Scanners.TYPESCRIPT_IMAGE, false)).get(0);

        assertThat(java.command("/workspace", target(ScanTarget.Language.JAVA, "21"))).containsExactly(
            "java", "-jar", "/opt/graphnous/java-scanner.jar",
            "--path", "/workspace",
            "--target", "backend",
            "--java-version", "21",
            "--output", "/output/scan-result.json"
        );
    }

    @Test
    void makesTheJavaScannerVerbose() {
        final var java = Scanners.of(options(Scanners.JAVA_IMAGE, Scanners.TYPESCRIPT_IMAGE, true)).get(0);

        assertThat(java.command("/workspace", target(ScanTarget.Language.JAVA, null))).endsWith("--verbose");
    }

    @Test
    void passesTheTargetToTheTypescriptScanner() {
        final var typescript = Scanners.of(options(Scanners.JAVA_IMAGE, Scanners.TYPESCRIPT_IMAGE, false)).get(1);

        assertThat(typescript.command("/workspace", target(ScanTarget.Language.TYPESCRIPT, "24"))).containsExactly(
            "node", "/opt/graphnous/scanner.js",
            "--path", "/workspace",
            "--target", "backend",
            "--output", "/output/scan-result.json"
        );
    }

    private static CliOptions options(
        final String javaImage,
        final String typescriptImage,
        final boolean verbose
    ) {
        return new CliOptions(Path.of("/repo"), Path.of("/out"), DockerWorkspace.hostDirectory(), javaImage, typescriptImage, verbose);
    }

    private static ScanTarget target(final ScanTarget.Language language, final String version) {
        final var target = new ScanTarget();
        target.setPath("backend");
        target.setLanguage(language);
        target.setLanguageVersion(version);

        return target;
    }
}
