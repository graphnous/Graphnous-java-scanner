package dev.graphnous.scanner.language;

import com.github.javaparser.ParserConfiguration.LanguageLevel;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class JavaLanguageLevelTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
        "8,      JAVA_8",
        "1.8,    JAVA_8",
        "11,     JAVA_11",
        "17,     JAVA_17",
        "17.0.2, JAVA_17",
        "' 21 ', JAVA_21",
        "25,     JAVA_25",
        "1.4,    JAVA_1_4",
        "5,      JAVA_5",
        "1.5,    JAVA_5"
    })
    void mapsVersionsToLanguageLevels(final String version, final LanguageLevel level) {
        assertThat(JavaLanguageLevel.of(version)).isEqualTo(level);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "latest", "${java.version}", "99"})
    void usesCurrentForUnknownVersions(final String version) {
        assertThat(JavaLanguageLevel.of(version)).isEqualTo(LanguageLevel.CURRENT);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
        "JAVA_1_4, 1.4",
        "JAVA_8,   8",
        "JAVA_25,  25"
    })
    void mapsLanguageLevelsToVersions(final LanguageLevel level, final String version) {
        assertThat(JavaLanguageLevel.version(level)).isEqualTo(version);
    }
}
