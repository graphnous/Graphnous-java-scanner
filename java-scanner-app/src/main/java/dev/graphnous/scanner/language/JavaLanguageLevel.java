package dev.graphnous.scanner.language;

import com.github.javaparser.ParserConfiguration.LanguageLevel;

import java.util.regex.Pattern;

/**
 * Maps a project's Java version to the JavaParser language level to parse
 * its sources with.
 */
public final class JavaLanguageLevel {

    private static final Pattern VERSION = Pattern.compile("(?:1\\.)?(\\d+)(?:\\..*)?");

    private JavaLanguageLevel() {
    }

    /**
     * {@code 8} and {@code 1.8} → Java 8, {@code 17.0.2} → Java 17, and
     * {@code 1.4} → Java 1.4. Versions that are missing, unrecognised or
     * newer than JavaParser supports get {@link LanguageLevel#CURRENT}.
     */
    public static LanguageLevel of(final String version) {
        if (version == null) {
            return LanguageLevel.CURRENT;
        }

        final var matcher = VERSION.matcher(version.trim());

        if (!matcher.matches()) {
            return LanguageLevel.CURRENT;
        }

        final var release = Integer.parseInt(matcher.group(1));

        final var name = release <= 4
            ? "JAVA_1_" + release
            : "JAVA_" + release;

        try {
            return LanguageLevel.valueOf(name);
        } catch (IllegalArgumentException e) {
            return LanguageLevel.CURRENT;
        }
    }

    /**
     * The Java version of a language level, the reverse of {@link #of}:
     * {@code JAVA_17} → {@code 17} and {@code JAVA_1_4} → {@code 1.4}.
     */
    public static String version(final LanguageLevel level) {
        return level.name()
            .replaceFirst("^JAVA_", "")
            .replaceFirst("_PREVIEW$", "")
            .replace('_', '.');
    }
}
