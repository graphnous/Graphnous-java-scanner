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
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A remote Maven repository, such as Maven Central, to download the poms
 * and jars of the libraries of a project from. Only files are downloaded:
 * no Maven and no plugin of the project runs.
 * <p>
 * The headers of the repository, e.g. its credentials (see
 * {@link MavenSettings}), are sent to it alone: a redirect to another host,
 * as repositories do to the storage of their files, is followed without
 * them.
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

    private static final int MAX_REDIRECTS = 5;

    private final String id;

    private final URI base;

    private final Map<String, String> headers;

    private final HttpClient client;

    public MavenRemote(final URI base) {
        this("central", base, Map.of());
    }

    /**
     * @param headers sent with each request to the repository, e.g.
     *                {@code Authorization}
     */
    public MavenRemote(
        final String id,
        final URI base,
        final Map<String, String> headers
    ) {
        this.id = id;
        this.base = base.toString().endsWith("/") ? base : URI.create(base + "/");
        this.headers = Map.copyOf(headers);
        this.client = HttpClient.newBuilder()
            .proxy(ProxySelector.getDefault())
            // Followed here, to leave the headers out for other hosts
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    public String id() {
        return id;
    }

    public URI base() {
        return base;
    }

    Map<String, String> headers() {
        return headers;
    }

    /**
     * Whether requests carry credentials or other headers.
     */
    public boolean hasHeaders() {
        return !headers.isEmpty();
    }

    @Override
    public String toString() {
        // Without the user info a URL may hold
        return id + " (" + base.getScheme() + "://" + base.getHost() + (base.getPort() < 0 ? "" : ":" + base.getPort()) + base.getPath() + ")";
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
            final var response = send(
                groupId.replace('.', '/') + "/" + artifactId + "/maven-metadata.xml",
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
        final var response = send(path, HttpResponse.BodyHandlers.ofInputStream());

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
        final var response = send(path + ".sha1", HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            return Optional.empty();
        }

        return Optional.of(response.body().trim().split("\\s+")[0]).filter(sum -> sum.length() == 40);
    }

    /**
     * Requests the file of the repository, following redirects; the
     * headers of the repository go only to its own scheme, host and port.
     */
    private <T> HttpResponse<T> send(
        final String path,
        final HttpResponse.BodyHandler<T> body
    ) throws IOException, InterruptedException {
        var uri = base.resolve(path);

        for (int redirects = 0; ; redirects++) {
            final var request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMinutes(2))
                .GET();

            if (isSameOrigin(uri)) {
                headers.forEach(request::header);
            }

            final var response = client.send(request.build(), body);
            final var location = response.headers().firstValue("Location");

            if (response.statusCode() / 100 != 3 || location.isEmpty() || redirects == MAX_REDIRECTS) {
                return response;
            }

            if (response.body() instanceof InputStream stream) {
                stream.close();
            }

            uri = uri.resolve(location.get());
        }
    }

    private boolean isSameOrigin(final URI uri) {
        return base.getScheme().equalsIgnoreCase(uri.getScheme())
            && base.getHost() != null && base.getHost().equalsIgnoreCase(uri.getHost())
            && port(base) == port(uri);
    }

    private static int port(final URI uri) {
        return uri.getPort() >= 0 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
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
