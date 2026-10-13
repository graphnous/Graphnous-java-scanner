package dev.graphnous.scanner.java.maven;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class MavenRemoteTest {

    @TempDir
    Path directory;

    private HttpServer server;

    /**
     * The files the server was asked for, by their path.
     */
    private final Map<String, Integer> requests = new ConcurrentHashMap<>();

    /**
     * Files whose checksum the server gets wrong.
     */
    private final Set<String> corrupt = ConcurrentHashMap.newKeySet();

    private LocalRepository remote;

    @BeforeEach
    void startServer() throws IOException {
        remote = new LocalRepository(directory.resolve("remote/repository"));
        Files.createDirectories(remote.root());

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/maven2/", exchange -> {
            final var path = exchange.getRequestURI().getPath().substring("/maven2/".length());
            requests.merge(path, 1, Integer::sum);

            final var checksum = path.endsWith(".sha1");
            final var file = remote.root().resolve(checksum ? path.substring(0, path.length() - 5) : path).normalize();

            if (!file.startsWith(remote.root()) || !Files.isRegularFile(file)) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }

            final var content = checksum
                ? sha1(corrupt.contains(path) ? new byte[] {1} : Files.readAllBytes(file)).getBytes()
                : Files.readAllBytes(file);

            exchange.sendResponseHeaders(200, content.length);
            exchange.getResponseBody().write(content);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void downloadsTheDependenciesAndTheDependenciesTheyPullIn() throws IOException {
        remote.pom("org.example", "web", "1.0", dependencies("org.example", "http", "2.0"));
        remote.jar("org.example", "web", "1.0", source("Web"));
        remote.pom("org.example", "http", "2.0", "");
        remote.jar("org.example", "http", "2.0", source("Http"));

        final var local = local();
        final var jars = MavenClasspath.of(List.of(module(dependencies("org.example", "web", "1.0"))), Set.of(), local);

        assertThat(jars).extracting(jar -> jar.getFileName().toString()).containsExactly("web-1.0.jar", "http-2.0.jar");
        assertThat(jars).allSatisfy(jar -> assertThat(jar).startsWith(local.root()).isRegularFile());
        assertThat(local.downloads()).isEqualTo(4);

        // Another scan finds them in the repository
        final var again = local();
        MavenClasspath.of(List.of(module(dependencies("org.example", "web", "1.0"))), Set.of(), again);

        assertThat(again.downloads()).isZero();
        assertThat(requests).containsEntry("org/example/web/1.0/web-1.0.jar", 1);
    }

    @Test
    void downloadsParentsAndImportedBomsToTellTheVersions() throws IOException {
        remote.pom("org.example", "platform", "5.0", """
            <packaging>pom</packaging>
            <dependencyManagement>
                <dependencies>
                    <dependency><groupId>org.example</groupId><artifactId>web</artifactId><version>1.0</version></dependency>
                </dependencies>
            </dependencyManagement>
            """);
        remote.pom("org.example", "starter-parent", "5.0", """
            <packaging>pom</packaging>
            <dependencyManagement>
                <dependencies>
                    <dependency>
                        <groupId>org.example</groupId><artifactId>platform</artifactId><version>5.0</version>
                        <type>pom</type><scope>import</scope>
                    </dependency>
                </dependencies>
            </dependencyManagement>
            """);
        remote.pom("org.example", "web", "1.0", "");
        remote.jar("org.example", "web", "1.0", source("Web"));

        final var local = local();
        final var module = MavenPom.read(pom("""
            <parent><groupId>org.example</groupId><artifactId>starter-parent</artifactId><version>5.0</version></parent>
            <dependencies><dependency><groupId>org.example</groupId><artifactId>web</artifactId></dependency></dependencies>
            """), local);

        assertThat(module.dependencies()).containsExactly(new MavenDependency("org.example", "web", "1.0", "compile"));
        assertThat(MavenClasspath.of(List.of(module), Set.of(), local))
            .extracting(jar -> jar.getFileName().toString())
            .containsExactly("web-1.0.jar");
    }

    @Test
    void takesTheLatestReleaseTheRemoteListsWhenThePomsDoNotTell() throws IOException {
        remote.pom("org.example", "web", "1.2", "");
        remote.jar("org.example", "web", "1.2", source("Web"));
        Files.writeString(remote.root().resolve("org/example/web/maven-metadata.xml"), """
            <metadata>
                <groupId>org.example</groupId>
                <artifactId>web</artifactId>
                <versioning><latest>1.3-SNAPSHOT</latest><release>1.2</release></versioning>
            </metadata>
            """);

        assertThat(MavenClasspath.of(List.of(module("<dependencies><dependency><groupId>org.example</groupId><artifactId>web</artifactId></dependency></dependencies>")), Set.of(), local()))
            .extracting(jar -> jar.getFileName().toString())
            .containsExactly("web-1.2.jar");
    }

    @Test
    void rejectsAFileWhoseChecksumDoesNotMatch() throws IOException {
        remote.pom("org.example", "web", "1.0", "");
        remote.jar("org.example", "web", "1.0", source("Web"));
        corrupt.add("org/example/web/1.0/web-1.0.jar.sha1");

        final var local = local();

        assertThat(MavenClasspath.of(List.of(module(dependencies("org.example", "web", "1.0"))), Set.of(), local)).isEmpty();
        assertThat(local.root().resolve("org/example/web/1.0/web-1.0.jar")).doesNotExist();
        assertThat(local.root().resolve("org/example/web/1.0")).isDirectoryNotContaining("glob:**.part");
    }

    @Test
    void asksForWhatTheRemoteDoesNotHaveOnce() throws IOException {
        final var local = local();

        assertThat(local.jar("org.example", "missing", "1.0")).isEmpty();
        assertThat(local.jar("org.example", "missing", "1.0")).isEmpty();
        assertThat(requests).containsEntry("org/example/missing/1.0/missing-1.0.jar", 1);
    }

    @Test
    void downloadsNothingForCoordinatesThatAreNotAPath() {
        final var local = local();

        assertThat(local.jar("org.example", "web", "../../../etc/passwd")).isEmpty();
        assertThat(local.jar("org.example", "web", "1.0?x=1")).isEmpty();
        assertThat(requests).isEmpty();
    }

    @Test
    void sendsTheCredentialsToTheRepositoryAloneAndNotWhereItRedirects() throws IOException {
        remote.pom("org.example", "web", "1.0", "");
        remote.jar("org.example", "web", "1.0", source("Web"));

        final var authorizations = new ConcurrentHashMap<String, String>();

        // The storage serves the files of the repository, which redirects
        // there, as repositories do to the storage of their files
        final var storage = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        storage.createContext("/", exchange -> {
            authorizations.put("storage", String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            final var file = remote.root().resolve(exchange.getRequestURI().getPath().substring(1));

            if (Files.isRegularFile(file)) {
                final var content = Files.readAllBytes(file);
                exchange.sendResponseHeaders(200, content.length);
                exchange.getResponseBody().write(content);
            } else {
                exchange.sendResponseHeaders(404, -1);
            }
            exchange.close();
        });
        storage.start();

        final var repository = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        repository.createContext("/private/", exchange -> {
            final var authorization = exchange.getRequestHeaders().getFirst("Authorization");
            authorizations.put("repository", String.valueOf(authorization));

            if (!"Basic c2Nhbm5lcjpzZWNyZXQ=".equals(authorization)) {
                exchange.sendResponseHeaders(401, -1);
            } else {
                exchange.getResponseHeaders().add(
                    "Location",
                    "http://127.0.0.1:" + storage.getAddress().getPort() + "/" + exchange.getRequestURI().getPath().substring("/private/".length())
                );
                exchange.sendResponseHeaders(302, -1);
            }
            exchange.close();
        });
        repository.start();

        try {
            final var local = MavenRepository.create(directory.resolve("local")).downloadingFrom(new MavenRemote(
                "private",
                URI.create("http://127.0.0.1:" + repository.getAddress().getPort() + "/private"),
                Map.of("Authorization", "Basic c2Nhbm5lcjpzZWNyZXQ=")
            ));

            assertThat(local.jar("org.example", "web", "1.0")).isPresent();
            assertThat(authorizations)
                .containsEntry("repository", "Basic c2Nhbm5lcjpzZWNyZXQ=")
                .containsEntry("storage", "null");
        } finally {
            repository.stop(0);
            storage.stop(0);
        }
    }

    @Test
    void downloadsFromTheFirstRemoteThatHasTheFile() throws IOException {
        remote.pom("org.example", "web", "1.0", "");
        remote.jar("org.example", "web", "1.0", source("Web"));

        final var empty = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final var asked = new ConcurrentHashMap<String, Integer>();
        empty.createContext("/", exchange -> {
            asked.merge(exchange.getRequestURI().getPath(), 1, Integer::sum);
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        empty.start();

        try {
            final var local = MavenRepository.create(directory.resolve("local")).downloadingFrom(List.of(
                new MavenRemote(URI.create("http://127.0.0.1:" + empty.getAddress().getPort() + "/")),
                new MavenRemote(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/maven2"))
            ));

            assertThat(local.jar("org.example", "web", "1.0")).isPresent();
            assertThat(asked).containsKey("/org/example/web/1.0/web-1.0.jar");
        } finally {
            empty.stop(0);
        }
    }

    private MavenRepository local() {
        return MavenRepository.create(directory.resolve("local"))
            .downloadingFrom(new MavenRemote(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/maven2")));
    }

    private MavenPom module(final String content) throws IOException {
        return MavenPom.read(pom(content), MavenRepository.NONE);
    }

    private Path pom(final String content) throws IOException {
        final var pom = directory.resolve("project/pom.xml");
        Files.createDirectories(pom.getParent());
        Files.writeString(pom, """
            <project>
                <groupId>com.example</groupId>
                <artifactId>shop</artifactId>
                <version>1.0</version>
                %s
            </project>
            """.formatted(content));

        return pom;
    }

    private static String dependencies(
        final String groupId,
        final String artifactId,
        final String version
    ) {
        return "<dependencies><dependency><groupId>" + groupId + "</groupId><artifactId>" + artifactId
            + "</artifactId><version>" + version + "</version></dependency></dependencies>";
    }

    private static Map<String, String> source(final String name) {
        return Map.of("org/example/" + name + ".java", "package org.example; public class " + name + " { }");
    }

    private static String sha1(final byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(content));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
