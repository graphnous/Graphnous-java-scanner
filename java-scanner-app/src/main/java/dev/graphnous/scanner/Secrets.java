package dev.graphnous.scanner;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * What a scan result must not hold: secrets, by the name they are kept
 * under or by the value itself, as the scan result schema describes them
 * for constants and configuration properties.
 */
final class Secrets {

    /**
     * Words in a name that name a secret, in lower case, without
     * {@code -}, {@code _} and {@code .}. Not "key" alone, so names such as
     * routing-key are kept.
     */
    private static final List<String> NAMES = List.of(
        "password",
        "passwd",
        "secret",
        "token",
        "credential",
        "apikey",
        "accesskey",
        "privatekey",
        "authorization"
    );

    /**
     * A url with a user and a password, as in
     * {@code jdbc:postgresql://app:secret@db/orders}.
     */
    private static final Pattern URL_WITH_PASSWORD = Pattern.compile("[a-zA-Z][a-zA-Z0-9+.-]*://[^/\\s@:]*:[^/\\s@]+@");

    private Secrets() {
    }

    /**
     * Whether the name of a constant or the key of a property names a
     * secret, such as {@code API_KEY}, {@code dbPassword} or
     * {@code spring.datasource.password}.
     */
    static boolean namesSecret(final String name) {
        final var normalized = name.toLowerCase(Locale.ROOT).replaceAll("[-_.]", "");

        return NAMES.stream().anyMatch(normalized::contains);
    }

    /**
     * Whether the value holds a secret: a url with a password, a
     * {@code password=} setting, or a private key.
     */
    static boolean holdsSecret(final String value) {
        return URL_WITH_PASSWORD.matcher(value).find()
            || value.toLowerCase(Locale.ROOT).contains("password=")
            || value.contains("-----BEGIN");
    }
}
