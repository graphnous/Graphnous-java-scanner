package dev.graphnous.scanner;

import dev.graphnous.scanner.java.maven.MavenRemote;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MainTest {

    @TempDir
    Path directory;

    @Test
    void readsTheLibraryArguments() {
        final var options = Main.parseArguments(new String[] {
            "--path", "repository",
            "--output", "out.json",
            "--classpath", "a.jar" + File.pathSeparator + "b.jar",
            "--maven-repository", "/m2",
            "--download-dependencies",
            "--maven-remote", "https://nexus.example.com/repository/maven"
        });

        assertThat(options.classpath()).containsExactly(Path.of("a.jar"), Path.of("b.jar"));
        assertThat(options.mavenRepository()).isEqualTo(Path.of("/m2"));
        assertThat(options.downloadDependencies()).isTrue();
        assertThat(options.mavenRemote()).isEqualTo(URI.create("https://nexus.example.com/repository/maven"));
    }

    @Test
    void downloadsFromMavenCentralOnlyWhenAskedTo() {
        final var options = Main.parseArguments(new String[] {"--path", "repository", "--output", "out.json"});

        assertThat(options.downloadDependencies()).isFalse();
        assertThat(options.mavenRemote()).isEqualTo(MavenRemote.MAVEN_CENTRAL);
        assertThat(options.classpath()).isEmpty();
    }

    @Test
    void createsTheMavenRepositoryToDownloadInto() {
        final var location = directory.resolve("m2");

        final var reading = Main.mavenRepository(options(location, false), new RecordingScanLog());
        assertThat(reading.exists()).isFalse();
        assertThat(location).doesNotExist();

        final var downloading = Main.mavenRepository(options(location, true), new RecordingScanLog());
        assertThat(downloading.exists()).isTrue();
        assertThat(location).isDirectory();
    }

    private static Main.Options options(final Path mavenRepository, final boolean download) {
        return new Main.Options(
            Path.of("repository"), ".", Path.of("out.json"), null,
            List.of(), mavenRepository, download, MavenRemote.MAVEN_CENTRAL, null, false
        );
    }
}
