package dev.graphnous.scanner.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import dev.graphnous.scanner.docker.DockerSandbox;
import dev.graphnous.scanner.GraphnousScanner;
import dev.graphnous.scanner.ScanReport;
import dev.graphnous.scanner.ScanResultWriter;
import dev.graphnous.scanner.java.JavaScannerDefinition;
import dev.graphnous.scanner.executor.DefaultScanExecutor;
import dev.graphnous.scanner.java.language.JavaVersionDetector;
import dev.graphnous.scanner.plan.DefaultScanPlanner;
import dev.graphnous.scanner.sandbox.FileSystemScanSandbox;
import dev.graphnous.scanner.sandbox.ScanSandbox;
import dev.graphnous.scanner.java.targetdetector.MavenScanTargetDetector;
import dev.graphnous.scanner.typescript.TypescriptScannerDefinition;
import dev.graphnous.scanner.typescript.language.NodeVersionDetector;
import dev.graphnous.scanner.typescript.targetdetector.NpmTargetDetector;

import java.nio.file.Path;
import java.util.List;

public final class Main {

    static final int OK = 0;
    static final int SCAN_FAILED = 1;
    static final int USAGE_ERROR = 2;

    private Main() {
    }

    static void main(final String[] args) {
        System.exit(run(args));
    }

    static int run(final String[] args) {
        if (CliOptions.isHelp(args)) {
            System.out.print(CliOptions.USAGE);
            return OK;
        }

        final CliOptions options;

        try {
            options = CliOptions.parse(args, defaultScannersDirectory());
        } catch (CliOptions.UsageException e) {
            System.err.println("Error: " + e.getMessage());
            System.err.println();
            System.err.print(CliOptions.USAGE);
            return USAGE_ERROR;
        }

        try {
            final var report = scan(options);
            return report.hasFailures() ? SCAN_FAILED : OK;
        } catch (RuntimeException e) {
            System.err.println("Scan failed: " + e.getMessage());
            return SCAN_FAILED;
        }
    }

    private static ScanReport scan(final CliOptions options) {
        final var objectMapper = new ObjectMapper();
        final var reporter = new ConsoleReporter(System.out, System.err);

        final var planner = new DefaultScanPlanner(
            List.of(
                new MavenScanTargetDetector(),
                new NpmTargetDetector()
            ),
            List.of(
                new JavaVersionDetector(),
                new NodeVersionDetector(objectMapper, TypescriptScannerDefinition.DEFAULT_NODE_VERSION)
            )
        );

        final var executor = new DefaultScanExecutor(
            List.of(
                new JavaScannerDefinition(options.javaScanner(), options.verbose()),
                new TypescriptScannerDefinition(options.typescriptScanner())
            ),
            sandbox(options, objectMapper, reporter),
            List.of(reporter)
        );

        final var scanner = new GraphnousScanner(
            planner,
            executor,
            List.of(reporter)
        );

        System.out.println("Scanning " + options.repository() + " (sandbox: " + options.sandbox().name().toLowerCase() + ")");

        final var report = scanner.scan(options.repository());

        final var files = new ScanResultFiles(new ScanResultWriter(objectMapper))
            .write(report.results(), options.output());

        System.out.println("Wrote " + files.size() + (files.size() == 1 ? " result" : " results") + " to " + options.output());

        files.forEach(file ->
            System.out.println("  - " + file.getFileName())
        );

        if (report.hasFailures()) {
            final var total = report.results().size() + report.failures().size();

            System.err.println("Failed to scan " + report.failures().size() + " of " + total + " projects:");

            report.failures().forEach(failure ->
                System.err.println("  - " + ConsoleReporter.describe(failure.target()) + ": " + failure.message())
            );
        }

        return report;
    }

    private static ScanSandbox sandbox(
        final CliOptions options,
        final ObjectMapper objectMapper,
        final ConsoleReporter reporter
    ) {
        return switch (options.sandbox()) {
            case PROCESS -> new FileSystemScanSandbox(objectMapper, reporter);
            case DOCKER -> new DockerSandbox(
                connectToDocker(),
                options.dockerWorkspace(),
                objectMapper,
                reporter
            );
        };
    }

    private static DockerClient connectToDocker() {
        final var docker = DockerSandbox.defaultClient();

        try {
            docker.pingCmd().exec();
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                "Docker is not reachable (" + e.getMessage() + "). Is Docker running?",
                e
            );
        }

        return docker;
    }

    /**
     * The {@code scanners} directory next to the CLI jar, or next to the
     * classes directory when running from the build output.
     */
    static Path defaultScannersDirectory() {
        try {
            final var location = Path.of(
                Main.class.getProtectionDomain().getCodeSource().getLocation().toURI()
            );

            return location.getParent().resolve("scanners");
        } catch (Exception e) {
            return Path.of("scanners");
        }
    }
}
