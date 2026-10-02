package dev.graphnous.scanner.cli;

import dev.graphnous.scanner.ScanFailure;
import dev.graphnous.scanner.ScannerListener;
import dev.graphnous.scanner.listener.ScanProcessListener;
import dev.graphnous.scanner.model.ScanTarget;
import dev.graphnous.scanner.plan.ScanPlan;

import java.io.PrintStream;

/**
 * Prints scan progress, with the output of the scanner processes
 * indented below the step that runs them.
 */
class ConsoleReporter implements ScannerListener, ScanProcessListener {

    private static final String INDENT = "    ";

    private final PrintStream out;
    private final PrintStream err;

    ConsoleReporter(
        final PrintStream out,
        final PrintStream err
    ) {
        this.out = out;
        this.err = err;
    }

    @Override
    public void onScanStarted() {
        out.println("Detecting projects");
    }

    @Override
    public void onPlanCreated(final ScanPlan plan) {
        final var targets = plan.targets();

        out.println("Found " + targets.size() + (targets.size() == 1 ? " project" : " projects"));

        targets.forEach(target ->
            out.println("  - " + describe(target))
        );

        plan.warnings().forEach(warning ->
            err.println("Warning: " + warning)
        );
    }

    @Override
    public void onStepChanged(final ScanStep step) {
        out.println("[" + step.index() + "/" + step.total() + "] Scanning " + describe(step.target()));
    }

    @Override
    public void onStepFailed(
        final ScanStep step,
        final ScanFailure failure
    ) {
        err.println("[" + step.index() + "/" + step.total() + "] Failed: " + failure.message());
    }

    @Override
    public void onScanComplete() {
        out.println("Scan completed");
    }

    @Override
    public void stdout(final String line) {
        out.println(INDENT + line);
    }

    @Override
    public void stderr(final String line) {
        err.println(INDENT + line);
    }

    static String describe(final ScanTarget target) {
        final var version = target.getLanguageVersion() == null
            ? ""
            : " " + target.getLanguageVersion();

        return target.getPath()
            + " (" + target.getLanguage() + version + ", " + target.getBuildSystem() + ")";
    }
}
