package ru.itmo.highload.common.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import ru.itmo.highload.common.domain.events.EventEnvelope;
import ru.itmo.highload.common.domain.events.EventType;
import ru.itmo.highload.common.testing.KafkaCluster;

@SpringBootTest(
        classes = EventDeliveryIT.App.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "logging.level.org.apache.kafka=WARN")
class EventDeliveryIT {
    static final KafkaCluster KAFKA = new KafkaCluster().start();
    static final PostgreSQLContainer<?> DB =
            new PostgreSQLContainer<>("postgres:17.11-alpine3.24").withInitScript("outbox.sql");

    static {
        DB.start();
        try {
            KAFKA.createTopics();
        } catch (Exception error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    static final Queue<UUID> RECEIVED = new ConcurrentLinkedQueue<>();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", DB::getJdbcUrl);
        p.add("spring.datasource.username", DB::getUsername);
        p.add("spring.datasource.password", DB::getPassword);
        p.add("spring.kafka.bootstrap-servers", KAFKA::bootstrapServers);
        p.add("spring.kafka.consumer.auto-offset-reset", () -> "earliest");
        p.add("spring.kafka.consumer.enable-auto-commit", () -> false);
        p.add("spring.kafka.consumer.properties.session.timeout.ms", () -> 10000);
        p.add("spring.kafka.consumer.properties.heartbeat.interval.ms", () -> 3000);
        p.add("spring.kafka.consumer.properties.metadata.max.age.ms", () -> 10000);
        p.add(
                "spring.kafka.consumer.key-deserializer",
                () -> "org.apache.kafka.common.serialization.StringDeserializer");
        p.add(
                "spring.kafka.consumer.value-deserializer",
                () -> "org.apache.kafka.common.serialization.StringDeserializer");
        p.add(
                "spring.kafka.producer.key-serializer",
                () -> "org.apache.kafka.common.serialization.StringSerializer");
        p.add(
                "spring.kafka.producer.value-serializer",
                () -> "org.apache.kafka.common.serialization.StringSerializer");
        p.add("spring.kafka.producer.acks", () -> "all");
        p.add("spring.kafka.producer.properties.enable.idempotence", () -> true);
        p.add("spring.kafka.producer.properties.request.timeout.ms", () -> 1000);
        p.add("spring.kafka.producer.properties.delivery.timeout.ms", () -> 4000);
        p.add("spring.kafka.producer.properties.max.block.ms", () -> 1000);
        p.add("catering.events.enabled", () -> true);
        p.add("catering.events.relay-delay", () -> 600000);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class App {
        @Bean
        DataSource dataSource() {
            var source = new PGSimpleDataSource();
            source.setURL(DB.getJdbcUrl());
            source.setUser(DB.getUsername());
            source.setPassword(DB.getPassword());
            return source;
        }

        @Bean
        Sink sink(EventCodec codec) {
            return new Sink(codec);
        }

        @Bean
        JdbcOutbox outbox(JdbcTemplate jdbc, ObjectMapper mapper) {
            return new JdbcOutbox(jdbc, mapper);
        }
    }

    static final class Sink {
        private final EventCodec codec;

        Sink(EventCodec codec) {
            this.codec = codec;
        }

        @KafkaListener(topics = "order-events", groupId = "delivery-test")
        public void accept(ConsumerRecord<String, String> record) throws IOException {
            RECEIVED.add(codec.decode(record).eventId());
        }
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired JdbcOutbox outbox;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager transactions;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired EventCodec codec;

    EventEnvelope event(UUID order) {
        return new EventEnvelope(
                UUID.randomUUID(),
                EventType.ORDER_CONFIRMED,
                order,
                Instant.now(),
                1,
                EventEnvelope.Payload.order(UUID.randomUUID(), 3, null));
    }

    @Test
    void rollbackRetryOrderingAndPoisonRecovery() throws Exception {
        var tx = new TransactionTemplate(transactions);
        jdbc.execute("create table processed_event(event_id uuid primary key)");
        var inbox = new JdbcInbox(jdbc);
        var inboxId = UUID.randomUUID();
        assertThatThrownBy(() -> inbox.claim(inboxId)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() ->
                        tx.executeWithoutResult(s -> {
                            assertThat(inbox.claim(inboxId)).isTrue();
                            throw new IllegalStateException("rollback inbox");
                        }))
                .isInstanceOf(IllegalStateException.class);
        tx.executeWithoutResult(s -> {
            assertThat(inbox.claim(inboxId)).isTrue();
            assertThat(inbox.claim(inboxId)).isFalse();
        });
        var first = event(UUID.randomUUID());
        var second = event(first.aggregateId());
        assertThatThrownBy(() -> outbox.append(first)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() ->
                        tx.executeWithoutResult(s -> {
                            outbox.append(first);
                            throw new IllegalStateException("rollback");
                        }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select count(*) from outbox_event", Integer.class))
                .isZero();
        tx.executeWithoutResult(s -> {
            outbox.append(first);
            outbox.append(second);
            outbox.append(first);
        });
        var failed =
                new OutboxRelay(
                        jdbc,
                        transactions,
                        (t, k, v) -> {
                            throw new TimeoutException();
                        });
        failed.publishBatch();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from outbox_event where sent_at is null",
                                Integer.class))
                .isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "select attempts from outbox_event where event_id=?",
                                Integer.class,
                                first.eventId()))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "select attempts from outbox_event where event_id=?",
                                Integer.class,
                                second.eventId()))
                .isZero();
        jdbc.update("update outbox_event set next_attempt_at=clock_timestamp()");
        var relay =
                new OutboxRelay(
                        jdbc,
                        transactions,
                        (t, k, v) -> kafka.send(t, k, v).get(5, TimeUnit.SECONDS));
        relay.publishBatch();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from outbox_event where sent_at is not null",
                                Integer.class))
                .isEqualTo(1);
        relay.publishBatch();
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() ->
                        assertThat(RECEIVED).containsExactly(first.eventId(), second.eventId()));
        String bad = "{broken";
        kafka.send("order-events", first.aggregateId().toString(), bad).get(5, TimeUnit.SECONDS);
        var after = event(first.aggregateId());
        kafka.send("order-events", after.aggregateId().toString(), mapper.writeValueAsString(after))
                .get(5, TimeUnit.SECONDS);
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(RECEIVED).contains(after.eventId()));
        var props = new Properties();
        props.put("bootstrap.servers", KAFKA.bootstrapServers());
        props.put("key.deserializer", StringDeserializer.class);
        props.put("value.deserializer", StringDeserializer.class);
        props.put("group.id", UUID.randomUUID().toString());
        props.put("enable.auto.commit", "false");
        try (var consumer = new KafkaConsumer<String, String>(props)) {
            var partitions =
                    List.of(
                            new TopicPartition("order-events.DLT", 0),
                            new TopicPartition("order-events.DLT", 1),
                            new TopicPartition("order-events.DLT", 2));
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            await().atMost(Duration.ofSeconds(15))
                    .untilAsserted(() ->
                            assertThat(consumer.poll(Duration.ofMillis(500)))
                                    .anySatisfy(rec -> assertThat(rec.value()).isEqualTo(bad)));
        }
        assertThatThrownBy(() ->
                        codec.decode(
                                new ConsumerRecord<>(
                                        "order-events",
                                        0,
                                        0L,
                                        "wrong",
                                        mapper.writeValueAsString(first))))
                .isInstanceOf(IllegalArgumentException.class);
        var recovered = event(first.aggregateId());
        KAFKA.stopAll();
        try {
            tx.executeWithoutResult(s -> outbox.append(recovered));
            relay.publishBatch();
            assertThat(
                            jdbc.queryForObject(
                                    "select sent_at is null from outbox_event where event_id=?",
                                    Boolean.class,
                                    recovered.eventId()))
                    .isTrue();
        } finally {
            KAFKA.startAll();
        }
        await().atMost(Duration.ofSeconds(90))
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> {
                    jdbc.update(
                            "update outbox_event set next_attempt_at=clock_timestamp() where sent_at is null");
                    relay.publishBatch();
                    assertThat(RECEIVED).contains(recovered.eventId());
                });
    }
}
