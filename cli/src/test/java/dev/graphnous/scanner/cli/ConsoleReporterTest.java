package dev.graphnous.scanner.cli;

import dev.graphnous.scanner.ScanFailure;
import dev.graphnous.scanner.ScannerListener;
import dev.graphnous.core.model.ScanTarget;
import dev.graphnous.scanner.plan.ScanPlan;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConsoleReporterTest {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private final ConsoleReporter reporter = new ConsoleReporter(
        new PrintStream(out, true, StandardCharsets.UTF_8),
        new PrintStream(err, true, StandardCharsets.UTF_8)
    );

    @Test
    void reportsThePlanAndEachStep() {
        final var java = target(ScanTarget.Language.JAVA, ".", "25", ScanTarget.BuildSystem.MAVEN);
        final var web = target(ScanTarget.Language.TYPESCRIPT, "apps/web", null, ScanTarget.BuildSystem.NPM);

        reporter.onScanStarted();
        reporter.onPlanCreated(new ScanPlan(List.of(java, web)));
        reporter.onStepChanged(new ScannerListener.ScanStep(1, 2, java));
        reporter.stdout("scanning files");
        reporter.onStepChanged(new ScannerListener.ScanStep(2, 2, web));
        reporter.onScanComplete();

        assertThat(lines(out)).containsExactly(
            "Detecting projects",
            "Found 2 projects",
            "  - . (JAVA 25, MAVEN)",
            "  - apps/web (TYPESCRIPT, NPM)",
            "[1/2] Scanning . (JAVA 25, MAVEN)",
            "    scanning files",
            "[2/2] Scanning apps/web (TYPESCRIPT, NPM)",
            "Scan completed"
        );
    }

    @Test
    void usesSingularForOneProject() {
        reporter.onPlanCreated(new ScanPlan(List.of(
            target(ScanTarget.Language.JAVA, ".", "25", ScanTarget.BuildSystem.MAVEN)
        )));

        assertThat(lines(out)).first().isEqualTo("Found 1 project");
    }

    @Test
    void writesPlanWarningsToStderr() {
        reporter.onPlanCreated(new ScanPlan(
            List.of(target(ScanTarget.Language.JAVA, "legacy", null, ScanTarget.BuildSystem.MAVEN)),
            List.of("legacy: could not determine the JAVA version, scanning without it")
        ));

        assertThat(lines(out)).containsExactly(
            "Found 1 project",
            "  - legacy (JAVA, MAVEN)"
        );
        assertThat(lines(err)).containsExactly(
            "Warning: legacy: could not determine the JAVA version, scanning without it"
        );
    }

    @Test
    void reportsFailedStepsOnStderr() {
        final var web = target(ScanTarget.Language.TYPESCRIPT, "apps/web", "24", ScanTarget.BuildSystem.NPM);

        reporter.onStepFailed(
            new ScannerListener.ScanStep(2, 3, web),
            new ScanFailure(web, new IllegalStateException("Scanner failed with exit code 3"))
        );

        assertThat(lines(err)).containsExactly("[2/3] Failed: Scanner failed with exit code 3");
    }

    @Test
    void writesScannerErrorsToStderr() {
        reporter.stderr("Skipping Broken.java");

        assertThat(lines(err)).containsExactly("    Skipping Broken.java");
        assertThat(out.size()).isZero();
    }

    private static List<String> lines(final ByteArrayOutputStream stream) {
        return stream.toString(StandardCharsets.UTF_8).lines().toList();
    }

    private static ScanTarget target(
        final ScanTarget.Language language,
        final String path,
        final String version,
        final ScanTarget.BuildSystem buildSystem
    ) {
        final var target = new ScanTarget();
        target.setLanguage(language);
        target.setPath(path);
        target.setLanguageVersion(version);
        target.setBuildSystem(buildSystem);

        return target;
    }
}
