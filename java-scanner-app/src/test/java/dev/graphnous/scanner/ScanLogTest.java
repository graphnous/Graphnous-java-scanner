package dev.graphnous.scanner;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ScanLogTest {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @Test
    void hidesDetailsByDefault() {
        final var log = log(false);

        log.info("Module core: 2 files, 3 classes");
        log.detail("  core/A.java: 1 class");

        assertThat(text(out)).isEqualTo("Module core: 2 files, 3 classes\n");
    }

    @Test
    void showsDetailsWhenVerbose() {
        final var log = log(true);

        log.info("Module core: 2 files, 3 classes");
        log.detail("  core/A.java: 1 class");

        assertThat(text(out)).isEqualTo("Module core: 2 files, 3 classes\n  core/A.java: 1 class\n");
    }

    @Test
    void writesWarningsToStderr() {
        log(false).warn("Skipping Broken.java: syntax error");

        assertThat(text(err)).isEqualTo("Skipping Broken.java: syntax error\n");
        assertThat(text(out)).isEmpty();
    }

    private ScanLog log(final boolean verbose) {
        return ScanLog.console(
            new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8),
            verbose
        );
    }

    private static String text(final ByteArrayOutputStream stream) {
        return stream.toString(StandardCharsets.UTF_8).replace(System.lineSeparator(), "\n");
    }
}
