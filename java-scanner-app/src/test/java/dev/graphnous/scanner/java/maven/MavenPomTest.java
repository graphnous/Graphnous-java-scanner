package dev.graphnous.scanner.java.maven;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MavenPomTest {

    @TempDir
    Path repository;

    @Test
    void readsTheCoordinatesOfTheProject() throws IOException {
        final var pom = pom(repository, """
            <project>
                <groupId>com.example</groupId>
                <artifactId>shop</artifactId>
                <version>1.2.0</version>
            </project>
            """);

        final var mavenPom = MavenPom.read(pom);

        assertThat(mavenPom.groupId()).contains("com.example");
        assertThat(mavenPom.artifactId()).contains("shop");
        assertThat(mavenPom.version()).contains("1.2.0");
    }

    @Test
    void inheritsGroupAndVersionFromTheParent() throws IOException {
        pom(repository, parent());
        final var pom = pom(repository.resolve("core"), """
            <project>
                <parent>
                    <groupId>com.example</groupId>
                    <artifactId>shop</artifactId>
                    <version>1.2.0</version>
                </parent>
                <artifactId>core</artifactId>
            </project>
            """);

        final var mavenPom = MavenPom.read(pom);

        assertThat(mavenPom.groupId()).contains("com.example");
        assertThat(mavenPom.artifactId()).contains("core");
        assertThat(mavenPom.version()).contains("1.2.0");
    }

    @Test
    void readsDependenciesWithTheirVersionAndScope() throws IOException {
        final var pom = pom(repository, """
            <project>
                <groupId>com.example</groupId>
                <artifactId>shop</artifactId>
                <version>1.2.0</version>
                <properties>
                    <jackson.version>2.18.0</jackson.version>
                </properties>
                <dependencies>
                    <dependency>
                        <groupId>com.fasterxml.jackson.core</groupId>
                        <artifactId>jackson-databind</artifactId>
                        <version>${jackson.version}</version>
                    </dependency>
                    <dependency>
                        <groupId>${project.groupId}</groupId>
                        <artifactId>domain</artifactId>
                        <version>${project.version}</version>
                    </dependency>
                    <dependency>
                        <groupId>org.junit.jupiter</groupId>
                        <artifactId>junit-jupiter</artifactId>
                        <version>5.11.0</version>
                        <scope>test</scope>
                    </dependency>
                </dependencies>
            </project>
            """);

        assertThat(MavenPom.read(pom).dependencies()).containsExactly(
            new MavenDependency("com.fasterxml.jackson.core", "jackson-databind", "2.18.0", "compile"),
            new MavenDependency("com.example", "domain", "1.2.0", "compile"),
            new MavenDependency("org.junit.jupiter", "junit-jupiter", "5.11.0", "test")
        );
    }

    @Test
    void takesVersionAndScopeFromTheDependencyManagementOfTheParent() throws IOException {
        pom(repository, """
            <project>
                <groupId>com.example</groupId>
                <artifactId>shop</artifactId>
                <version>1.2.0</version>
                <properties>
                    <lombok.version>1.18.34</lombok.version>
                </properties>
                <dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                            <version>${lombok.version}</version>
                            <scope>provided</scope>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
            </project>
            """);
        final var pom = pom(repository.resolve("core"), """
            <project>
                <parent>
                    <groupId>com.example</groupId>
                    <artifactId>shop</artifactId>
                    <version>1.2.0</version>
                </parent>
                <artifactId>core</artifactId>
                <properties>
                    <lombok.version>1.18.36</lombok.version>
                </properties>
                <dependencies>
                    <dependency>
                        <groupId>org.projectlombok</groupId>
                        <artifactId>lombok</artifactId>
                    </dependency>
                </dependencies>
            </project>
            """);

        assertThat(MavenPom.read(pom).dependencies()).containsExactly(
            new MavenDependency("org.projectlombok", "lombok", "1.18.36", "provided")
        );
    }

    @Test
    void inheritsDependenciesOfTheParentUnlessTheModuleDeclaresThem() throws IOException {
        pom(repository, """
            <project>
                <groupId>com.example</groupId>
                <artifactId>shop</artifactId>
                <version>1.2.0</version>
                <dependencies>
                    <dependency>
                        <groupId>org.slf4j</groupId>
                        <artifactId>slf4j-api</artifactId>
                        <version>2.0.0</version>
                    </dependency>
                    <dependency>
                        <groupId>org.junit.jupiter</groupId>
                        <artifactId>junit-jupiter</artifactId>
                        <version>5.10.0</version>
                        <scope>test</scope>
                    </dependency>
                </dependencies>
            </project>
            """);
        final var pom = pom(repository.resolve("core"), """
            <project>
                <parent>
                    <groupId>com.example</groupId>
                    <artifactId>shop</artifactId>
                    <version>1.2.0</version>
                </parent>
                <artifactId>core</artifactId>
                <dependencies>
                    <dependency>
                        <groupId>org.junit.jupiter</groupId>
                        <artifactId>junit-jupiter</artifactId>
                        <version>5.11.0</version>
                        <scope>test</scope>
                    </dependency>
                </dependencies>
            </project>
            """);

        assertThat(MavenPom.read(pom).dependencies()).containsExactly(
            new MavenDependency("org.junit.jupiter", "junit-jupiter", "5.11.0", "test"),
            new MavenDependency("org.slf4j", "slf4j-api", "2.0.0", "compile")
        );
    }

    @Test
    void leavesOutVersionsItCannotResolve() throws IOException {
        final var pom = pom(repository, """
            <project>
                <parent>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-starter-parent</artifactId>
                    <version>3.4.0</version>
                    <relativePath/>
                </parent>
                <artifactId>shop</artifactId>
                <dependencies>
                    <dependency>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-web</artifactId>
                    </dependency>
                    <dependency>
                        <groupId>com.example</groupId>
                        <artifactId>missing</artifactId>
                        <version>${missing.version}</version>
                    </dependency>
                </dependencies>
            </project>
            """);

        final var mavenPom = MavenPom.read(pom);

        assertThat(mavenPom.version()).contains("3.4.0");
        assertThat(mavenPom.dependencies()).containsExactly(
            new MavenDependency("org.springframework.boot", "spring-boot-starter-web", null, "compile"),
            new MavenDependency("com.example", "missing", null, "compile")
        );
    }

    @Test
    void findsTheFirstOfThePropertiesNearestFirst() throws IOException {
        pom(repository, """
            <project>
                <properties>
                    <maven.compiler.release>21</maven.compiler.release>
                </properties>
            </project>
            """);
        final var pom = pom(repository.resolve("core"), """
            <project>
                <parent>
                    <artifactId>shop</artifactId>
                </parent>
                <properties>
                    <maven.compiler.source>17</maven.compiler.source>
                </properties>
            </project>
            """);

        assertThat(MavenPom.read(pom).firstProperty(List.of("maven.compiler.release", "maven.compiler.source")))
            .contains("17");
    }

    @Test
    void failsOnAPomThatIsNotXml() throws IOException {
        final var pom = pom(repository, "not xml");

        assertThatThrownBy(() -> MavenPom.read(pom))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageStartingWith("Failed to read " + pom);
    }

    @Test
    void hasNoSourceDirectoriesWhenItConfiguresNone() throws IOException {
        final var mavenPom = MavenPom.read(pom(repository, parent()));

        assertThat(mavenPom.sourceDirectories()).isEmpty();
        assertThat(mavenPom.testSourceDirectories()).isEmpty();
    }

    @Test
    void readsTheSourceDirectoriesRelativeToThePom() throws IOException {
        final var pom = pom(repository.resolve("core"), """
            <project>
                <properties>
                    <sources>java</sources>
                </properties>
                <build>
                    <sourceDirectory>${sources}</sourceDirectory>
                    <testSourceDirectory>${project.basedir}/tests</testSourceDirectory>
                </build>
            </project>
            """);

        final var mavenPom = MavenPom.read(pom);
        final var core = repository.resolve("core").toAbsolutePath();

        assertThat(mavenPom.sourceDirectories()).containsExactly(core.resolve("java"));
        assertThat(mavenPom.testSourceDirectories()).containsExactly(core.resolve("tests"));
    }

    @Test
    void inheritsTheSourceDirectoriesRelativeToTheModule() throws IOException {
        pom(repository, """
            <project>
                <groupId>com.example</groupId>
                <artifactId>shop</artifactId>
                <version>1.2.0</version>
                <build>
                    <sourceDirectory>src</sourceDirectory>
                    <testSourceDirectory>test</testSourceDirectory>
                </build>
            </project>
            """);
        final var pom = pom(repository.resolve("core"), """
            <project>
                <parent>
                    <groupId>com.example</groupId>
                    <artifactId>shop</artifactId>
                    <version>1.2.0</version>
                </parent>
                <artifactId>core</artifactId>
                <build>
                    <testSourceDirectory>spec</testSourceDirectory>
                </build>
            </project>
            """);

        final var mavenPom = MavenPom.read(pom);
        final var core = repository.resolve("core").toAbsolutePath();

        assertThat(mavenPom.sourceDirectories()).containsExactly(core.resolve("src"));
        assertThat(mavenPom.testSourceDirectories()).containsExactly(core.resolve("spec"));
    }

    @Test
    void readsTheSourceDirectoriesAddedByTheBuildHelperPlugin() throws IOException {
        final var pom = pom(repository, """
            <project>
                <build>
                    <plugins>
                        <plugin>
                            <groupId>org.codehaus.mojo</groupId>
                            <artifactId>build-helper-maven-plugin</artifactId>
                            <executions>
                                <execution>
                                    <goals><goal>add-source</goal></goals>
                                    <configuration>
                                        <sources>
                                            <source>src/extra/java</source>
                                            <source>${project.build.directory}/generated</source>
                                        </sources>
                                    </configuration>
                                </execution>
                                <execution>
                                    <goals><goal>add-test-source</goal></goals>
                                    <configuration>
                                        <sources><source>src/it/java</source></sources>
                                    </configuration>
                                </execution>
                            </executions>
                        </plugin>
                    </plugins>
                </build>
            </project>
            """);

        final var mavenPom = MavenPom.read(pom);
        final var root = repository.toAbsolutePath();

        // ${project.build.directory} is not known, so that directory is left out
        assertThat(mavenPom.sourceDirectories()).containsExactly(root.resolve("src/extra/java"));
        assertThat(mavenPom.testSourceDirectories()).containsExactly(root.resolve("src/it/java"));
    }

    private static String parent() {
        return """
            <project>
                <groupId>com.example</groupId>
                <artifactId>shop</artifactId>
                <version>1.2.0</version>
            </project>
            """;
    }

    private static Path pom(final Path directory, final String content) throws IOException {
        Files.createDirectories(directory);
        final var pom = directory.resolve("pom.xml");
        Files.writeString(pom, content);

        return pom;
    }
}
