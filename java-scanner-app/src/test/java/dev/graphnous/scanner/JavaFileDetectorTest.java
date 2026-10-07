package dev.graphnous.scanner;

import dev.graphnous.core.model.Class;
import dev.graphnous.core.model.File;
import dev.graphnous.core.model.Module;
import dev.graphnous.core.model.Package;
import dev.graphnous.core.model.ScanTarget;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.assertj.core.api.Assertions.tuple;

class JavaFileDetectorTest {

    private final RecordingScanLog log = new RecordingScanLog();

    private final JavaFileDetector detector = new JavaFileDetector(new JavaClassParser(), log);

    @TempDir
    Path repository;

    @Test
    void detectsJavaFilesInTheModuleRelativeToTheTarget() throws IOException {
        write("backend/core/src/main/java/com/example/Service.java", """
            package com.example;
            public class Service { }
            """);
        write("backend/core/README.md", "# core");
        write("backend/api/src/main/java/com/example/Api.java", """
            package com.example;
            public class Api { }
            """);

        final var files = detector.detectFiles(repository, target("backend"), module("core"));

        assertThat(files).singleElement().satisfies(file -> {
            assertThat(file.getPath()).isEqualTo(
                Path.of("core", "src", "main", "java", "com", "example", "Service.java").toString()
            );
            assertThat(file.getLanguage()).isEqualTo("JAVA");
            assertThat(file.getClasses())
                .extracting(Class::getQualifiedName)
                .containsExactly("com.example.Service");
        });
    }

    @Test
    void returnsNoFilesForAModuleWithoutJavaSources() throws IOException {
        write("backend/pom.xml", "<project/>");

        assertThat(detector.detectFiles(repository, target("backend"), module("."))).isEmpty();
    }

    @Test
    void reportsPathsWithoutDotSegmentsForTheRootModule() throws IOException {
        write("pom.xml", "<project/>");
        write("src/main/java/Root.java", "class Root { }");

        assertThat(detector.detectFiles(repository, target("."), module(".")))
            .extracting(File::getPath)
            .containsExactly(Path.of("src", "main", "java", "Root.java").toString());
    }

    @Test
    void doesNotIncludeFilesOfSubModules() throws IOException {
        write("pom.xml", "<project/>");
        write("src/main/java/Root.java", "class Root { }");
        write("core/pom.xml", "<project/>");
        write("core/src/main/java/Core.java", "class Core { }");
        write("services/api/pom.xml", "<project/>");
        write("services/api/src/main/java/Api.java", "class Api { }");

        assertThat(detector.detectFiles(repository, target("."), module(".")))
            .extracting(File::getPath)
            .containsExactly(Path.of("src", "main", "java", "Root.java").toString());
    }

    @Test
    void includesFilesOfSubModulesInTheirOwnModule() throws IOException {
        write("pom.xml", "<project/>");
        write("core/pom.xml", "<project/>");
        write("core/src/main/java/Core.java", "class Core { }");
        write("core/plugin/pom.xml", "<project/>");
        write("core/plugin/src/main/java/Plugin.java", "class Plugin { }");

        assertThat(detector.detectFiles(repository, target("."), module("core")))
            .extracting(File::getPath)
            .containsExactly(Path.of("core", "src", "main", "java", "Core.java").toString());
    }

    @Test
    void treatsGradleProjectsAsSubModules() throws IOException {
        write("pom.xml", "<project/>");
        write("src/main/java/Root.java", "class Root { }");
        write("legacy/build.gradle", "");
        write("legacy/src/main/java/Legacy.java", "class Legacy { }");
        write("kotlin-dsl/build.gradle.kts", "");
        write("kotlin-dsl/src/main/java/Kts.java", "class Kts { }");

        assertThat(detector.detectFiles(repository, target("."), module(".")))
            .extracting(File::getPath)
            .containsExactly(Path.of("src", "main", "java", "Root.java").toString());
    }

    @Test
    void skipsFilesWithSyntaxErrors() throws IOException {
        write("pom.xml", "<project/>");
        write("src/main/java/Valid.java", "class Valid { }");
        write("src/main/java/Broken.java", "public class {");

        assertThat(detector.detectFiles(repository, target("."), module(".")))
            .extracting(File::getPath)
            .containsExactly(Path.of("src", "main", "java", "Valid.java").toString());
    }

    @Test
    void warnsAboutSkippedFilesWithTheirFirstProblem() throws IOException {
        write("pom.xml", "<project/>");
        write("src/main/java/Broken.java", "public class {");

        detector.detectFiles(repository, target("."), module("."));

        assertThat(log.warnings).containsExactly(
            "Skipping " + Path.of("src", "main", "java", "Broken.java") + ": (line 1,col 8) Parse error. Found \"{\""
        );
        assertThat(log.info).isEmpty();
    }

    @Test
    void reportsEachFileAsDetail() throws IOException {
        write("pom.xml", "<project/>");
        write("src/main/java/Types.java", "class A { } class B { }");

        detector.detectFiles(repository, target("."), module("."));

        assertThat(log.details).containsExactly(
            "  " + Path.of("src", "main", "java", "Types.java") + ": 2 classes"
        );
        assertThat(log.info).isEmpty();
    }

    @Test
    void skipsBuildOutputOfTheModule() throws IOException {
        write("pom.xml", "<project/>");
        write("src/main/java/Root.java", "class Root { }");
        write("target/generated-sources/jsonschema2pojo/Generated.java", "class Generated { }");
        write("build/generated/sources/Gradle.java", "class Gradle { }");

        assertThat(detector.detectFiles(repository, target("."), module(".")))
            .extracting(File::getPath)
            .containsExactly(Path.of("src", "main", "java", "Root.java").toString());
    }

    @Test
    void skipsBuildOutputOfANestedModulePath() throws IOException {
        write("backend/core/pom.xml", "<project/>");
        write("backend/core/src/main/java/Core.java", "class Core { }");
        write("backend/core/target/classes/Copied.java", "class Copied { }");

        assertThat(detector.detectFiles(repository, target("backend"), module("core")))
            .extracting(File::getPath)
            .containsExactly(Path.of("core", "src", "main", "java", "Core.java").toString());
    }

    @Test
    void keepsSourcePackagesNamedLikeOutputDirectories() throws IOException {
        write("pom.xml", "<project/>");
        write("src/main/java/com/example/build/Builder.java", "package com.example.build; class Builder { }");
        write("src/main/java/com/example/target/Target.java", "package com.example.target; class Target { }");

        assertThat(detector.detectFiles(repository, target("."), module(".")))
            .extracting(File::getPath)
            .containsExactlyInAnyOrder(
                Path.of("src", "main", "java", "com", "example", "build", "Builder.java").toString(),
                Path.of("src", "main", "java", "com", "example", "target", "Target.java").toString()
            );
    }

    @Test
    void includesPlainSubDirectoriesWithoutBuildFile() throws IOException {
        write("pom.xml", "<project/>");
        write("src/main/java/com/example/a/A.java", "package com.example.a; class A { }");
        write("src/main/java/com/example/b/B.java", "package com.example.b; class B { }");

        assertThat(detector.detectFiles(repository, target("."), module(".")))
            .extracting(File::getPath)
            .containsExactlyInAnyOrder(
                Path.of("src", "main", "java", "com", "example", "a", "A.java").toString(),
                Path.of("src", "main", "java", "com", "example", "b", "B.java").toString()
            );
    }

    @Test
    void setsTheSizeAndChecksumOfEachFile() throws IOException {
        write("pom.xml", "<project/>");
        write("src/main/java/Root.java", "class Root { }");

        assertThat(detector.detectFiles(repository, target("."), module(".")))
            .singleElement()
            .satisfies(file -> {
                assertThat(file.getSize()).isEqualTo(14);
                assertThat(file.getChecksum())
                    .hasSize(64)
                    .isEqualTo(sha256("class Root { }"));
            });
    }

    @Test
    void setsThePackagesOfTheModuleAndThePackageOfEachFile() throws IOException {
        write("pom.xml", "<project/>");
        write("src/main/java/com/example/web/Api.java", "package com.example.web; class Api { }");
        write("src/main/java/com/example/Order.java", "package com.example; record Order(String id) { record Line(int quantity) { } }");
        write("src/main/java/com/example/Status.java", "package com.example; enum Status { OPEN }");
        write("src/main/java/Script.java", "class Script { }");

        final var module = module(".");
        detector.detect(repository, target("."), module, JavaTypeIndex.EMPTY);

        assertThat(module.getFiles()).hasSize(4);

        assertThat(module.getPackages())
            .extracting(Package::getName, Package::getQualifiedName)
            .containsExactly(
                tuple("example", "com.example"),
                tuple("web", "com.example.web")
            );

        assertThat(module.getFiles())
            .extracting(File::getPath, File::getPackage)
            .containsExactlyInAnyOrder(
                tuple(Path.of("src/main/java/com/example/web/Api.java").toString(), "com.example.web"),
                tuple(Path.of("src/main/java/com/example/Order.java").toString(), "com.example"),
                tuple(Path.of("src/main/java/com/example/Status.java").toString(), "com.example"),
                tuple(Path.of("src/main/java/Script.java").toString(), null)
            );
    }

    private static String sha256(final String content) {
        try {
            return java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8))
            );
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void skipsFilesItCannotReadWithAWarning() throws IOException {
        assumeTrue(
            repository.getFileSystem().supportedFileAttributeViews().contains("posix"),
            "needs POSIX file permissions"
        );

        write("pom.xml", "<project/>");
        write("src/main/java/A.java", "class A { }");
        write("src/main/java/B.java", "class B { }");
        write("src/main/java/C.java", "class C { }");

        final var unreadable = repository.resolve("src/main/java/B.java");
        Files.setPosixFilePermissions(unreadable, Set.of());

        try {
            assumeTrue(!Files.isReadable(unreadable), "runs as a user that can read any file");

            assertThat(detector.detectFiles(repository, target("."), module(".")))
                .extracting(File::getPath)
                .containsExactlyInAnyOrder(
                    Path.of("src", "main", "java", "A.java").toString(),
                    Path.of("src", "main", "java", "C.java").toString()
                );

            assertThat(log.warnings).singleElement(InstanceOfAssertFactories.STRING)
                .startsWith("Skipping " + Path.of("src", "main", "java", "B.java") + ": could not read it");
        } finally {
            Files.setPosixFilePermissions(unreadable, PosixFilePermissions.fromString("rw-------"));
        }
    }

    @Test
    void reportsFilesInTheirOrder() throws IOException {
        write("pom.xml", "<project/>");

        for (var i = 0; i < 50; i++) {
            write("src/main/java/T" + i + ".java", "class T" + i + " { }");
        }

        final var files = detector.detectFiles(repository, target("."), module("."));

        assertThat(log.details)
            .containsExactlyElementsOf(files.stream().map(file -> "  " + file.getPath() + ": 1 class").toList());
    }

    @Test
    void tellsProductionCodeFromTestCode() throws IOException {
        write("backend/core/pom.xml", "<project/>");
        write("backend/core/src/main/java/com/example/Service.java", "package com.example; class Service { }");
        write("backend/core/src/test/java/com/example/ServiceTest.java", "package com.example; class ServiceTest { }");

        assertThat(detector.detectFiles(repository, target("backend"), module("core")))
            .extracting(file -> file.getClasses().getFirst().getName(), File::getSourceSet)
            .containsExactlyInAnyOrder(
                tuple("Service", File.SourceSet.MAIN),
                tuple("ServiceTest", File.SourceSet.TEST)
            );
    }

    @Test
    void treatsOtherTestSourceSetsAsTestCode() throws IOException {
        write("build.gradle", "");
        write("src/integrationTest/java/Flow.java", "class Flow { }");
        write("src/testFixtures/java/Fixture.java", "class Fixture { }");

        assertThat(detector.detectFiles(repository, target("."), module(".")))
            .extracting(File::getSourceSet)
            .containsOnly(File.SourceSet.TEST)
            .hasSize(2);
    }

    @Test
    void leavesTheSourceSetOutWhenItCannotTell() throws IOException {
        write("pom.xml", "<project/>");
        write("Root.java", "class Root { }");
        write("src/Loose.java", "class Loose { }");
        write("src/generated/java/Generated.java", "class Generated { }");
        write("scripts/src/main/java/Script.java", "class Script { }");

        assertThat(detector.detectFiles(repository, target("."), module(".")))
            .extracting(file -> file.getClasses().getFirst().getName(), File::getSourceSet)
            .containsExactlyInAnyOrder(
                tuple("Root", null),
                tuple("Loose", null),
                tuple("Generated", null),
                tuple("Script", null)
            );
    }

    @Test
    void usesTheSourceDirectoriesThePomConfigures() throws IOException {
        write("core/pom.xml", """
            <project>
                <build>
                    <sourceDirectory>src</sourceDirectory>
                    <testSourceDirectory>test</testSourceDirectory>
                    <plugins>
                        <plugin>
                            <artifactId>build-helper-maven-plugin</artifactId>
                            <executions>
                                <execution>
                                    <goals><goal>add-test-source</goal></goals>
                                    <configuration>
                                        <sources><source>src/it</source></sources>
                                    </configuration>
                                </execution>
                            </executions>
                        </plugin>
                    </plugins>
                </build>
            </project>
            """);
        write("core/src/com/example/Service.java", "package com.example; class Service { }");
        write("core/test/com/example/ServiceTest.java", "package com.example; class ServiceTest { }");
        // Nested in the source directory, but the deeper test directory decides
        write("core/src/it/com/example/FlowIT.java", "package com.example; class FlowIT { }");
        // In the source directory, so production code as Maven compiles it,
        // despite the conventional test path
        write("core/src/test/java/com/example/Legacy.java", "package com.example; class Legacy { }");

        assertThat(detector.detectFiles(repository, target("."), module("core")))
            .extracting(file -> file.getClasses().getFirst().getName(), File::getSourceSet)
            .containsExactlyInAnyOrder(
                tuple("Service", File.SourceSet.MAIN),
                tuple("ServiceTest", File.SourceSet.TEST),
                tuple("FlowIT", File.SourceSet.TEST),
                tuple("Legacy", File.SourceSet.MAIN)
            );
    }

    private void write(final String path, final String content) throws IOException {
        final var file = repository.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static ScanTarget target(final String path) {
        final var target = new ScanTarget();
        target.setPath(path);

        return target;
    }

    private static Module module(final String path) {
        final var module = new Module();
        module.setPath(path);

        return module;
    }
}
