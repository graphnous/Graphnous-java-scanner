package dev.graphnous.scanner.maven;

import dev.graphnous.scanner.ScanLog;
import dev.graphnous.scanner.model.Dependency;
import dev.graphnous.scanner.model.Module;
import dev.graphnous.scanner.model.ScanTarget;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class MavenModuleDetectorTest {

    private final MavenModuleDetector detector = new MavenModuleDetector();

    @TempDir
    Path repository;

    @Test
    void handlesMaven() {
        assertThat(detector.forSystem()).isEqualTo(ScanTarget.BuildSystem.MAVEN);
    }

    @Test
    void detectsEveryPomBelowTheTarget() throws IOException {
        pom(repository.resolve("backend"));
        pom(repository.resolve("backend/core"));
        pom(repository.resolve("backend/services/api"));
        pom(repository.resolve("other"));

        final var modules = detector.detectModules(repository, target("backend"));

        assertThat(modules)
            .extracting(Module::getPath)
            .containsExactlyInAnyOrder(
                ".",
                "core",
                Path.of("services", "api").toString()
            );

        assertThat(modules).allSatisfy(module ->
            assertThat(module.getFiles()).isEmpty()
        );
    }

    @Test
    void usesDotForTheRootModule() throws IOException {
        pom(repository);

        assertThat(detector.detectModules(repository, target(".")))
            .extracting(Module::getPath)
            .containsExactly(".");
    }

    @Test
    void detectsModulesOfTheDotTarget() throws IOException {
        pom(repository);
        pom(repository.resolve("core"));

        assertThat(detector.detectModules(repository, target(".")))
            .extracting(Module::getPath)
            .containsExactlyInAnyOrder(".", "core");
    }

    @Test
    void ignoresPomsInBuildOutput() throws IOException {
        pom(repository.resolve("backend"));
        pom(repository.resolve("backend/target/generated-project"));
        pom(repository.resolve("backend/core"));
        pom(repository.resolve("backend/core/target/it/sample"));

        assertThat(detector.detectModules(repository, target("backend")))
            .extracting(Module::getPath)
            .containsExactlyInAnyOrder(".", "core");
    }

    @Test
    void namesModulesAfterTheirArtifactIdOrDirectory() throws IOException {
        Files.createDirectories(repository.resolve("core"));
        Files.writeString(repository.resolve("pom.xml"), "<project><artifactId>shop</artifactId></project>");
        pom(repository.resolve("core"));

        assertThat(detector.detectModules(repository, target(".")))
            .extracting(Module::getPath, Module::getName)
            .containsExactlyInAnyOrder(
                tuple(".", "shop"),
                tuple("core", "core")
            );
    }

    @Test
    void setsTheDependenciesOfEachModule() throws IOException {
        Files.writeString(repository.resolve("pom.xml"), """
            <project>
                <groupId>com.example</groupId>
                <artifactId>shop</artifactId>
                <version>1.0.0</version>
                <dependencies>
                    <dependency>
                        <groupId>com.example</groupId>
                        <artifactId>domain</artifactId>
                        <version>${project.version}</version>
                    </dependency>
                    <dependency>
                        <groupId>org.junit.jupiter</groupId>
                        <artifactId>junit-jupiter</artifactId>
                        <scope>test</scope>
                    </dependency>
                </dependencies>
            </project>
            """);

        assertThat(detector.detectModules(repository, target(".")))
            .singleElement()
            .satisfies(module -> assertThat(module.getDependencies())
                .extracting(Dependency::getName, Dependency::getVersion, Dependency::getScope)
                .containsExactly(
                    tuple("com.example:domain", "1.0.0", "compile"),
                    tuple("org.junit.jupiter:junit-jupiter", null, "test")
                ));
    }

    @Test
    void keepsModulesWithAPomItCannotRead() throws IOException {
        Files.createDirectories(repository.resolve("broken"));
        Files.writeString(repository.resolve("broken/pom.xml"), "not xml");

        final var warnings = new ArrayList<String>();
        final var log = new ScanLog() {
            @Override
            public void info(final String line) {
            }

            @Override
            public void detail(final String line) {
            }

            @Override
            public void warn(final String line) {
                warnings.add(line);
            }
        };

        assertThat(new MavenModuleDetector(log).detectModules(repository, target(".")))
            .extracting(Module::getPath, Module::getName)
            .containsExactly(tuple("broken", "broken"));

        assertThat(warnings).singleElement(InstanceOfAssertFactories.STRING)
            .startsWith("Could not read " + Path.of("broken", "pom.xml") + ": Content is not allowed in prolog");
    }

    private static ScanTarget target(final String path) {
        final var target = new ScanTarget();
        target.setPath(path);
        target.setLanguage(ScanTarget.Language.JAVA);
        target.setBuildSystem(ScanTarget.BuildSystem.MAVEN);

        return target;
    }

    private static void pom(final Path directory) throws IOException {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("pom.xml"), "<project/>");
    }
}
