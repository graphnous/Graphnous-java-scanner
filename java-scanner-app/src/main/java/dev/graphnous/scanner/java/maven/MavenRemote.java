package dev.graphnous.scanner.java.maven;

import org.w3c.dom.Element;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A remote Maven repository, such as Maven Central, to download the poms
 * and jars of the libraries of a project from. Only files are downloaded:
 * no Maven and no plugin of the project runs.
 */
public final class MavenRemote {

    public static final URI MAVEN_CENTRAL = URI.create("https://repo.maven.apache.org/maven2/");

    /**
     * What a group, artifact or version may hold, so it names a path in
     * the repository and nothing else.
     */
    private static final Pattern COORDINATE = Pattern.compile("[A-Za-z0-9_.+\\-]+");

    /**
     * Bounds a download, against a file that is not what it claims to be.
     */
    private static final long MAX_SIZE = 512L * 1024 * 1024;

    private final URI base;

    private final HttpClient client;

    public MavenRemote(final URI base) {
        this.base = base.toString().endsWith("/") ? base : URI.create(base + "/");
        this.client = HttpClient.newBuilder()
            .proxy(ProxySelector.getDefault())
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    public URI base() {
        return base;
    }

    /**
     * Downloads the pom or jar of the artifact to the file, checked
     * against the SHA-1 checksum the repository publishes next to it.
     *
     * @return whether the repository has the file and it was downloaded
     */
    boolean download(
        final String groupId,
        final String artifactId,
        final String version,
        final String extension,
        final Path target
    ) {
        if (!isCoordinate(groupId) || !isCoordinate(artifactId) || !isCoordinate(version)) {
            return false;
        }

        final var path = groupId.replace('.', '/') + "/" + artifactId + "/" + version + "/"
            + artifactId + "-" + version + "." + extension;

        try {
            final var temporary = Files.createDirectories(target.getParent())
                .resolve(target.getFileName() + "." + ProcessHandle.current().pid() + "." + Thread.currentThread().threadId() + ".part");

            try {
                final var digest = fetch(path, temporary);

                if (digest.isEmpty()) {
                    return false;
                }

                final var expected = checksum(path);

                if (expected.isPresent() && !expected.get().equalsIgnoreCase(digest.get())) {
                    return false;
                }

                // Atomic, so another scan sharing the repository never
                // reads a file that is half written
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);

                return true;
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * The version the repository lists as the latest release of the
     * artifact, for a dependency whose version the poms do not tell.
     */
    Optional<String> latestVersion(
        final String groupId,
        final String artifactId
    ) {
        if (!isCoordinate(groupId) || !isCoordinate(artifactId)) {
            return Optional.empty();
        }

        try {
            final var response = client.send(
                request(groupId.replace('.', '/') + "/" + artifactId + "/maven-metadata.xml"),
                HttpResponse.BodyHandlers.ofByteArray()
            );

            if (response.statusCode() != 200) {
                return Optional.empty();
            }

            final var metadata = MavenPom.documentBuilderFactory()
                .newDocumentBuilder()
                .parse(new ByteArrayInputStream(response.body()))
                .getDocumentElement();

            return text(metadata, "release")
                .or(() -> text(metadata, "latest"))
                .filter(MavenRemote::isCoordinate);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Writes the file to the target and returns its SHA-1, or nothing when
     * the repository does not have it.
     */
    private Optional<String> fetch(
        final String path,
        final Path target
    ) throws IOException, InterruptedException {
        final var response = client.send(request(path), HttpResponse.BodyHandlers.ofInputStream());

        try (InputStream body = response.body()) {
            if (response.statusCode() != 200) {
                return Optional.empty();
            }

            final var digest = sha1();
            final var buffer = new byte[64 * 1024];
            var size = 0L;

            try (var output = Files.newOutputStream(target)) {
                for (int read = body.read(buffer); read >= 0; read = body.read(buffer)) {
                    size += read;

                    if (size > MAX_SIZE) {
                        return Optional.empty();
                    }

                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                }
            }

            return Optional.of(HexFormat.of().formatHex(digest.digest()));
        }
    }

    /**
     * The SHA-1 the repository publishes for the file, as the first word
     * of the {@code .sha1} file next to it.
     */
    private Optional<String> checksum(final String path) throws IOException, InterruptedException {
        final var response = client.send(request(path + ".sha1"), HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            return Optional.empty();
        }

        return Optional.of(response.body().trim().split("\\s+")[0]).filter(sum -> sum.length() == 40);
    }

    private HttpRequest request(final String path) {
        return HttpRequest.newBuilder(base.resolve(path))
            .timeout(Duration.ofMinutes(2))
            .GET()
            .build();
    }

    private static boolean isCoordinate(final String value) {
        return value != null && COORDINATE.matcher(value).matches() && !value.equals(".") && !value.equals("..");
    }

    private static Optional<String> text(
        final Element metadata,
        final String name
    ) {
        final var elements = metadata.getElementsByTagName(name);

        return elements.getLength() == 0
            ? Optional.empty()
            : Optional.of(elements.item(0).getTextContent().trim()).filter(value -> !value.isEmpty());
    }

    private static MessageDigest sha1() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 is not available", e);
        }
    }
}
