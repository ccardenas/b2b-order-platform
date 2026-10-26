package com.mariposa.b2b.orderprocessor.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mariposa.b2b.orderprocessor.KafkaTestContainer;
import com.mariposa.b2b.orderprocessor.application.port.out.OrderResultStore;
import com.mariposa.b2b.orderprocessor.application.port.out.OrderStorageException;
import com.mariposa.b2b.orderprocessor.application.port.out.ProcessedEventPublisher;
import com.mariposa.b2b.orderprocessor.application.port.out.ProcessedOrder;
import com.mariposa.b2b.orderprocessor.domain.model.StoredRevision;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Recuperacion ante fallos de infraestructura, con fallos inyectados en los puertos
 * reales (los adaptadores de verdad siguen debajo):
 * - la publicacion inmediata falla y el OutboxRelay la recupera (§5.4, §7.2);
 * - Mongo falla y el mensaje se reintenta entero (§5.3) hasta tener exito o ir a la DLT.
 */
class RecoveryIT extends FlowTestSupport {

    private static final Duration WAIT = Duration.ofSeconds(30);

    /** orderIds con este prefijo: la publicacion inmediata falla siempre. */
    static final String FAIL_PUBLISH = "ORD-FAILPUB-";
    /** orderIds con este prefijo: las dos primeras escrituras fallan. */
    static final String FLAKY_MONGO = "ORD-FLAKY-";
    /** orderIds con este prefijo: Mongo nunca responde. */
    static final String MONGO_DOWN = "ORD-DOWN-";

    static final Map<String, AtomicInteger> saveAttempts = new ConcurrentHashMap<>();

    @TestConfiguration
    static class FaultInjection {

        @Bean
        @Primary
        ProcessedEventPublisher failingPublisher(@Qualifier("processedEventPublisher") ProcessedEventPublisher real) {
            return processed -> {
                if (processed.order().orderId().startsWith(FAIL_PUBLISH)) {
                    throw new IllegalStateException("broker unavailable (injected)");
                }
                real.publish(processed);
            };
        }

        @Bean
        @Primary
        OrderResultStore failingStore(@Qualifier("orderResultStore") OrderResultStore real) {
            return new OrderResultStore() {
                @Override
                public Optional<StoredRevision> findRevision(String orderId) {
                    if (orderId.startsWith(MONGO_DOWN)) {
                        throw new OrderStorageException("could not read order revision", new RuntimeException("injected"));
                    }
                    return real.findRevision(orderId);
                }

                @Override
                public WriteResult save(ProcessedOrder processed) {
                    String orderId = processed.order().orderId();
                    int attempt = saveAttempts.computeIfAbsent(orderId, id -> new AtomicInteger()).incrementAndGet();
                    if (orderId.startsWith(FLAKY_MONGO) && attempt <= 2) {
                        throw new OrderStorageException("conditional write failed", new RuntimeException("injected"));
                    }
                    return real.save(processed);
                }

                @Override
                public void markPublished(String orderId, String outboxEventId, Instant publishedAt) {
                    real.markPublished(orderId, outboxEventId, publishedAt);
                }
            };
        }
    }

    private final ObjectMapper json = new ObjectMapper();

    private void stubHappyApis() {
        stubClient("CLI-1", "ACTIVE", "RETAIL");
        stubProduct("PRD-001", "ACTIVE", "STANDARD");
    }

    @Test
    void whenTheImmediatePublicationFailsTheRelayPublishesTheSameEventOnce() throws IOException, InterruptedException {
        String orderId = FAIL_PUBLISH + newOrderId();
        stubHappyApis();

        publish(orderId, event("EVT-1", 1, orderId, "CLI-1", item("PRD-001", 1, "10.00")));

        // El resultado es durable aunque no se haya podido publicar.
        await().atMost(WAIT).until(() -> order(orderId) != null);
        Document pendingOutbox = order(orderId).get("outbox", Document.class);
        String outboxEventId = pendingOutbox.getString("eventId");

        await().atMost(WAIT).until(() -> !records(KafkaTestContainer.PROCESSED, orderId).isEmpty());
        await().atMost(WAIT).until(() -> "PUBLISHED".equals(order(orderId).get("outbox", Document.class).getString("status")));

        ConsumerRecord<String, byte[]> relayed = records(KafkaTestContainer.PROCESSED, orderId).get(0);
        assertThat(json.readTree(relayed.value()).get("eventId").asText()).isEqualTo(outboxEventId);
        Document outbox = order(orderId).get("outbox", Document.class);
        assertThat(outbox.getInteger("publishAttempts")).isEqualTo(1);
        // Lo publico el relay, no el camino rapido: respeto la antiguedad minima (1 s en este test).
        assertThat(Duration.between(outbox.getDate("createdAt").toInstant(), outbox.getDate("publishedAt").toInstant()))
                .isGreaterThanOrEqualTo(Duration.ofSeconds(1));

        // Varias pasadas mas del relay (cada 300 ms) no vuelven a publicar un evento ya PUBLISHED.
        Thread.sleep(1_500);
        assertThat(records(KafkaTestContainer.PROCESSED, orderId)).hasSize(1);
        assertThat(records(KafkaTestContainer.DLT, orderId)).isEmpty();
    }

    @Test
    void aTransientMongoFailureIsRetriedAtMessageLevelAndEndsPublishedOnce() {
        String orderId = FLAKY_MONGO + newOrderId();
        stubHappyApis();

        publish(orderId, event("EVT-1", 1, orderId, "CLI-1", item("PRD-001", 1, "10.00")));

        await().atMost(WAIT).until(() -> records(KafkaTestContainer.PROCESSED, orderId).size() == 1);
        assertThat(saveAttempts.get(orderId)).hasValue(3);
        assertThat(order(orderId).getString("status")).isEqualTo("APPROVED");
        assertThat(records(KafkaTestContainer.DLT, orderId)).isEmpty();
    }

    @Test
    void whenMongoStaysDownTheMessageGoesToTheDltAfterAllAttemptsAndThePartitionMovesOn() {
        String downOrderId = MONGO_DOWN + newOrderId();
        String nextOrderId = newOrderId();
        stubHappyApis();
        String event = event("EVT-1", 1, downOrderId, "CLI-1", item("PRD-001", 1, "10.00"));

        publish(downOrderId, event);

        await().atMost(WAIT).until(() -> !records(KafkaTestContainer.DLT, downOrderId).isEmpty());
        List<ConsumerRecord<String, byte[]>> dead = records(KafkaTestContainer.DLT, downOrderId);
        assertThat(dead).hasSize(1);
        assertThat(header(dead.get(0), "x-error-category")).isEqualTo("PERSISTENCE");
        assertThat(header(dead.get(0), "x-error-cause")).isEqualTo("could not read order revision (RuntimeException)");
        assertThat(header(dead.get(0), "x-attempts")).isEqualTo("6");
        assertThat(new String(dead.get(0).value())).isEqualTo(event);
        assertThat(records(KafkaTestContainer.PROCESSED, downOrderId)).isEmpty();

        publish(nextOrderId, event("EVT-1", 1, nextOrderId, "CLI-1", item("PRD-001", 1, "10.00")));
        await().atMost(WAIT).until(() -> records(KafkaTestContainer.PROCESSED, nextOrderId).size() == 1);
    }
}
