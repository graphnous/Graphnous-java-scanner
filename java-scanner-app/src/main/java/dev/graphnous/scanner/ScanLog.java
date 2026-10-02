package dev.graphnous.scanner;

import java.io.PrintStream;

/**
 * Where the scanner reports progress. The scanner runs as a separate
 * process whose stdout and stderr reach the CLI line by line, so this
 * decides how much of that output there is.
 */
public interface ScanLog {

    /**
     * Progress that is always shown, such as one line per module.
     */
    void info(String line);

    /**
     * Details only shown with {@code --verbose}, such as one line per file.
     */
    void detail(String line);

    /**
     * Problems that do not stop the scan, such as a skipped file.
     */
    void warn(String line);

    static ScanLog console(final boolean verbose) {
        return console(System.out, System.err, verbose);
    }

    static ScanLog console(
        final PrintStream out,
        final PrintStream err,
        final boolean verbose
    ) {
        return new ScanLog() {
            @Override
            public void info(final String line) {
                out.println(line);
            }

            @Override
            public void detail(final String line) {
                if (verbose) {
                    out.println(line);
                }
            }

            @Override
            public void warn(final String line) {
                err.println(line);
            }
        };
    }
}
