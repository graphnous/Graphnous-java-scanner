package dev.graphnous.scanner;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SecretsTest {

    @Test
    void tellsNamesOfSecrets() {
        assertThat(Secrets.namesSecret("spring.datasource.password")).isTrue();
        assertThat(Secrets.namesSecret("API_KEY")).isTrue();
        assertThat(Secrets.namesSecret("dbPassword")).isTrue();
        assertThat(Secrets.namesSecret("client-secret")).isTrue();
        assertThat(Secrets.namesSecret("github.access_token")).isTrue();
        assertThat(Secrets.namesSecret("private-key")).isTrue();

        assertThat(Secrets.namesSecret("spring.rabbitmq.template.routing-key")).isFalse();
        assertThat(Secrets.namesSecret("orders.topic")).isFalse();
        assertThat(Secrets.namesSecret("cache.key-prefix")).isFalse();
    }

    @Test
    void tellsValuesThatHoldSecrets() {
        assertThat(Secrets.holdsSecret("jdbc:postgresql://app:hunter2@db/orders")).isTrue();
        assertThat(Secrets.holdsSecret("amqp://guest:guest@rabbit:5672")).isTrue();
        assertThat(Secrets.holdsSecret("PlainLoginModule required username=\"a\" password=\"b\";")).isTrue();
        assertThat(Secrets.holdsSecret("-----BEGIN PRIVATE KEY-----")).isTrue();

        assertThat(Secrets.holdsSecret("jdbc:postgresql://db/orders")).isFalse();
        assertThat(Secrets.holdsSecret("https://user@example.com/orders")).isFalse();
        assertThat(Secrets.holdsSecret("orders.created")).isFalse();
    }
}
