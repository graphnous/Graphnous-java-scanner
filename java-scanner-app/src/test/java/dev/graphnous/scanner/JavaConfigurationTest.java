package dev.graphnous.scanner;

import dev.graphnous.scanner.model.ConfigurationProperty;
import dev.graphnous.scanner.model.Module;
import dev.graphnous.scanner.model.ScanTarget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class JavaConfigurationTest {

    @TempDir
    Path repository;

    @Test
    void readsPropertiesFiles() {
        final var documents = JavaConfiguration.propertiesDocuments("""
            # Orders
            orders.topic=orders.created
            orders.group : billing
            orders.name    Orders\\
                Service
            orders.path=/api\\u002Forders
            ! a comment
            #---
            spring.config.activate.on-profile=prod
            orders.topic=orders.created.prod
            """);

        assertThat(documents).hasSize(2);
        assertThat(documents.getFirst())
            .extracting(ConfigurationProperty::getKey, ConfigurationProperty::getValue, ConfigurationProperty::getLine)
            .containsExactly(
                tuple("orders.topic", "orders.created", 2),
                tuple("orders.group", "billing", 3),
                tuple("orders.name", "OrdersService", 4),
                tuple("orders.path", "/api/orders", 6)
            );
        assertThat(documents.get(1))
            .extracting(ConfigurationProperty::getKey, ConfigurationProperty::getLine)
            .containsExactly(
                tuple("spring.config.activate.on-profile", 9),
                tuple("orders.topic", 10)
            );
    }

    @Test
    void readsYamlFiles() {
        final var documents = JavaConfiguration.yamlDocuments("""
            spring:
              kafka:
                template:
                  default-topic: orders.created
            app:
              topics:
                - orders.created
                - "orders.deleted"
              retries: 3
              empty:
            ---
            spring.config.activate.on-profile: prod
            app.topics[0]: orders.created.prod
            """);

        assertThat(documents).hasSize(2);
        assertThat(documents.getFirst())
            .extracting(ConfigurationProperty::getKey, ConfigurationProperty::getValue, ConfigurationProperty::getLine)
            .containsExactly(
                tuple("spring.kafka.template.default-topic", "orders.created", 4),
                tuple("app.topics[0]", "orders.created", 7),
                tuple("app.topics[1]", "orders.deleted", 8),
                tuple("app.retries", "3", 9),
                tuple("app.empty", "", 10)
            );
        assertThat(documents.get(1))
            .extracting(ConfigurationProperty::getKey, ConfigurationProperty::getValue)
            .containsExactly(
                tuple("spring.config.activate.on-profile", "prod"),
                tuple("app.topics[0]", "orders.created.prod")
            );
    }

    @Test
    void findsTheKeysOfPlaceholders() {
        final var keys = new ArrayList<String>();

        JavaConfiguration.placeholders("${orders.topic} and ${orders.group:${ORDERS_GROUP:billing}} and ${ ignored", keys::add);

        assertThat(keys).containsExactly("orders.topic", "orders.group", "ORDERS_GROUP");
    }

    @Test
    void keepsThePropertiesTheCodeRefersTo() throws IOException {
        write("pom.xml", "<project><modules><module>library</module><module>app</module></modules></project>");
        write("library/pom.xml", "<project/>");
        write("library/src/main/java/com/example/events/OrderListener.java", """
            package com.example.events;

            class OrderListener {
                static final String GROUP = "${orders.group}";

                @KafkaListener(topics = "${orders.topic}", groupId = GROUP)
                void created(Object event) { }

                void send(Environment environment) {
                    environment.getProperty("orders.dead-letter");
                }
            }
            """);
        write("library/src/main/java/com/example/events/BillingProperties.java", """
            package com.example.events;

            @ConfigurationProperties(prefix = "billing")
            record BillingProperties(String topic, String password) { }
            """);
        write("app/pom.xml", "<project/>");
        write("app/src/main/java/com/example/App.java", """
            package com.example;

            class App { }
            """);
        write("app/src/main/resources/application.yml", """
            spring:
              application:
                name: orders
              datasource:
                url: jdbc:postgresql://db/orders
                password: hunter2
              kafka:
                bootstrap-servers: kafka:9092
                template:
                  default-topic: orders.default
              cloud:
                stream:
                  bindings:
                    orderCreated-in-0:
                      destination: orders.created
                      binder: kafka
            orders:
              prefix: orders
              topic: ${orders.prefix}.created
              group: billing
              deadLetter: orders.dead
              unused: anything
            billing:
              topic: billing.charged
              password: hunter2
              url: amqp://billing:hunter2@rabbit/billing
            """);
        write("app/src/main/resources/application-prod.properties", """
            orders.topic=orders.created.prod
            orders.unused=anything
            """);

        final var app = module(scan(), "app");

        assertThat(app.getConfiguration())
            .extracting(
                ConfigurationProperty::getKey,
                ConfigurationProperty::getValue,
                ConfigurationProperty::getFile,
                ConfigurationProperty::getProfile
            )
            .containsExactly(
                tuple("spring.application.name", "orders", "app/src/main/resources/application.yml", null),
                tuple("spring.kafka.template.default-topic", "orders.default", "app/src/main/resources/application.yml", null),
                tuple("spring.cloud.stream.bindings.orderCreated-in-0.destination", "orders.created", "app/src/main/resources/application.yml", null),
                tuple("orders.prefix", "orders", "app/src/main/resources/application.yml", null),
                tuple("orders.topic", "${orders.prefix}.created", "app/src/main/resources/application.yml", null),
                tuple("orders.group", "billing", "app/src/main/resources/application.yml", null),
                tuple("orders.deadLetter", "orders.dead", "app/src/main/resources/application.yml", null),
                tuple("billing.topic", "billing.charged", "app/src/main/resources/application.yml", null),
                tuple("orders.topic", "orders.created.prod", "app/src/main/resources/application-prod.properties", "prod")
            );

        assertThat(module(scan(), "library").getConfiguration()).isNull();
    }

    private List<Module> scan() {
        final var target = new ScanTarget();
        target.setPath(".");
        target.setLanguage(ScanTarget.Language.JAVA);
        target.setBuildSystem(ScanTarget.BuildSystem.MAVEN);

        return new JavaScanner().scan(repository, target).getModules();
    }

    private static Module module(
        final List<Module> modules,
        final String path
    ) {
        return modules.stream()
            .filter(module -> module.getPath().equals(path))
            .findFirst()
            .orElseThrow();
    }

    private void write(
        final String path,
        final String content
    ) throws IOException {
        final var file = repository.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
