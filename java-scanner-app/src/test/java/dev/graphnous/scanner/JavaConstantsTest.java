package dev.graphnous.scanner;

import dev.graphnous.scanner.model.Argument;
import dev.graphnous.scanner.model.Class;
import dev.graphnous.scanner.model.Field;
import dev.graphnous.scanner.model.File;
import dev.graphnous.scanner.model.Method;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.assertj.core.api.Assertions.tuple;

class JavaConstantsTest {

    private final JavaClassParser parser = new JavaClassParser();

    @TempDir
    Path directory;

    @Test
    void recordsTheValuesOfConstants() throws IOException {
        final var topics = write("com/example/Topics.java", """
            package com.example;

            public class Topics {
                public static final String PREFIX = "orders.";
                public static final String CREATED = PREFIX + "created";
                public static final String VERSIONED = CREATED + ".v" + 2;
                static final int PARTITIONS = 3 * (2 + 1);
                static final long RETENTION = 7L * 24 * 60 * 60 * 1000;
                static final double RATIO = 1 / 2.0;
                static final boolean ENABLED = !false;
                static final char SEPARATOR = '.';
                static final String CHOSEN = ENABLED ? "on" : "off";
                static final String BLOCK = \"""
                    orders\""";
                final String perInstance = "orders";
            }
            """);

        assertThat(constants(parse(topics, topics))).containsOnly(
            entry("PREFIX", "orders."),
            entry("CREATED", "orders.created"),
            entry("VERSIONED", "orders.created.v2"),
            entry("PARTITIONS", "9"),
            entry("RETENTION", "604800000"),
            entry("RATIO", "0.5"),
            entry("ENABLED", "true"),
            entry("SEPARATOR", "."),
            entry("CHOSEN", "on"),
            entry("BLOCK", "orders"),
            entry("perInstance", "orders")
        );
    }

    @Test
    void leavesOutWhatIsNotAConstant() throws IOException {
        final var settings = write("com/example/Settings.java", """
            package com.example;

            public class Settings {
                static String notFinal = "orders";
                static final String READ = System.getenv("TOPIC");
                static final String DERIVED = "orders." + READ;
                static final Object NOT_A_STRING = "orders";
                static final String A = B + "";
                static final String B = A + "";
                static final String LONG = "%s";
            }
            """.formatted("x".repeat(JavaConstants.MAX_LENGTH + 1)));

        assertThat(constants(parse(settings, settings))).isEmpty();
    }

    @Test
    void leavesOutSecrets() throws IOException {
        final var client = write("com/example/Client.java", """
            package com.example;

            public class Client {
                static final String API_KEY = "abc";
                static final String dbPassword = "abc";
                static final String HEADER = "X-" + API_KEY;
                static final String URL = "postgresql://app:hunter2@db/orders";
                static final String ROUTING_KEY = "orders.created";
            }
            """);

        assertThat(constants(parse(client, client))).containsOnly(
            entry("ROUTING_KEY", "orders.created")
        );
    }

    @Test
    void recordsTheConstantsOfInterfaces() throws IOException {
        final var topics = write("com/example/Topics.java", """
            package com.example;

            public interface Topics {
                String CREATED = "orders.created";
            }
            """);

        assertThat(constants(parse(topics, topics))).containsOnly(entry("CREATED", "orders.created"));
    }

    @Test
    void computesArgumentsAndAnnotationsFromConstantsOfOtherFiles() throws IOException {
        final var topics = write("com/example/Topics.java", """
            package com.example;

            public class Topics {
                public static final String CREATED = "orders.created";
            }
            """);
        final var publisher = write("com/example/orders/Publisher.java", """
            package com.example.orders;

            import com.example.Topics;
            import static com.example.Topics.CREATED;

            class Publisher {
                @Listener(topics = {Topics.CREATED, "orders." + "deleted"}, group = CREATED)
                void send(Object event, String other) {
                    final String topic = Topics.CREATED + ".v2";
                    String changing = "orders";

                    publish(Topics.CREATED, event);
                    publish(topic, event);
                    publish(changing, event);
                    publish("orders." + other, event);
                }

                void publish(String topic, Object event) { }
            }
            """);

        final var file = parse(publisher, topics, publisher);
        final var send = method(file, "send");

        assertThat(send.getCalls())
            .extracting(call -> call.getArguments().getFirst())
            .extracting(Argument::getValue, Argument::getConstant)
            .containsExactly(
                tuple("Topics.CREATED", "orders.created"),
                tuple("topic", "orders.created.v2"),
                tuple("changing", null),
                tuple("\"orders.\" + other", null)
            );

        assertThat(send.getCalls())
            .extracting(call -> call.getArguments().get(1).getConstant())
            .containsOnlyNulls();

        assertThat(send.getAnnotations().getFirst().getArguments().getAdditionalProperties()).containsExactly(
            entry("topics", List.of("orders.created", "orders.deleted")),
            entry("group", "orders.created")
        );
    }

    private Path write(
        final String path,
        final String source
    ) throws IOException {
        final var file = directory.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);

        return file;
    }

    private File parse(
        final Path path,
        final Path... sources
    ) {
        final var file = new File();
        parser.parse(path, file, parser.index(List.of(sources)));

        return file;
    }

    /**
     * The constants of the fields of the file's classes, by name.
     */
    private static Map<String, String> constants(final File file) {
        final var constants = new HashMap<String, String>();

        for (final Class clazz : file.getClasses()) {
            for (final Field field : clazz.getFields()) {
                if (field.getConstant() != null) {
                    constants.put(field.getName(), field.getConstant());
                }
            }
        }

        return constants;
    }

    private static Method method(
        final File file,
        final String name
    ) {
        return file.getClasses().stream()
            .flatMap(clazz -> clazz.getMethods().stream())
            .filter(method -> method.getName().equals(name))
            .findFirst()
            .orElseThrow();
    }
}
