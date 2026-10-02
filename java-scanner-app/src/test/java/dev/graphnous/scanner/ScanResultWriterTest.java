package dev.graphnous.scanner;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.graphnous.scanner.model.ScanResultSchema;
import dev.graphnous.scanner.model.ScanTarget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScanResultWriterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final ScanResultWriter writer = new ScanResultWriter(objectMapper);

    @TempDir
    Path directory;

    @Test
    void writesResultAsJson() throws Exception {
        final var output = directory.resolve("scan-result.json");
        final var result = result();

        writer.write(result, output);

        assertThat(output).exists();
        assertThat(objectMapper.readValue(output.toFile(), ScanResultSchema.class))
            .isEqualTo(result);
    }

    @Test
    void failsWhenOutputCannotBeWritten() {
        final var output = directory.resolve("missing").resolve("scan-result.json");

        assertThatThrownBy(() -> writer.write(result(), output))
            .isInstanceOf(UncheckedIOException.class)
            .hasMessageContaining(output.toString());
    }

    private static ScanResultSchema result() {
        final var target = new ScanTarget();
        target.setPath(".");
        target.setLanguage(ScanTarget.Language.JAVA);
        target.setLanguageVersion("25");

        final var result = new ScanResultSchema();
        result.setFormat("graphnous-scan-result");
        result.setVersion("1");
        result.setTarget(target);
        result.setModules(new ArrayList<>());

        return result;
    }
}
