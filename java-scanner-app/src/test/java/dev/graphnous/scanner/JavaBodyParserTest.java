package dev.graphnous.scanner;

import dev.graphnous.scanner.java.maven.LocalRepository;
import dev.graphnous.scanner.model.Argument;
import dev.graphnous.scanner.model.Call;
import dev.graphnous.scanner.model.FieldAccess;
import dev.graphnous.scanner.model.File;
import dev.graphnous.scanner.model.Method;
import dev.graphnous.scanner.model.TypeUse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class JavaBodyParserTest {

    private final JavaClassParser parser = new JavaClassParser();

    @TempDir
    Path directory;

    @Test
    void resolvesCallsToMethodsOfOtherFilesOfTheTarget() throws IOException {
        final var orders = write("com/example/Orders.java", """
            package com.example;

            import java.util.List;

            public class Orders {
                public Order find(String id) { return null; }

                public void save(List<Order> orders, String... tags) { }
            }
            """);
        final var order = write("com/example/Order.java", """
            package com.example;

            public class Order { }
            """);
        final var service = write("com/example/service/Service.java", """
            package com.example.service;

            import com.example.Orders;
            import java.util.List;

            class Service {
                private final Orders orders = new Orders();

                void run() {
                    orders.save(List.of(orders.find("1")), "a", "b");
                }
            }
            """);

        final var run = method(parse(service, orders, order, service), "run");

        assertThat(run.getCalls())
            .extracting(Call::getKind, Call::getTarget, Call::getResolved, Call::getStartLine, Call::getStartColumn)
            .containsExactly(
                tuple(Call.Kind.METHOD, "com.example.Orders.save(java.util.List<com.example.Order>, java.lang.String...)", true, 10, 9),
                tuple(Call.Kind.METHOD, "java.util.List.of(E)", true, 10, 21),
                tuple(Call.Kind.METHOD, "com.example.Orders.find(java.lang.String)", true, 10, 29)
            );
    }

    @Test
    void listsTheArgumentsOfCallsAsWritten() throws IOException {
        final var orders = write("com/example/Orders.java", """
            package com.example;

            import java.util.List;
            import java.util.function.Consumer;

            class Orders {
                Orders(String name) { }

                void save(String id, int count, Consumer<String> then) { }

                void run() {
                    save("1",  1
                        + 2, id -> {
                            System.out.println("Saved the order with id " + id + ", which is the first of the orders that this method saves, and the only one at that");
                            System.out.println("And a second line, so that what is passed is longer than is kept");
                        });
                    new Orders("orders").run();
                    List.of("a").forEach(this::log);
                }

                void log(String line) { }
            }
            """);

        final var run = method(parse(orders, orders), "run");

        assertThat(run.getCalls())
            .filteredOn(call -> !call.getTarget().startsWith("java.io"))
            .extracting(
                Call::getTarget,
                call -> call.getArguments().stream().map(Argument::getValue).toList()
            )
            .containsExactly(
                tuple(
                    "com.example.Orders.save(java.lang.String, int, java.util.function.Consumer<java.lang.String>)",
                    List.of(
                        "\"1\"",
                        "1 + 2",
                        "id -> { System.out.println(\"Saved the order with id \" + id + \", which is the first of the orders that this method saves, and the only one at that\"); System.out.println(\"And a second line, so that wha…"
                    )
                ),
                tuple("com.example.Orders.run()", List.of()),
                tuple("com.example.Orders.Orders(java.lang.String)", List.of("\"orders\"")),
                tuple("java.lang.Iterable.forEach(java.util.function.Consumer<? super T>)", List.of("this::log")),
                tuple("java.util.List.of(E)", List.of("\"a\"")),
                tuple("com.example.Orders.log(java.lang.String)", List.of())
            );

        assertThat(run.getCalls())
            .flatExtracting(Call::getArguments)
            .allSatisfy(argument -> {
                assertThat(argument.getValue()).hasSizeLessThanOrEqualTo(200);
                assertThat(argument.getName()).isNull();
            });
    }

    @Test
    void resolvesConstructorsAndMethodReferences() throws IOException {
        final var order = write("com/example/Order.java", """
            package com.example;

            import java.util.List;

            class Order {
                private final String id;

                Order(String id) {
                    this.id = id;
                }

                Order() {
                    this("new");
                }

                String getId() { return id; }

                List<String> ids(List<Order> orders) {
                    new Order("x");
                    return orders.stream().map(Order::getId).toList();
                }
            }
            """);

        final var methods = parse(order, order).getClasses().getFirst().getMethods();

        assertThat(methods.get(1).getCalls())
            .extracting(Call::getKind, Call::getTarget)
            .containsExactly(tuple(Call.Kind.CONSTRUCTOR, "com.example.Order.Order(java.lang.String)"));

        assertThat(methods.get(3).getCalls())
            .extracting(Call::getKind, Call::getTarget)
            .containsExactly(
                tuple(Call.Kind.CONSTRUCTOR, "com.example.Order.Order(java.lang.String)"),
                tuple(Call.Kind.METHOD, "java.util.stream.Stream.toList()"),
                tuple(Call.Kind.METHOD, "java.util.stream.Stream.map(java.util.function.Function<? super T,? extends R>)"),
                tuple(Call.Kind.METHOD, "java.util.Collection.stream()"),
                tuple(Call.Kind.METHOD_REFERENCE, "com.example.Order.getId()")
            );
    }

    @Test
    void keepsCallsIntoLibrariesUnresolvedAsWritten() throws IOException {
        final var service = write("com/example/Service.java", """
            package com.example;

            import org.slf4j.Logger;
            import org.slf4j.LoggerFactory;

            class Service {
                private static final Logger log = LoggerFactory.getLogger(Service.class);

                void run(Client client) {
                    log.info("running");
                    client.connect();
                    new Client();
                }
            }
            """);

        assertThat(method(parse(service, service), "run").getCalls())
            .extracting(Call::getKind, Call::getTarget, Call::getResolved)
            .containsExactly(
                tuple(Call.Kind.METHOD, "log.info", false),
                tuple(Call.Kind.METHOD, "client.connect", false),
                tuple(Call.Kind.CONSTRUCTOR, "com.example.Client.Client", false)
            );
    }

    @Test
    void resolvesCallsWithLibraryArgumentsToTheOnlyMethodOfTheTargetThatTakesAsManyArguments() throws IOException {
        final var repository = write("com/example/Repository.java", """
            package com.example;

            import org.springframework.jdbc.core.JdbcTemplate;

            class Repository {
                Repository(JdbcTemplate jdbc) { }

                void save(JdbcTemplate jdbc, String id) { }

                void save(String id) { }

                static Repository of(JdbcTemplate jdbc) { return new Repository(jdbc); }
            }
            """);
        final var service = write("com/example/Service.java", """
            package com.example;

            import org.springframework.jdbc.core.JdbcTemplate;

            class Service {
                void run(JdbcTemplate jdbc, Repository repository) {
                    repository.save(jdbc, "1");
                    Repository.of(jdbc);
                    log(jdbc);
                }

                private void log(JdbcTemplate jdbc) { }
            }
            """);

        assertThat(method(parse(service, repository, service), "run").getCalls())
            .extracting(Call::getTarget, Call::getResolved)
            .containsExactly(
                tuple("com.example.Repository.save(org.springframework.jdbc.core.JdbcTemplate, java.lang.String)", true),
                tuple("com.example.Repository.of(org.springframework.jdbc.core.JdbcTemplate)", true),
                tuple("com.example.Service.log(org.springframework.jdbc.core.JdbcTemplate)", true)
            );

        assertThat(method(parse(repository, repository, service), "of").getCalls())
            .extracting(Call::getKind, Call::getTarget, Call::getResolved)
            .containsExactly(
                tuple(Call.Kind.CONSTRUCTOR, "com.example.Repository.Repository(org.springframework.jdbc.core.JdbcTemplate)", true)
            );
    }

    @Test
    void detectsReadsAndWritesOfFieldsAndEnumConstants() throws IOException {
        final var counter = write("com/example/Counter.java", """
            package com.example;

            class Counter {
                enum Status { OPEN }

                private int count;
                private Status status;

                void increment(int count) {
                    this.count += count;
                    status = Status.OPEN;
                    count++;
                    total++;
                    System.out.println(this.count);
                }

                static int total;
            }
            """);

        assertThat(method(parse(counter, counter), "increment").getFieldAccesses())
            .extracting(FieldAccess::getTarget, FieldAccess::getAccess, FieldAccess::getStartLine)
            .containsExactly(
                tuple("com.example.Counter.count", FieldAccess.Access.READ_WRITE, 10),
                tuple("com.example.Counter.status", FieldAccess.Access.WRITE, 11),
                tuple("com.example.Counter.Status.OPEN", FieldAccess.Access.READ, 11),
                tuple("com.example.Counter.total", FieldAccess.Access.READ_WRITE, 13),
                tuple("java.lang.System.out", FieldAccess.Access.READ, 14),
                tuple("com.example.Counter.count", FieldAccess.Access.READ, 14)
            );
    }

    @Test
    void detectsTheTypesTheBodyUses() throws IOException {
        final var service = write("com/example/Service.java", """
            package com.example;

            import java.util.ArrayList;
            import java.util.List;
            import java.util.Map;

            class Service {
                <T> void run(Object value) {
                    List<String> names = new ArrayList<Order>();
                    Map.Entry<String, T> entry = null;
                    var inferred = 1;
                    String text = (String) value;
                    if (value instanceof Order order) { }
                    try {
                        Class<?> type = Order.class;
                    } catch (IllegalStateException | Unknown e) { }
                    Order[] orders = new Order[1];
                    Runnable task = new Runnable() { public void run() { } };
                }
            }

            class Order { }
            """);

        assertThat(method(parse(service, service), "run").getTypeUses())
            .extracting(TypeUse::getKind, TypeUse::getTarget, TypeUse::getResolved)
            .containsExactly(
                tuple(TypeUse.Kind.LOCAL_VARIABLE, "java.util.List", true),
                tuple(TypeUse.Kind.LOCAL_VARIABLE, "java.lang.String", true),
                tuple(TypeUse.Kind.TYPE_ARGUMENT, "com.example.Order", true),
                tuple(TypeUse.Kind.LOCAL_VARIABLE, "java.util.Map.Entry", true),
                tuple(TypeUse.Kind.LOCAL_VARIABLE, "java.lang.String", true),
                tuple(TypeUse.Kind.LOCAL_VARIABLE, "java.lang.String", true),
                tuple(TypeUse.Kind.CAST, "java.lang.String", true),
                tuple(TypeUse.Kind.INSTANCEOF, "com.example.Order", true),
                tuple(TypeUse.Kind.LOCAL_VARIABLE, "java.lang.Class", true),
                tuple(TypeUse.Kind.CLASS_LITERAL, "com.example.Order", true),
                tuple(TypeUse.Kind.CATCH, "java.lang.IllegalStateException", true),
                tuple(TypeUse.Kind.CATCH, "com.example.Unknown", false),
                tuple(TypeUse.Kind.LOCAL_VARIABLE, "com.example.Order", true),
                tuple(TypeUse.Kind.OTHER, "com.example.Order", true),
                tuple(TypeUse.Kind.LOCAL_VARIABLE, "java.lang.Runnable", true),
                tuple(TypeUse.Kind.OTHER, "java.lang.Runnable", true)
            );
    }

    @Test
    void addsWhatLambdasAndAnonymousClassesUseToTheMethod() throws IOException {
        final var service = write("com/example/Service.java", """
            package com.example;

            class Service {
                void run() {
                    Runnable first = () -> stop();
                    Runnable second = new Runnable() {
                        public void run() {
                            stop();
                        }
                    };
                }

                void stop() { }
            }
            """);

        final var methods = parse(service, service).getClasses().getFirst().getMethods();

        assertThat(methods.getFirst().getCalls())
            .extracting(Call::getTarget, Call::getStartLine)
            .containsExactly(
                tuple("com.example.Service.stop()", 5),
                tuple("com.example.Service.stop()", 8)
            );
        assertThat(methods.get(1).getCalls()).isEmpty();
    }

    @Test
    void resolvesCallsIntoLocalAndAnonymousClasses() throws IOException {
        final var service = write("com/example/Service.java", """
            package com.example;

            class Service {
                Runnable onStart = new Runnable() {
                    public void run() {
                        start();
                    }
                };

                int run() {
                    class Counter {
                        int count;

                        int next() {
                            return ++count + stop();
                        }
                    }

                    Counter counter = new Counter();
                    return counter.next();
                }

                void start() { }

                int stop() { return 0; }
            }
            """);

        final var file = parse(service, service);
        final var classes = file.getClasses().getFirst().getClasses();

        // The method of a local class has what its body uses, not the
        // method that declares the class
        assertThat(method(file, "run").getCalls())
            .extracting(Call::getKind, Call::getTarget)
            .containsExactly(
                tuple(Call.Kind.CONSTRUCTOR, "com.example.Service$1Counter.Counter()"),
                tuple(Call.Kind.METHOD, "com.example.Service$1Counter.next()")
            );
        assertThat(method(file, "run").getTypeUses())
            .extracting(TypeUse::getTarget)
            .containsExactly("com.example.Service$1Counter");

        assertThat(classes)
            .extracting(clazz -> clazz.getQualifiedName())
            .containsExactly("com.example.Service$1", "com.example.Service$1Counter");

        final var next = classes.get(1).getMethods().getFirst();

        assertThat(next.getQualifiedName()).isEqualTo("com.example.Service$1Counter.next()");
        assertThat(next.getCalls()).extracting(Call::getTarget).containsExactly("com.example.Service.stop()");
        assertThat(next.getFieldAccesses())
            .extracting(FieldAccess::getTarget, FieldAccess::getAccess)
            .containsExactly(tuple("com.example.Service$1Counter.count", FieldAccess.Access.READ_WRITE));

        // An anonymous class outside a method has what its methods use
        assertThat(classes.getFirst().getMethods().getFirst().getCalls())
            .extracting(Call::getTarget)
            .containsExactly("com.example.Service.start()");
    }

    @Test
    void resolvesCallsIntoTheJarsOfLibraries() throws IOException {
        final var jar = new LocalRepository(directory.resolve("m2")).jar("org.example", "client", "1.0", Map.of(
            "org/example/client/Client.java", """
                package org.example.client;

                public class Client {
                    public static Client connect(String url) { return new Client(); }

                    public Response send(Request request) { return null; }
                }
                """,
            "org/example/client/Request.java", "package org.example.client; public class Request { }",
            "org/example/client/Response.java", "package org.example.client; public class Response { public int status; }"
        ));
        final var service = write("src/com/example/Service.java", """
            package com.example;

            import org.example.client.*;

            class Service {
                int run(Request request) {
                    return Client.connect("http://localhost").send(request).status;
                }
            }
            """);

        final var file = new File();
        parser.parse(service, file, parser.index(List.of(service), List.of(jar)));
        final var run = method(file, "run");

        assertThat(run.getCalls())
            .extracting(Call::getTarget, Call::getResolved)
            .containsExactly(
                tuple("org.example.client.Client.send(org.example.client.Request)", true),
                tuple("org.example.client.Client.connect(java.lang.String)", true)
            );
        assertThat(run.getFieldAccesses())
            .extracting(FieldAccess::getTarget)
            .containsExactly("org.example.client.Response.status");

        // The wildcard import of the library resolves the parameter type
        assertThat(run.getParameters().getFirst().getType().getName()).isEqualTo("org.example.client.Request");
    }

    @Test
    void resolvesOverloadsForArraysOfEachPrimitiveTypeTheSameEachTime() throws IOException {
        final var arrays = write("com/example/Arrays2.java", """
            package com.example;

            public class Arrays2 {
                public static int[] copy(int[] a) { return a; }
                public static long[] copy(long[] a) { return a; }
                public static double[] copy(double[] a) { return a; }
                public static Integer[] copy(Integer[] a) { return a; }
                public static Object[] copy(Object[] a) { return a; }
            }
            """);
        final var service = write("com/example/Service.java", """
            package com.example;

            class Service {
                void run(int[] ints, long[] longs, Integer[] boxed, String[] strings) {
                    Arrays2.copy(ints);
                    Arrays2.copy(longs);
                    Arrays2.copy((double[]) null);
                    Arrays2.copy(boxed);
                    Arrays2.copy(strings);
                    Arrays2.copy(Arrays2.copy(ints));
                }
            }
            """);

        // The symbol solver picks one of these at random, see JavaBodyParser
        for (int i = 0; i < 5; i++) {
            assertThat(method(parse(service, arrays, service), "run").getCalls())
                .extracting(Call::getTarget)
                .containsExactly(
                    "com.example.Arrays2.copy(int[])",
                    "com.example.Arrays2.copy(long[])",
                    "com.example.Arrays2.copy(double[])",
                    "com.example.Arrays2.copy(java.lang.Integer[])",
                    "com.example.Arrays2.copy(java.lang.Object[])",
                    "com.example.Arrays2.copy(int[])",
                    "com.example.Arrays2.copy(int[])"
                );
        }
    }

    @Test
    void resolvesThePatternVariablesOfEarlierStatements() throws IOException {
        final var service = write("com/example/Service.java", """
            package com.example;

            class Service {
                String run(Object value) {
                    String text = "";
                    if (!(value instanceof Order order)) {
                        return text;
                    }
                    return order.id();
                }
            }

            record Order(String id) { }
            """);

        assertThat(method(parse(service, service), "run").getCalls())
            .extracting(Call::getTarget, Call::getResolved)
            .containsExactly(tuple("com.example.Order.id()", true));
    }

    @Test
    void usesTheCopyOfBlockStmtContextThatResolvesNamesInLongBlocks() throws IOException {
        final var context = com.github.javaparser.symbolsolver.javaparsermodel.contexts.BlockStmtContext.class;

        // The class of the scanner, not the one of the library jar
        assertThat(context.getProtectionDomain().getCodeSource().getLocation().getPath()).doesNotEndWith(".jar");

        final var statements = new StringBuilder();
        for (int i = 0; i < 3_000; i++) {
            statements.append("        total = add(total, ").append(i).append(");\n");
        }

        final var service = write("com/example/Service.java", """
            package com.example;

            class Service {
                int run() {
                    int total = 0;
            %s        return total;
                }

                int add(int a, int b) { return a + b; }
            }
            """.formatted(statements));

        assertThat(method(parse(service, service), "run").getCalls())
            .hasSize(3_000)
            .allSatisfy(call -> assertThat(call.getTarget()).isEqualTo("com.example.Service.add(int, int)"));
    }

    private Path write(final String name, final String source) throws IOException {
        final var path = directory.resolve(name);
        Files.createDirectories(path.getParent());
        Files.writeString(path, source);

        return path;
    }

    /**
     * Parses the file with the types of all sources of the target.
     */
    private File parse(final Path path, final Path... sources) {
        final var file = new File();
        parser.parse(path, file, parser.index(List.of(sources)));

        return file;
    }

    private static Method method(final File file, final String name) {
        final var methods = new ArrayList<Method>();
        file.getClasses().forEach(clazz -> methods.addAll(clazz.getMethods()));

        return methods.stream()
            .filter(method -> method.getName().equals(name))
            .findFirst()
            .orElseThrow();
    }
}
