package dev.graphnous.scanner;

import com.github.javaparser.ParserConfiguration;
import dev.graphnous.core.model.Annotation;
import dev.graphnous.core.model.Class;
import dev.graphnous.core.model.EnumConstant;
import dev.graphnous.core.model.Field;
import dev.graphnous.core.model.File;
import dev.graphnous.core.model.Method;
import dev.graphnous.core.model.Modifier;
import dev.graphnous.core.model.Parameter;
import dev.graphnous.core.model.RecordComponent;
import dev.graphnous.core.model.TypeRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.assertj.core.api.Assertions.tuple;

class JavaClassParserTest {

    private final JavaClassParser parser = new JavaClassParser();

    @TempDir
    Path directory;

    @Test
    void detectsAllKindsOfTypes() throws IOException {
        final var file = parse("""
            package com.example;

            public class Service { }

            interface Repository { }

            record Order(String id) { }

            enum Status { OPEN, CLOSED }

            @interface Audited { }
            """);

        assertThat(allClasses(file))
            .extracting(Class::getName, Class::getQualifiedName, Class::getKind)
            .containsExactlyInAnyOrder(
                tuple("Service", "com.example.Service", Class.Kind.CLASS),
                tuple("Repository", "com.example.Repository", Class.Kind.INTERFACE),
                tuple("Order", "com.example.Order", Class.Kind.RECORD),
                tuple("Status", "com.example.Status", Class.Kind.ENUM),
                tuple("Audited", "com.example.Audited", Class.Kind.ANNOTATION)
            );
    }

    @Test
    void qualifiesNestedTypesWithTheirOuterTypes() throws IOException {
        final var file = parse("""
            package com.example;

            public class Outer {
                public static class Inner {
                    enum Deepest { A }
                }

                record Value(int amount) { }
            }
            """);

        assertThat(allClasses(file))
            .extracting(Class::getQualifiedName)
            .containsExactlyInAnyOrder(
                "com.example.Outer",
                "com.example.Outer.Inner",
                "com.example.Outer.Inner.Deepest",
                "com.example.Outer.Value"
            );

        assertThat(file.getClasses())
            .singleElement()
            .satisfies(outer -> {
                assertThat(outer.getNesting()).isEqualTo(Class.Nesting.TOP_LEVEL);
                assertThat(outer.getClasses())
                    .extracting(Class::getQualifiedName, Class::getNesting)
                    .containsExactly(
                        tuple("com.example.Outer.Inner", Class.Nesting.MEMBER),
                        tuple("com.example.Outer.Value", Class.Nesting.MEMBER)
                    );
                assertThat(outer.getClasses().getFirst().getClasses())
                    .extracting(Class::getQualifiedName)
                    .containsExactly("com.example.Outer.Inner.Deepest");
            });
    }

    @Test
    void usesSimpleNameInTheDefaultPackage() throws IOException {
        final var file = parse("""
            class Script { }
            """);

        assertThat(allClasses(file))
            .extracting(Class::getQualifiedName)
            .containsExactly("Script");
    }

    @Test
    void setsNoClassesForAFileWithoutTypes() throws IOException {
        final var file = parse("""
            package com.example;
            """);

        assertThat(allClasses(file)).isEmpty();
    }

    @Test
    void failsOnInvalidSource() throws IOException {
        final var source = directory.resolve("Broken.java");
        Files.writeString(source, "public class {");

        assertThatThrownBy(() -> parser.parse(source, new File()))
            .isInstanceOfSatisfying(JavaParseException.class, exception -> {
                assertThat(exception.getSourcePath()).isEqualTo(source);
                assertThat(exception.getProblems()).isNotEmpty();
            })
            .hasMessageContaining(source.toString());
    }

    @Test
    void parsesModernJavaSyntax() throws IOException {
        final var file = parse("""
            package com.example;

            sealed interface Shape permits Circle, Square { }

            record Circle(double radius) implements Shape { }

            record Square(double side) implements Shape { }

            class Areas {
                double area(Shape shape) {
                    return switch (shape) {
                        case Circle(var radius) -> Math.PI * radius * radius;
                        case Square(var side) -> side * side;
                    };
                }

                String describe(Object value) {
                    var text = \"""
                        value
                        \""";
                    return value instanceof String s ? s : text;
                }
            }
            """);

        assertThat(allClasses(file))
            .extracting(Class::getName)
            .containsExactlyInAnyOrder("Shape", "Circle", "Square", "Areas");
    }

    @Test
    void rejectsSyntaxNewerThanTheLanguageLevel() throws IOException {
        final var source = directory.resolve("Order.java");
        Files.writeString(source, "record Order(String id) { }");

        final var java11 = new JavaClassParser(ParserConfiguration.LanguageLevel.JAVA_11);

        assertThatThrownBy(() -> java11.parse(source, new File()))
            .isInstanceOf(JavaParseException.class);
    }

    @Test
    void acceptsSyntaxOfTheLanguageLevel() throws IOException {
        final var source = directory.resolve("Order.java");
        Files.writeString(source, "record Order(String id) { }");

        final var file = new File();
        new JavaClassParser(ParserConfiguration.LanguageLevel.JAVA_17).parse(source, file);

        assertThat(allClasses(file))
            .extracting(Class::getKind)
            .containsExactly(Class.Kind.RECORD);
    }

    @Test
    void failsWhenTheFileCannotBeRead() {
        final var missing = directory.resolve("Missing.java");

        assertThatThrownBy(() -> parser.parse(missing, new File()))
            .isInstanceOf(UncheckedIOException.class)
            .hasMessageContaining(missing.toString());
    }

    @Test
    void detectsMethodsWithTheirParameters() throws IOException {
        final var file = parse("""
            package com.example;

            import java.util.List;

            public class Orders {
                public Order find(String id, int version) { return null; }

                void save(List<Order> orders, String... tags) { }

                enum Status { OPEN }

                void update(Status status, int[] quantities) { }
            }
            """);

        assertThat(allClasses(file))
            .filteredOn(clazz -> clazz.getName().equals("Orders"))
            .singleElement()
            .satisfies(orders -> {
                assertThat(orders.getMethods())
                    .extracting(Method::getName, Method::getQualifiedName, method -> name(method.getReturnType()))
                    .containsExactly(
                        tuple("find", "com.example.Orders.find(java.lang.String, int)", "com.example.Order"),
                        tuple("save", "com.example.Orders.save(java.util.List<com.example.Order>, java.lang.String...)", "void"),
                        tuple("update", "com.example.Orders.update(com.example.Orders.Status, int[])", "void")
                    );

                assertThat(orders.getMethods().get(1).getParameters())
                    .extracting(Parameter::getName, parameter -> name(parameter.getType()), Parameter::getQualifiedName)
                    .containsExactly(
                        tuple("orders", "java.util.List<com.example.Order>",
                            "com.example.Orders.save(java.util.List<com.example.Order>, java.lang.String...).orders"),
                        tuple("tags", "java.lang.String...",
                            "com.example.Orders.save(java.util.List<com.example.Order>, java.lang.String...).tags")
                    );
            });
    }

    @Test
    void qualifiesTypeArguments() throws IOException {
        final var file = parse("""
            package com.example;

            import java.util.List;
            import java.util.Map;
            import java.util.function.Function;

            class Mapper {
                void map(
                    Map<String, List<Order>> byCustomer,
                    Map.Entry<String, Integer> entry,
                    Function<? super Order, ? extends Number> total,
                    List<?> anything,
                    List<int[]>[] batches
                ) { }
            }
            """);

        assertThat(file.getClasses().getFirst().getMethods().getFirst().getParameters())
            .extracting(parameter -> parameter.getType().getName())
            .containsExactly(
                "java.util.Map<java.lang.String,java.util.List<com.example.Order>>",
                "java.util.Map.Entry<java.lang.String,java.lang.Integer>",
                "java.util.function.Function<? super com.example.Order,? extends java.lang.Number>",
                "java.util.List<?>",
                "java.util.List<int[]>[]"
            );
    }

    @Test
    void keepsTypeVariablesOfTheMethodAndItsTypes() throws IOException {
        final var file = parse("""
            package com.example;

            import java.util.List;

            class Repository<E> {
                <T extends Comparable<T>> T max(List<T> values, E[] entities) { return null; }

                void save(E entity) { }
            }
            """);

        assertThat(file.getClasses().getFirst().getMethods())
            .extracting(Method::getQualifiedName, method -> name(method.getReturnType()))
            .containsExactly(
                tuple("com.example.Repository.max(java.util.List<T>, E[])", "T"),
                tuple("com.example.Repository.save(E)", "void")
            );
    }

    @Test
    void resolvesTypesFromImportsAndTheJdk() throws IOException {
        final var file = parse("""
            package com.example;

            import java.util.*;

            interface Service {
                void handle(Set<String> keys, java.time.Instant at, Object value);
            }
            """);

        assertThat(file.getClasses().getFirst().getMethods())
            .extracting(Method::getQualifiedName)
            .containsExactly(
                "com.example.Service.handle(java.util.Set<java.lang.String>, java.time.Instant, java.lang.Object)"
            );
    }

    @Test
    void resolvesUnknownTypesToTheOnlyLibraryImport() throws IOException {
        final var file = parse("""
            package com.example;

            import java.util.*;
            import org.springframework.web.bind.annotation.*;

            @RestController
            class Api { }
            """);

        assertThat(file.getClasses().getFirst().getAnnotations())
            .extracting(Annotation::getQualifiedName)
            .containsExactly("org.springframework.web.bind.annotation.RestController");
    }

    @Test
    void resolvesUnknownTypesToThePackageOfTheFileWithSeveralLibraryImports() throws IOException {
        final var file = parse("""
            package com.example;

            import org.springframework.web.bind.annotation.*;
            import org.springframework.stereotype.*;

            @RestController
            class Api { }
            """);

        assertThat(file.getClasses().getFirst().getAnnotations())
            .extracting(Annotation::getQualifiedName)
            .containsExactly("com.example.RestController");
    }

    @Test
    void resolvesScannedTypesOfThePackageBeforeLibraryImports() throws IOException {
        final var order = write("Order.java", """
            package com.example;

            public record Order(String id) { }
            """);
        final var service = write("Service.java", """
            package com.example;

            import org.springframework.stereotype.*;

            @Service
            class OrderService {
                void save(Order order) { }
            }
            """);

        final var file = new File();
        parser.parse(service, file, parser.index(List.of(order, service)));

        final var clazz = file.getClasses().getFirst();

        assertThat(clazz.getMethods())
            .extracting(Method::getQualifiedName)
            .containsExactly("com.example.OrderService.save(com.example.Order)");
        assertThat(clazz.getAnnotations())
            .extracting(Annotation::getQualifiedName)
            .containsExactly("org.springframework.stereotype.Service");
    }

    @Test
    void resolvesScannedTypesOfOnDemandImports() throws IOException {
        final var customer = write("Customer.java", """
            package com.example.domain;

            public class Customer {
                public enum Tier { GOLD }
            }
            """);
        final var service = write("Service.java", """
            package com.example.service;

            import com.example.domain.*;
            import com.example.domain.Customer.*;
            import jakarta.inject.*;

            class CustomerService {
                @Inject
                CustomerService(Customer customer, Tier tier) { }
            }
            """);

        final var file = new File();
        parser.parse(service, file, parser.index(List.of(customer, service)));

        assertThat(file.getClasses().getFirst().getMethods())
            .singleElement()
            .satisfies(constructor -> {
                assertThat(constructor.getQualifiedName()).isEqualTo(
                    "com.example.service.CustomerService.CustomerService(com.example.domain.Customer, com.example.domain.Customer.Tier)"
                );
                assertThat(constructor.getAnnotations())
                    .extracting(Annotation::getQualifiedName)
                    .containsExactly("jakarta.inject.Inject");
            });
    }

    @Test
    void indexesTheTypesOfParsableFiles() throws IOException {
        final var order = write("Order.java", """
            package com.example;

            public class Order {
                record Line(int quantity) { }
            }
            """);
        final var broken = write("Broken.java", "public class {");

        final var index = parser.index(List.of(order, broken));

        assertThat(index.size()).isEqualTo(2);
        assertThat(index.containsType("com.example.Order")).isTrue();
        assertThat(index.containsType("com.example.Order.Line")).isTrue();
        assertThat(index.containsPackage("com.example")).isTrue();
    }

    @Test
    void detectsConstructorsAsMethodsWithoutReturnType() throws IOException {
        final var file = parse("""
            package com.example;

            import java.util.List;

            public class Order {
                public Order() { }

                Order(String id, List<Line> lines) { }

                void cancel() { }

                record Line(String product, int quantity) { }
            }
            """);

        assertThat(allClasses(file))
            .filteredOn(clazz -> clazz.getName().equals("Order"))
            .flatExtracting(Class::getMethods)
            .extracting(Method::getName, Method::getQualifiedName, method -> name(method.getReturnType()))
            .containsExactly(
                tuple("Order", "com.example.Order.Order()", null),
                tuple("Order", "com.example.Order.Order(java.lang.String, java.util.List<com.example.Order.Line>)", null),
                tuple("cancel", "com.example.Order.cancel()", "void")
            );

        assertThat(allClasses(file))
            .filteredOn(clazz -> clazz.getName().equals("Line"))
            .flatExtracting(Class::getMethods)
            .isEmpty();
    }

    @Test
    void detectsTheCompactConstructorOfARecordWithTheComponentsAsParameters() throws IOException {
        final var file = parse("""
            package com.example;

            record Order(String id, int quantity) {
                Order {
                    if (quantity < 0) throw new IllegalArgumentException();
                }

                Order(String id) { this(id, 1); }
            }
            """);

        assertThat(file.getClasses().getFirst().getMethods())
            .extracting(Method::getQualifiedName, method -> name(method.getReturnType()))
            .containsExactly(
                tuple("com.example.Order.Order(java.lang.String, int)", null),
                tuple("com.example.Order.Order(java.lang.String)", null)
            );
    }

    @Test
    void detectsAnnotationElementsAsMethods() throws IOException {
        final var file = parse("""
            package com.example;

            @interface Audited {
                String value() default "";
            }
            """);

        assertThat(file.getClasses().getFirst().getMethods())
            .extracting(Method::getQualifiedName, method -> name(method.getReturnType()))
            .containsExactly(tuple("com.example.Audited.value()", "java.lang.String"));
    }

    @Test
    void detectsFieldsOfTheTypeItself() throws IOException {
        final var file = parse("""
            package com.example;

            import java.util.List;

            public class Order {
                private final String id;
                private int quantity, values[];
                private static final List<String> TAGS = List.of();

                class Line {
                    long amount;
                }
            }
            """);

        assertThat(allClasses(file))
            .filteredOn(clazz -> clazz.getName().equals("Order"))
            .singleElement()
            .satisfies(order -> assertThat(order.getFields())
                .extracting(Field::getName, Field::getQualifiedName, field -> name(field.getType()))
                .containsExactly(
                    tuple("id", "com.example.Order.id", "java.lang.String"),
                    tuple("quantity", "com.example.Order.quantity", "int"),
                    tuple("values", "com.example.Order.values", "int[]"),
                    tuple("TAGS", "com.example.Order.TAGS", "java.util.List<java.lang.String>")
                ));

        assertThat(allClasses(file))
            .filteredOn(clazz -> clazz.getName().equals("Line"))
            .singleElement()
            .satisfies(line -> assertThat(line.getFields())
                .extracting(Field::getQualifiedName)
                .containsExactly("com.example.Order.Line.amount"));
    }

    @Test
    void detectsRecordComponentsAndEnumConstants() throws IOException {
        final var file = parse("""
            package com.example;

            record Order(String id, int quantity) {
                static final int MAX = 10;
            }

            enum Status { OPEN, CLOSED }
            """);

        assertThat(allClasses(file))
            .filteredOn(clazz -> clazz.getName().equals("Order"))
            .singleElement()
            .satisfies(order -> {
                assertThat(order.getRecordComponents())
                    .extracting(RecordComponent::getName, component -> name(component.getType()))
                    .containsExactly(
                        tuple("id", "java.lang.String"),
                        tuple("quantity", "int")
                    );
                assertThat(order.getFields())
                    .extracting(Field::getName, field -> name(field.getType()))
                    .containsExactly(tuple("MAX", "int"));
            });

        assertThat(allClasses(file))
            .filteredOn(clazz -> clazz.getName().equals("Status"))
            .singleElement()
            .satisfies(status -> {
                assertThat(status.getEnumConstants())
                    .extracting(EnumConstant::getName, EnumConstant::getQualifiedName)
                    .containsExactly(
                        tuple("OPEN", "com.example.Status.OPEN"),
                        tuple("CLOSED", "com.example.Status.CLOSED")
                    );
                assertThat(status.getFields()).isEmpty();
            });
    }

    @Test
    void listsTheClassesEachTypeRefersTo() throws IOException {
        final var file = parse("""
            package com.example;

            import java.util.List;
            import java.util.Map;

            class Orders<T> {
                Map<String, List<? extends Order>>[] byCustomer;
                int count;
                T current;
                Map.Entry<String, T> last;
            }

            record Order(String id) { }
            """);

        assertThat(allClasses(file).getFirst().getFields())
            .extracting(Field::getName, field -> field.getType().getReferences())
            .containsExactly(
                tuple("byCustomer", Set.of("java.util.Map", "java.lang.String", "java.util.List", "com.example.Order")),
                tuple("count", Set.of()),
                tuple("current", Set.of()),
                tuple("last", Set.of("java.util.Map.Entry", "java.lang.String"))
            );
    }

    @Test
    void detectsAnnotationsOfClassesMethodsConstructorsAndFields() throws IOException {
        final var file = parse("""
            package com.example;

            import org.springframework.stereotype.Service;
            import org.springframework.transaction.annotation.Transactional;
            import jakarta.inject.Inject;

            @Service
            @Deprecated
            public class Orders {
                @Inject
                private Repository repository;

                @Inject
                Orders(Repository repository) { }

                @Override
                @Transactional
                public String toString() { return ""; }
            }
            """);

        final var orders = file.getClasses().getFirst();

        assertThat(orders.getAnnotations())
            .extracting(Annotation::getName, Annotation::getQualifiedName, Annotation::getArguments)
            .containsExactly(
                tuple("Service", "org.springframework.stereotype.Service", null),
                tuple("Deprecated", "java.lang.Deprecated", null)
            );

        assertThat(orders.getFields())
            .flatExtracting(Field::getAnnotations)
            .extracting(Annotation::getQualifiedName)
            .containsExactly("jakarta.inject.Inject");

        assertThat(orders.getMethods())
            .extracting(method -> method.getAnnotations().stream().map(Annotation::getQualifiedName).toList())
            .containsExactly(
                List.of("jakarta.inject.Inject"),
                List.of("java.lang.Override", "org.springframework.transaction.annotation.Transactional")
            );
    }

    @Test
    void detectsAnnotationsOfRecordComponentsAndEnumConstants() throws IOException {
        final var file = parse("""
            package com.example;

            record Order(@NotNull String id) { }

            enum Status { @Deprecated OPEN, CLOSED }
            """);

        assertThat(allClasses(file))
            .flatExtracting(Class::getRecordComponents)
            .extracting(component -> component.getAnnotations().stream().map(Annotation::getQualifiedName).toList())
            .containsExactly(List.of("com.example.NotNull"));

        assertThat(allClasses(file))
            .flatExtracting(Class::getEnumConstants)
            .extracting(constant -> constant.getAnnotations().stream().map(Annotation::getQualifiedName).toList())
            .containsExactly(
                List.of("java.lang.Deprecated"),
                List.of()
            );
    }

    @Test
    void convertsAnnotationArgumentsToValues() throws IOException {
        final var file = parse("""
            package com.example;

            import java.util.concurrent.TimeUnit;
            import org.springframework.web.bind.annotation.RequestMapping;
            import org.springframework.web.bind.annotation.RequestMethod;

            @RequestMapping(
                path = {"/orders", "/api/orders"},
                method = RequestMethod.GET,
                produces = "application/json",
                timeout = 30,
                limit = -1L,
                ratio = 0.5,
                enabled = true,
                separator = ';',
                unit = TimeUnit.SECONDS,
                type = Order.class,
                cache = @Cache(name = "orders", ttl = 60),
                expression = "/api" + PATH
            )
            @SuppressWarnings("unchecked")
            class Api {
                static final String PATH = "/orders";
            }
            """);

        final var annotations = file.getClasses().getFirst().getAnnotations();

        assertThat(annotations.getFirst().getArguments().getAdditionalProperties())
            .containsExactly(
                entry("path", List.of("/orders", "/api/orders")),
                entry("method", "org.springframework.web.bind.annotation.RequestMethod.GET"),
                entry("produces", "application/json"),
                entry("timeout", 30),
                entry("limit", -1L),
                entry("ratio", 0.5),
                entry("enabled", true),
                entry("separator", ";"),
                entry("unit", "java.util.concurrent.TimeUnit.SECONDS"),
                entry("type", "com.example.Order.class"),
                entry("cache", annotations.getFirst().getArguments().getAdditionalProperties().get("cache")),
                entry("expression", "\"/api\" + PATH")
            );

        assertThat(annotations.getFirst().getArguments().getAdditionalProperties().get("cache"))
            .isInstanceOfSatisfying(Annotation.class, cache -> {
                assertThat(cache.getQualifiedName()).isEqualTo("com.example.Cache");
                assertThat(cache.getArguments().getAdditionalProperties())
                    .containsExactly(entry("name", "orders"), entry("ttl", 60));
            });

        assertThat(annotations.get(1).getArguments().getAdditionalProperties())
            .containsExactly(entry("value", "unchecked"));
    }

    @Test
    void detectsTheSupertypesOfEachKindOfType() throws IOException {
        final var file = parse("""
            package com.example;

            import java.io.Serializable;
            import java.util.AbstractList;
            import java.util.function.Supplier;

            class Orders extends AbstractList<Order> implements Serializable, Supplier<Order> { }

            class Plain { }

            interface Repository<T> extends Supplier<T>, AutoCloseable { }

            record Order(String id) implements Comparable<Order> { }

            enum Status implements Supplier<String> { OPEN }

            @interface Audited { }
            """);

        assertThat(allClasses(file))
            .extracting(Class::getName, clazz -> names(clazz.getSuperClasses()), clazz -> names(clazz.getInterfaces()))
            .containsExactly(
                tuple("Orders", List.of("java.util.AbstractList<com.example.Order>"),
                    List.of("java.io.Serializable", "java.util.function.Supplier<com.example.Order>")),
                tuple("Plain", List.of(), List.of()),
                tuple("Repository", List.of(), List.of("java.util.function.Supplier<T>", "java.lang.AutoCloseable")),
                tuple("Order", List.of(), List.of("java.lang.Comparable<com.example.Order>")),
                tuple("Status", List.of(), List.of("java.util.function.Supplier<java.lang.String>")),
                tuple("Audited", List.of(), List.of())
            );
    }

    @Test
    void detectsTheDeclaredModifiers() throws IOException {
        final var file = parse("""
            package com.example;

            public abstract sealed class Shape permits Circle {
                private static final long serialVersionUID = 1L;
                protected transient volatile int cache;

                public abstract double area();

                static synchronized void reset() { }

                protected Shape() { }
            }

            final class Circle extends Shape {
                public double area() { return 0; }
            }

            interface Named {
                String name();

                default String display() { return name(); }
            }
            """);

        final var shape = file.getClasses().getFirst();

        assertThat(shape.getModifiers())
            .containsExactly(Modifier.PUBLIC, Modifier.ABSTRACT, Modifier.SEALED);

        assertThat(shape.getFields())
            .extracting(Field::getName, Field::getModifiers)
            .containsExactly(
                tuple("serialVersionUID", List.of(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)),
                tuple("cache", List.of(Modifier.PROTECTED, Modifier.TRANSIENT, Modifier.VOLATILE))
            );

        assertThat(shape.getMethods())
            .extracting(Method::getName, Method::getModifiers)
            .containsExactly(
                tuple("area", List.of(Modifier.PUBLIC, Modifier.ABSTRACT)),
                tuple("reset", List.of(Modifier.STATIC, Modifier.SYNCHRONIZED)),
                tuple("Shape", List.of(Modifier.PROTECTED))
            );

        // Only what is written: interface methods are implicitly public
        assertThat(file.getClasses().get(2).getMethods())
            .extracting(Method::getName, Method::getModifiers)
            .containsExactly(
                tuple("name", List.of()),
                tuple("display", List.of(Modifier.DEFAULT))
            );
    }

    @Test
    void tellsMethodsAndConstructorsApart() throws IOException {
        final var file = parse("""
            package com.example;

            record Order(String id) {
                Order { }

                Order() { this("new"); }

                String describe() { return id; }
            }

            @interface Audited {
                String value();
            }
            """);

        assertThat(allClasses(file))
            .flatExtracting(Class::getMethods)
            .extracting(Method::getQualifiedName, Method::getKind)
            .containsExactly(
                tuple("com.example.Order.Order(java.lang.String)", Method.Kind.CONSTRUCTOR),
                tuple("com.example.Order.Order()", Method.Kind.CONSTRUCTOR),
                tuple("com.example.Order.describe()", Method.Kind.METHOD),
                tuple("com.example.Audited.value()", Method.Kind.METHOD)
            );
    }

    @Test
    void detectsTypeParametersWithTheirBounds() throws IOException {
        final var file = parse("""
            package com.example;

            import java.io.Serializable;

            class Repository<E extends Entity & Serializable, ID> {
                <T extends Comparable<T>> T max(T first, T second) { return first; }

                <R> Repository(R source) { }
            }

            record Page<T>(T content) { }
            """);

        final var repository = file.getClasses().getFirst();

        assertThat(repository.getTypeParameters())
            .containsExactly("E extends com.example.Entity & java.io.Serializable", "ID");

        assertThat(repository.getMethods())
            .extracting(Method::getName, Method::getTypeParameters)
            .containsExactly(
                tuple("max", List.of("T extends java.lang.Comparable<T>")),
                tuple("Repository", List.of("R"))
            );

        assertThat(file.getClasses().get(1).getTypeParameters()).containsExactly("T");
    }

    @Test
    void detectsParameterAnnotationsAndNamesParametersAfterTheirMethod() throws IOException {
        final var file = parse("""
            package com.example;

            import org.springframework.web.bind.annotation.*;

            class OrderApi {
                Order get(@PathVariable("id") String id, @RequestParam(required = false) int page) { return null; }
            }
            """);

        assertThat(file.getClasses().getFirst().getMethods().getFirst().getParameters())
            .satisfiesExactly(
                id -> {
                    assertThat(id.getQualifiedName())
                        .isEqualTo("com.example.OrderApi.get(java.lang.String, int).id");
                    assertThat(id.getAnnotations())
                        .extracting(Annotation::getQualifiedName)
                        .containsExactly("org.springframework.web.bind.annotation.PathVariable");
                    assertThat(id.getAnnotations().getFirst().getArguments().getAdditionalProperties())
                        .containsExactly(entry("value", "id"));
                },
                page -> assertThat(page.getAnnotations())
                    .extracting(Annotation::getQualifiedName)
                    .containsExactly("org.springframework.web.bind.annotation.RequestParam")
            );
    }

    @Test
    void leavesOutTypesWithoutAQualifiedName() throws IOException {
        final var file = parse("""
            package com.example;

            class Outer {
                void run() {
                    record Point(int x) { }
                    class Helper { }
                    Runnable task = new Runnable() {
                        class InAnonymous { }
                        public void run() { }
                    };
                }

                enum Mode {
                    FAST {
                        class InConstant { }
                    }
                }
            }
            """);

        assertThat(allClasses(file))
            .extracting(Class::getQualifiedName)
            .containsExactly("com.example.Outer", "com.example.Outer.Mode");
    }

    @Test
    void doesNotIndexTypesWithoutAQualifiedName() throws IOException {
        final var outer = write("Outer.java", """
            package com.example;

            class Outer {
                void run() {
                    class Point { }
                }
            }
            """);

        final var index = parser.index(List.of(outer));

        assertThat(index.containsType("com.example.Outer")).isTrue();
        assertThat(index.containsType("com.example.Point")).isFalse();
        assertThat(index.containsType("com.example.Outer.Point")).isFalse();
    }

    private Path write(final String name, final String source) throws IOException {
        final var path = directory.resolve(name);
        Files.writeString(path, source);

        return path;
    }

    private File parse(final String source) throws IOException {
        final var path = directory.resolve("Source.java");
        Files.writeString(path, source);

        final var file = new File();
        parser.parse(path, file);

        return file;
    }

    /**
     * The classes of the file and the classes nested in them, depth first.
     */
    private static List<Class> allClasses(final File file) {
        return file.getClasses().stream().flatMap(JavaClassParserTest::withNested).toList();
    }

    private static Stream<Class> withNested(final Class clazz) {
        return Stream.concat(Stream.of(clazz), clazz.getClasses().stream().flatMap(JavaClassParserTest::withNested));
    }

    private static String name(final TypeRef type) {
        return type == null ? null : type.getName();
    }

    private static List<String> names(final List<TypeRef> types) {
        return types.stream().map(TypeRef::getName).toList();
    }
}
