package dev.graphnous.scanner;

import dev.graphnous.scanner.java.maven.LocalRepository;
import dev.graphnous.scanner.java.maven.MavenRepository;
import dev.graphnous.scanner.model.Annotation;
import dev.graphnous.scanner.model.Class;
import dev.graphnous.scanner.model.File;
import dev.graphnous.scanner.model.Method;
import dev.graphnous.scanner.model.Module;
import dev.graphnous.scanner.model.ScanTarget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class JavaScannerTest {

    private final JavaScanner scanner = new JavaScanner();

    @TempDir
    Path repository;

    @TempDir
    Path home;

    @Test
    void scansModulesAndTheirClasses() throws IOException {
        write("backend/pom.xml", "<project/>");
        write("backend/core/pom.xml", "<project/>");
        write("backend/core/src/main/java/com/example/Order.java", """
            package com.example;
            public record Order(String id) { }
            """);

        final var target = new ScanTarget();
        target.setPath("backend");
        target.setLanguage(ScanTarget.Language.JAVA);
        target.setBuildSystem(ScanTarget.BuildSystem.MAVEN);

        final var result = scanner.scan(repository, target);

        assertThat(result.getFormat()).isEqualTo("graphnous-scan-result");
        assertThat(result.getVersion()).isEqualTo("2");
        assertThat(result.getTarget()).isSameAs(target);

        assertThat(result.getModules())
            .extracting(Module::getPath)
            .containsExactlyInAnyOrder(".", "core");

        final var root = module(result.getModules(), ".");
        final var core = module(result.getModules(), "core");

        assertThat(root.getFiles()).isEmpty();

        assertThat(core.getFiles())
            .flatExtracting(File::getClasses)
            .extracting(Class::getQualifiedName, Class::getKind)
            .containsExactly(
                tuple("com.example.Order", Class.Kind.RECORD)
            );
    }

    @Test
    void resolvesTypesDeclaredInOtherModules() throws IOException {
        write("pom.xml", "<project/>");
        write("domain/pom.xml", "<project/>");
        write("domain/src/main/java/com/example/domain/Order.java", """
            package com.example.domain;
            public record Order(String id) { }
            """);
        write("api/pom.xml", "<project/>");
        write("api/src/main/java/com/example/api/OrderApi.java", """
            package com.example.api;

            import com.example.domain.*;
            import org.springframework.web.bind.annotation.*;

            @RestController
            class OrderApi {
                @PostMapping
                void create(@RequestBody Order order) { }
            }
            """);

        final var target = new ScanTarget();
        target.setPath(".");
        target.setLanguage(ScanTarget.Language.JAVA);
        target.setBuildSystem(ScanTarget.BuildSystem.MAVEN);

        final var api = module(scanner.scan(repository, target).getModules(), "api");

        assertThat(api.getFiles())
            .flatExtracting(File::getClasses)
            .singleElement()
            .satisfies(clazz -> {
                assertThat(clazz.getAnnotations())
                    .extracting(Annotation::getQualifiedName)
                    .containsExactly("org.springframework.web.bind.annotation.RestController");
                assertThat(clazz.getMethods())
                    .extracting(Method::getQualifiedName)
                    .containsExactly("com.example.api.OrderApi.create(com.example.domain.Order)");
            });
    }

    @Test
    void takesTheValuesOfConstantsOfOtherClassesAndModulesInAnnotations() throws IOException {
        write("pom.xml", "<project/>");
        write("domain/pom.xml", "<project/>");
        write("domain/src/main/java/com/example/domain/Paths.java", """
            package com.example.domain;
            public interface Paths {
                String API = "/api/v1";
                String ORDERS = API + "/orders";
            }
            """);
        write("api/pom.xml", "<project/>");
        write("api/src/main/java/com/example/api/OrderApi.java", """
            package com.example.api;

            import com.example.domain.Paths;
            import org.springframework.web.bind.annotation.*;

            import static com.example.domain.Paths.API;

            @RestController
            @RequestMapping(Paths.ORDERS)
            class OrderApi {
                static final String CALLBACK = "/callback";

                @GetMapping(CALLBACK)
                void callback() { }

                @PostMapping(API + "/orders/{id}" + CALLBACK)
                void notify(String id) { }

                @GetMapping(Unknown.PATH)
                void unknown() { }
            }
            """);

        final var target = new ScanTarget();
        target.setPath(".");
        target.setLanguage(ScanTarget.Language.JAVA);
        target.setBuildSystem(ScanTarget.BuildSystem.MAVEN);

        final var api = module(scanner.scan(repository, target).getModules(), "api");
        final var clazz = api.getFiles().getFirst().getClasses().getFirst();

        assertThat(clazz.getAnnotations().get(1).getArguments().getAdditionalProperties())
            .containsEntry("value", "/api/v1/orders");
        assertThat(clazz.getMethods())
            .extracting(method -> method.getAnnotations().getFirst().getArguments().getAdditionalProperties().get("value"))
            .containsExactly(
                "/callback",
                "/api/v1/orders/{id}/callback",
                // A constant the scan does not have keeps its reference, of a class taken to be in the
                // one library package imported on demand
                "org.springframework.web.bind.annotation.Unknown.PATH"
            );
    }

    @Test
    void setsTheMavenVersionOfTheWrapper() throws IOException {
        write("pom.xml", "<project/>");
        write(".mvn/wrapper/maven-wrapper.properties",
            "distributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.11/apache-maven-3.9.11-bin.zip");

        final var target = new ScanTarget();
        target.setPath(".");
        target.setLanguage(ScanTarget.Language.JAVA);
        target.setBuildSystem(ScanTarget.BuildSystem.MAVEN);

        assertThat(scanner.scan(repository, target).getTarget().getBuildSystemVersion())
            .isEqualTo("3.9.11");
    }

    @Test
    void keepsScanningWhenAFileIsBroken() throws IOException {
        write("pom.xml", "<project/>");
        write("src/main/java/com/example/Valid.java", """
            package com.example;
            public class Valid { }
            """);
        write("src/main/java/com/example/Broken.java", "public class {");

        final var target = new ScanTarget();
        target.setPath(".");
        target.setLanguage(ScanTarget.Language.JAVA);
        target.setBuildSystem(ScanTarget.BuildSystem.MAVEN);

        final var result = scanner.scan(repository, target);

        assertThat(module(result.getModules(), ".").getFiles())
            .flatExtracting(File::getClasses)
            .extracting(Class::getQualifiedName)
            .containsExactly("com.example.Valid");
    }

    @Test
    void parsesSourcesWithTheJavaVersionOfTheProject() throws IOException {
        write("pom.xml", "<project/>");
        write("src/main/java/com/example/Legacy.java", """
            package com.example;
            public class Legacy { }
            """);
        write("src/main/java/com/example/Order.java", """
            package com.example;
            public record Order(String id) { }
            """);

        final var target = new ScanTarget();
        target.setPath(".");
        target.setLanguage(ScanTarget.Language.JAVA);
        target.setBuildSystem(ScanTarget.BuildSystem.MAVEN);

        final var result = new JavaScanner("11").scan(repository, target);

        assertThat(module(result.getModules(), ".").getFiles())
            .flatExtracting(File::getClasses)
            .extracting(Class::getQualifiedName)
            .containsExactly("com.example.Legacy");
    }

    @Test
    void reportsOneLinePerModule() throws IOException {
        write("pom.xml", "<project/>");
        write("src/main/java/Root.java", "class Root { }");
        write("core/pom.xml", "<project/>");
        write("core/src/main/java/A.java", "class A { } class B { }");
        write("core/src/main/java/C.java", "class C { }");
        write("core/src/test/java/CTest.java", "class CTest { }");

        final var target = new ScanTarget();
        target.setPath(".");
        target.setLanguage(ScanTarget.Language.JAVA);
        target.setBuildSystem(ScanTarget.BuildSystem.MAVEN);

        final var log = new RecordingScanLog();

        new JavaScanner("25", log).scan(repository, target);

        assertThat(log.info).containsExactlyInAnyOrder(
            "Module .: 1 file, 1 class",
            "Module core: 3 files (1 test), 4 classes"
        );
        assertThat(log.warnings).isEmpty();
    }

    @Test
    void resolvesCallsIntoTheLibrariesOfThePomsInTheLocalMavenRepository() throws IOException {
        final var local = new LocalRepository(home.resolve("m2"));
        local.pom("org.example", "client", "1.0", "");
        local.jar("org.example", "client", "1.0", Map.of(
            "org/example/Client.java", "package org.example; public class Client { public void connect(String url) { } }"
        ));

        write("pom.xml", """
            <project>
                <groupId>com.example</groupId>
                <artifactId>shop</artifactId>
                <version>1.0</version>
                <dependencies>
                    <dependency>
                        <groupId>org.example</groupId>
                        <artifactId>client</artifactId>
                        <version>1.0</version>
                    </dependency>
                </dependencies>
            </project>
            """);
        write("src/main/java/com/example/Service.java", """
            package com.example;

            import org.example.Client;

            class Service {
                void run(Client client) {
                    client.connect("http://localhost");
                }
            }
            """);

        final var log = new RecordingScanLog();
        final var result = new JavaScanner(null, log, List.of(), local.repository()).scan(repository, target());

        assertThat(calls(result.getModules()))
            .containsExactly("org.example.Client.connect(java.lang.String)");
        assertThat(log.info).contains("Resolving calls into 1 library");
    }

    @Test
    void resolvesCallsIntoTheJarsOfTheClasspath() throws IOException {
        final var jar = new LocalRepository(home.resolve("m2")).jar("org.example", "client", "1.0", Map.of(
            "org/example/Client.java", "package org.example; public class Client { public void connect(String url) { } }"
        ));

        write("pom.xml", "<project/>");
        write("src/main/java/com/example/Service.java", """
            package com.example;

            class Service {
                void run(org.example.Client client) {
                    client.connect("http://localhost");
                }
            }
            """);

        final var log = new RecordingScanLog();
        final var result = new JavaScanner(null, log, List.of(jar, home.resolve("classes")), MavenRepository.NONE)
            .scan(repository, target());

        assertThat(calls(result.getModules()))
            .containsExactly("org.example.Client.connect(java.lang.String)");
        assertThat(log.warnings).containsExactly("Skipping classpath entry " + home.resolve("classes") + ": not a jar");
    }

    @Test
    void resolvesCallsIntoGeneratedSourcesWithoutScanningThem() throws IOException {
        write("pom.xml", "<project/>");
        write("target/generated-sources/openapi/com/example/api/OrderDto.java", """
            package com.example.api;

            public class OrderDto {
                public String getId() { return null; }
            }
            """);
        write("src/main/java/com/example/Service.java", """
            package com.example;

            import com.example.api.OrderDto;

            class Service {
                String run(OrderDto order) {
                    return order.getId();
                }
            }
            """);

        final var result = new JavaScanner().scan(repository, target());

        assertThat(calls(result.getModules()))
            .containsExactly("com.example.api.OrderDto.getId()");
        assertThat(result.getModules().getFirst().getFiles())
            .extracting(File::getPath)
            .containsExactly("src/main/java/com/example/Service.java");
    }

    private static ScanTarget target() {
        final var target = new ScanTarget();
        target.setPath(".");
        target.setLanguage(ScanTarget.Language.JAVA);
        target.setBuildSystem(ScanTarget.BuildSystem.MAVEN);

        return target;
    }

    /**
     * The targets of the calls in the methods of the modules.
     */
    private static List<String> calls(final List<Module> modules) {
        return modules.stream()
            .flatMap(module -> module.getFiles().stream())
            .flatMap(file -> file.getClasses().stream())
            .flatMap(clazz -> clazz.getMethods().stream())
            .flatMap(method -> method.getCalls().stream())
            .map(call -> call.getTarget())
            .toList();
    }

    private static Module module(final List<Module> modules, final String path) {
        return modules.stream()
            .filter(module -> module.getPath().equals(path))
            .findFirst()
            .orElseThrow();
    }

    private void write(final String path, final String content) throws IOException {
        final var file = repository.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
