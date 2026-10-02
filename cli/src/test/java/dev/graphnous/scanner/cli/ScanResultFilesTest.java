package dev.graphnous.scanner.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.graphnous.scanner.ScanResultWriter;
import dev.graphnous.scanner.model.ScanResultSchema;
import dev.graphnous.scanner.model.ScanTarget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScanResultFilesTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final ScanResultFiles files = new ScanResultFiles(new ScanResultWriter(objectMapper));

    @TempDir
    Path directory;

    @ParameterizedTest(name = "{0} {1} -> {2}")
    @CsvSource({
        "JAVA,       .,              java-root",
        "JAVA,       backend,        java-backend",
        "TYPESCRIPT, apps/web,       typescript-apps-web",
        "TYPESCRIPT, apps\\web,      typescript-apps-web",
        "JAVA,       my project/api, java-my_project-api"
    })
    void namesFilesAfterTheTarget(
        final ScanTarget.Language language,
        final String path,
        final String name
    ) {
        assertThat(ScanResultFiles.fileName(target(language, path))).isEqualTo(name);
    }

    @Test
    void writesEachResultToItsOwnFile() throws Exception {
        final var java = result(ScanTarget.Language.JAVA, ".");
        final var typescript = result(ScanTarget.Language.TYPESCRIPT, "apps/web");

        final var output = directory.resolve("nested/results");

        final var written = files.write(List.of(java, typescript), output);

        assertThat(written).containsExactly(
            output.resolve("java-root.json"),
            output.resolve("typescript-apps-web.json")
        );

        assertThat(objectMapper.readValue(written.get(0).toFile(), ScanResultSchema.class)).isEqualTo(java);
        assertThat(objectMapper.readValue(written.get(1).toFile(), ScanResultSchema.class)).isEqualTo(typescript);
    }

    @Test
    void keepsResultsWithTheSameNameApart() {
        final var written = files.write(
            List.of(
                result(ScanTarget.Language.JAVA, "a/b"),
                result(ScanTarget.Language.JAVA, "a-b")
            ),
            directory
        );

        assertThat(written).containsExactly(
            directory.resolve("java-a-b.json"),
            directory.resolve("java-a-b-2.json")
        );
    }

    @Test
    void writesNothingForNoResults() {
        assertThat(files.write(List.of(), directory.resolve("empty"))).isEmpty();
        assertThat(directory.resolve("empty")).isEmptyDirectory();
    }

    private static ScanResultSchema result(
        final ScanTarget.Language language,
        final String path
    ) {
        final var result = new ScanResultSchema();
        result.setFormat("graphnous-scan-result");
        result.setVersion("1");
        result.setTarget(target(language, path));
        result.setModules(new ArrayList<>());

        return result;
    }

    private static ScanTarget target(
        final ScanTarget.Language language,
        final String path
    ) {
        final var target = new ScanTarget();
        target.setLanguage(language);
        target.setPath(path);
        target.setLanguageVersion("25");

        return target;
    }
}
