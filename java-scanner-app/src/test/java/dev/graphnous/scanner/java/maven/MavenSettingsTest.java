package dev.graphnous.scanner.java.maven;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

class MavenSettingsTest {

    private static final Map<String, String> ENVIRONMENT = Map.of(
        "NEXUS_USER", "scanner",
        "NEXUS_PASSWORD", "secret",
        "GITLAB_TOKEN", "glpat-123"
    );

    @TempDir
    Path directory;

    @Test
    void downloadsFromTheMirrorOfAllRepositoriesWithItsCredentials() throws IOException {
        final var remotes = remotes("""
            <settings xmlns="http://maven.apache.org/SETTINGS/1.2.0">
                <servers>
                    <server>
                        <id>nexus</id>
                        <username>${env.NEXUS_USER}</username>
                        <password>${env.NEXUS_PASSWORD}</password>
                    </server>
                </servers>
                <mirrors>
                    <mirror>
                        <id>nexus</id>
                        <mirrorOf>*</mirrorOf>
                        <url>https://nexus.example.com/repository/maven-public/</url>
                    </mirror>
                </mirrors>
            </settings>
            """);

        assertThat(remotes)
            .extracting(MavenRemote::id, MavenRemote::base, MavenRemote::hasHeaders)
            .containsExactly(tuple("nexus", URI.create("https://nexus.example.com/repository/maven-public/"), true));
        assertThat(headers(remotes.getFirst()))
            .containsEntry("Authorization", "Basic " + Base64.getEncoder().encodeToString("scanner:secret".getBytes()));
    }

    @Test
    void downloadsFromTheRepositoriesOfTheActiveProfilesThenMavenCentral() throws IOException {
        final var remotes = remotes("""
            <settings>
                <servers>
                    <server>
                        <id>gitlab</id>
                        <configuration>
                            <httpHeaders>
                                <property>
                                    <name>Private-Token</name>
                                    <value>${env.GITLAB_TOKEN}</value>
                                </property>
                            </httpHeaders>
                        </configuration>
                    </server>
                </servers>
                <profiles>
                    <profile>
                        <id>company</id>
                        <repositories>
                            <repository><id>gitlab</id><url>https://gitlab.example.com/api/v4/packages/maven</url></repository>
                        </repositories>
                    </profile>
                    <profile>
                        <id>default</id>
                        <activation><activeByDefault>true</activeByDefault></activation>
                        <repositories>
                            <repository><id>releases</id><url>https://releases.example.com/maven</url></repository>
                        </repositories>
                    </profile>
                    <profile>
                        <id>inactive</id>
                        <repositories>
                            <repository><id>other</id><url>https://other.example.com/maven</url></repository>
                        </repositories>
                    </profile>
                </profiles>
                <activeProfiles>
                    <activeProfile>company</activeProfile>
                </activeProfiles>
            </settings>
            """);

        assertThat(remotes)
            .extracting(MavenRemote::id, remote -> remote.base().getHost(), MavenRemote::hasHeaders)
            .containsExactly(
                tuple("gitlab", "gitlab.example.com", true),
                tuple("releases", "releases.example.com", false),
                tuple("central", "repo.maven.apache.org", false)
            );
        assertThat(headers(remotes.getFirst())).containsExactly(Map.entry("Private-Token", "glpat-123"));
    }

    @Test
    void mirrorsTheRepositoriesItsMirrorOfNames() throws IOException {
        final var remotes = remotes("""
            <settings>
                <mirrors>
                    <mirror>
                        <id>internal</id>
                        <mirrorOf>external:*,!releases</mirrorOf>
                        <url>https://mirror.example.com/maven</url>
                    </mirror>
                </mirrors>
                <profiles>
                    <profile>
                        <id>company</id>
                        <activation><activeByDefault>true</activeByDefault></activation>
                        <repositories>
                            <repository><id>releases</id><url>https://releases.example.com/maven</url></repository>
                            <repository><id>local</id><url>http://localhost:8081/maven</url></repository>
                        </repositories>
                    </profile>
                </profiles>
            </settings>
            """);

        // Central goes to the mirror too, once
        assertThat(remotes)
            .extracting(MavenRemote::id)
            .containsExactly("releases", "local", "internal");
    }

    @Test
    void takesARepositoryWithTheIdOfCentralForMavenCentral() throws IOException {
        final var remotes = remotes("""
            <settings>
                <profiles>
                    <profile>
                        <id>company</id>
                        <activation><activeByDefault>true</activeByDefault></activation>
                        <repositories>
                            <repository><id>central</id><url>https://central-proxy.example.com/maven</url></repository>
                        </repositories>
                    </profile>
                </profiles>
            </settings>
            """);

        assertThat(remotes)
            .extracting(remote -> remote.base().getHost())
            .containsExactly("central-proxy.example.com");
    }

    @Test
    void keepsEnvironmentVariablesThatAreNotSet() {
        assertThat(MavenSettings.interpolate("${env.NEXUS_USER}:${env.MISSING}", ENVIRONMENT::get))
            .isEqualTo("scanner:${env.MISSING}");
    }

    @Test
    void failsOnSettingsThatCannotBeRead() throws IOException {
        final var settings = Files.writeString(directory.resolve("settings.xml"), "<settings>");

        assertThatThrownBy(() -> MavenSettings.remotes(settings, MavenRemote.MAVEN_CENTRAL, ENVIRONMENT::get))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Could not read the Maven settings");
    }

    @Test
    void readsNoExternalEntities() throws IOException {
        final var secret = Files.writeString(directory.resolve("secret.txt"), "https://attacker.example.com/");
        final var settings = Files.writeString(directory.resolve("settings.xml"), """
            <!DOCTYPE settings [<!ENTITY secret SYSTEM "%s">]>
            <settings>
                <mirrors>
                    <mirror><id>m</id><mirrorOf>*</mirrorOf><url>&secret;</url></mirror>
                </mirrors>
            </settings>
            """.formatted(secret.toUri()));

        assertThat(MavenSettings.remotes(settings, MavenRemote.MAVEN_CENTRAL, ENVIRONMENT::get))
            .extracting(remote -> remote.base().getHost())
            .doesNotContain("attacker.example.com");
    }

    private List<MavenRemote> remotes(final String content) throws IOException {
        return MavenSettings.remotes(
            Files.writeString(directory.resolve("settings.xml"), content),
            MavenRemote.MAVEN_CENTRAL,
            ENVIRONMENT::get
        );
    }

    private static Map<String, String> headers(final MavenRemote remote) {
        return remote.headers();
    }
}
