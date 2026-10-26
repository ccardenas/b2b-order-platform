package com.mariposa.b2b.orderprocessor.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mariposa.b2b.orderprocessor.ContractSchemas;
import com.mariposa.b2b.orderprocessor.KafkaTestContainer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Flujo completo de §5.1 y filas de §5.2: Kafka -> order-processor -> APIs (WireMock)
 * -> Mongo -> orders.processed.v1 / orders.processing.dlt.
 */
class OrderProcessingFlowIT extends FlowTestSupport {

    private static final Duration WAIT = Duration.ofSeconds(30);

    private final ObjectMapper json = new ObjectMapper();

    private List<ConsumerRecord<String, byte[]>> awaitProcessed(String orderId, int count) {
        await().atMost(WAIT).until(() -> records(KafkaTestContainer.PROCESSED, orderId).size() >= count);
        return records(KafkaTestContainer.PROCESSED, orderId);
    }

    private ConsumerRecord<String, byte[]> awaitDeadLetter(String orderId) {
        await().atMost(WAIT).until(() -> !records(KafkaTestContainer.DLT, orderId).isEmpty());
        List<ConsumerRecord<String, byte[]>> dead = records(KafkaTestContainer.DLT, orderId);
        assertThat(dead).as("exactly one DLT record").hasSize(1);
        return dead.get(0);
    }

    private JsonNode body(ConsumerRecord<String, byte[]> record) throws IOException {
        return json.readTree(record.value());
    }

    private static void assertOnlyProposalHeaders(ConsumerRecord<String, byte[]> dead) {
        List<String> keys = StreamSupport.stream(dead.headers().spliterator(), false).map(Header::key).toList();
        assertThat(keys).contains("x-order-id", "x-error-category", "x-error-cause", "x-attempts", "x-failed-at",
                "x-component", "x-original-topic", "x-original-partition", "x-original-offset");
        // Sin los headers de Spring: llevan el stack trace y el mensaje crudo de la excepcion.
        assertThat(keys).noneMatch(key -> key.startsWith("kafka_dlt-") || key.startsWith("springDeserializerException"));
        assertThat(header(dead, "x-component")).isEqualTo("order-processor");
        assertThat(header(dead, "x-original-topic")).isEqualTo(KafkaTestContainer.INPUT);
    }

    // ---------------------------------------------------------------- resultados de negocio

    @Test
    void anApprovedOrderIsPersistedPublishedAndMatchesTheContract() throws IOException {
        String orderId = newOrderId();
        stubClient("CLI-W", "ACTIVE", "WHOLESALE");
        stubProduct("PRD-001", "ACTIVE", "STANDARD");
        stubProduct("PRD-008", "ACTIVE", "REDUCED");

        publish(orderId, event("EVT-1", 1, orderId, "CLI-W", item("PRD-001", 24, "35.5"), item("PRD-008", 12, "82.0")));

        ConsumerRecord<String, byte[]> processed = awaitProcessed(orderId, 1).get(0);
        assertThat(ContractSchemas.violations("orders.processed.v1", processed.value())).isEmpty();
        JsonNode event = body(processed);
        assertThat(event.get("status").asText()).isEqualTo("APPROVED");
        // PRD-001: 852.00 - 25.56 (3%) = 826.44 + 16% = 958.67. PRD-008: 984.00 sin descuento (12 u.) + 8% = 1062.72.
        assertThat(event.get("totals").get("grandTotal").decimalValue()).isEqualByComparingTo("2021.39");
        assertThat(event.get("sourceEventId").asText()).isEqualTo("EVT-1");

        await().atMost(WAIT).until(() -> "PUBLISHED".equals(order(orderId).get("outbox", Document.class).getString("status")));
        Document stored = order(orderId);
        assertThat(stored.getString("status")).isEqualTo("APPROVED");
        assertThat(stored.get("totals", Document.class).get("grandTotal")).isEqualTo(new Decimal128(new java.math.BigDecimal("2021.39")));
        assertThat(stored.get("outbox", Document.class).getString("eventId")).isEqualTo(event.get("eventId").asText());
        assertThat(stored.get("trace", Document.class).getString("sourceTopic")).isEqualTo(KafkaTestContainer.INPUT);
    }

    @Test
    void aMissingClientIsARejectionThatIsPublished() throws IOException {
        String orderId = newOrderId();
        stubClientNotFound("CLI-404");
        stubProduct("PRD-001", "ACTIVE", "STANDARD");

        publish(orderId, event("EVT-1", 1, orderId, "CLI-404", item("PRD-001", 1, "10.00")));

        ConsumerRecord<String, byte[]> processed = awaitProcessed(orderId, 1).get(0);
        assertThat(ContractSchemas.violations("orders.processed.v1", processed.value())).isEmpty();
        JsonNode event = body(processed);
        assertThat(event.get("status").asText()).isEqualTo("REJECTED");
        assertThat(event.get("reason").asText()).isEqualTo("CLIENT_NOT_FOUND");
        assertThat(event.get("totals").isNull()).isTrue();
        assertThat(records(KafkaTestContainer.DLT, orderId)).isEmpty();
    }

    // ---------------------------------------------------------------- idempotencia (§6.3)

    @Test
    void aRedeliveredEventIsPublishedOnlyOnce() throws IOException {
        String orderId = newOrderId();
        stubClient("CLI-1", "ACTIVE", "RETAIL");
        stubProduct("PRD-001", "ACTIVE", "STANDARD");
        String v1 = event("EVT-1", 1, orderId, "CLI-1", item("PRD-001", 1, "10.00"));

        publish(orderId, v1);
        publish(orderId, v1);
        // Misma clave = misma particion = orden garantizado: cuando aparece v2, los dos v1 ya se procesaron.
        publish(orderId, event("EVT-2", 2, orderId, "CLI-1", item("PRD-001", 2, "10.00")));

        await().atMost(WAIT).until(() -> records(KafkaTestContainer.PROCESSED, orderId).stream()
                .anyMatch(record -> new String(record.value(), StandardCharsets.UTF_8).contains("\"eventVersion\":2")));
        List<Integer> versions = new ArrayList<>();
        for (ConsumerRecord<String, byte[]> record : records(KafkaTestContainer.PROCESSED, orderId)) {
            versions.add(body(record).get("eventVersion").asInt());
        }
        assertThat(versions).containsExactly(1, 2);
        assertThat(records(KafkaTestContainer.DLT, orderId)).isEmpty();
    }

    @Test
    void anOlderVersionAfterANewerOneIsDiscardedWithoutPublishing() throws IOException {
        String orderId = newOrderId();
        stubClient("CLI-1", "ACTIVE", "RETAIL");
        stubProduct("PRD-001", "ACTIVE", "STANDARD");

        publish(orderId, event("EVT-2", 2, orderId, "CLI-1", item("PRD-001", 2, "10.00")));
        publish(orderId, event("EVT-1", 1, orderId, "CLI-1", item("PRD-001", 1, "10.00")));
        publish(orderId, event("EVT-3", 3, orderId, "CLI-1", item("PRD-001", 3, "10.00")));

        await().atMost(WAIT).until(() -> records(KafkaTestContainer.PROCESSED, orderId).size() >= 2);
        List<Integer> versions = new ArrayList<>();
        for (ConsumerRecord<String, byte[]> record : records(KafkaTestContainer.PROCESSED, orderId)) {
            versions.add(body(record).get("eventVersion").asInt());
        }
        assertThat(versions).containsExactly(2, 3);
        assertThat(order(orderId).getInteger("eventVersion")).isEqualTo(3);
        assertThat(records(KafkaTestContainer.DLT, orderId)).isEmpty();
    }

    @Test
    void theSameVersionWithAnotherEventIdGoesToTheDltAndKeepsTheFirstResult() {
        String orderId = newOrderId();
        stubClient("CLI-1", "ACTIVE", "RETAIL");
        stubProduct("PRD-001", "ACTIVE", "STANDARD");
        publish(orderId, event("EVT-1", 1, orderId, "CLI-1", item("PRD-001", 1, "10.00")));
        awaitProcessed(orderId, 1);

        String conflicting = event("EVT-OTHER", 1, orderId, "CLI-1", item("PRD-001", 5, "10.00"));
        publish(orderId, conflicting);

        ConsumerRecord<String, byte[]> dead = awaitDeadLetter(orderId);
        assertThat(header(dead, "x-error-category")).isEqualTo("VERSION_CONFLICT");
        assertThat(header(dead, "x-event-id")).isEqualTo("EVT-OTHER");
        assertThat(new String(dead.value(), StandardCharsets.UTF_8)).isEqualTo(conflicting);
        assertThat(order(orderId).getString("eventId")).isEqualTo("EVT-1");
        assertThat(records(KafkaTestContainer.PROCESSED, orderId)).hasSize(1);
    }

    // ---------------------------------------------------------------- DLT (§5.2, §5.5)

    @Test
    void anUnreadableMessageGoesToTheDltWithItsOriginalBytesAndDoesNotBlockThePartition() {
        String orderId = newOrderId();
        byte[] garbage = "{\"orderId\": not-json \u0000".getBytes(StandardCharsets.UTF_8);
        stubClient("CLI-1", "ACTIVE", "RETAIL");
        stubProduct("PRD-001", "ACTIVE", "STANDARD");

        publish(orderId, garbage);
        publish(orderId, event("EVT-1", 1, orderId, "CLI-1", item("PRD-001", 1, "10.00")));

        ConsumerRecord<String, byte[]> dead = awaitDeadLetter(orderId);
        assertThat(dead.value()).isEqualTo(garbage);
        assertThat(dead.key()).isEqualTo(orderId);
        assertThat(header(dead, "x-error-category")).isEqualTo("DESERIALIZATION");
        assertThat(header(dead, "x-order-id")).isEqualTo(orderId);
        assertThat(header(dead, "x-error-cause")).doesNotContain("not-json");
        assertOnlyProposalHeaders(dead);
        // El siguiente mensaje de la misma particion se proceso: el ilegible no la bloqueo.
        awaitProcessed(orderId, 1);
    }

    @Test
    void anInvalidContractGoesToTheDltAndIsNotPersisted() {
        String orderId = newOrderId();
        String invalid = event("EVT-1", 1, orderId, "CLI-1", item("PRD-001", 1, "10.00")).replace("\"MXN\"", "\"PEN\"");

        publish(orderId, invalid);

        ConsumerRecord<String, byte[]> dead = awaitDeadLetter(orderId);
        assertThat(new String(dead.value(), StandardCharsets.UTF_8)).isEqualTo(invalid);
        assertThat(header(dead, "x-error-category")).isEqualTo("VALIDATION");
        assertThat(header(dead, "x-error-cause")).isEqualTo("CURRENCY_MARKET_MISMATCH");
        assertThat(header(dead, "x-event-id")).isEqualTo("EVT-1");
        assertThat(header(dead, "x-attempts")).isEqualTo("1");
        assertOnlyProposalHeaders(dead);
        assertThat(order(orderId)).isNull();
        assertThat(apis.getAllServeEvents()).as("no API call for an invalid order").isEmpty();
    }

    @Test
    void exhaustedRetriesPersistATechnicalFailureAndGoToTheDltWithoutPublishing() {
        String orderId = newOrderId();
        stubClient("CLI-1", "ACTIVE", "RETAIL");
        stubProductStatus("PRD-503", 503);

        publish(orderId, event("EVT-1", 1, orderId, "CLI-1", item("PRD-503", 1, "10.00")));

        ConsumerRecord<String, byte[]> dead = awaitDeadLetter(orderId);
        assertThat(header(dead, "x-error-category")).isEqualTo("EXTERNAL_TRANSIENT");
        assertThat(header(dead, "x-error-cause")).isEqualTo("products-api: HTTP 503 after 3 attempt(s)");
        Document stored = order(orderId);
        assertThat(stored.getString("status")).isEqualTo("TECHNICAL_FAILURE");
        assertThat(stored.containsKey("outbox")).isFalse();
        assertThat(records(KafkaTestContainer.PROCESSED, orderId)).isEmpty();
    }

    @Test
    void anUnknownEnumFromAnApiIsAPermanentTechnicalFailure() {
        String orderId = newOrderId();
        stubClient("CLI-1", "ACTIVE", "RETAIL");
        stubProduct("PRD-BAD", "ARCHIVED", "STANDARD");

        publish(orderId, event("EVT-1", 1, orderId, "CLI-1", item("PRD-BAD", 1, "10.00")));

        ConsumerRecord<String, byte[]> dead = awaitDeadLetter(orderId);
        assertThat(header(dead, "x-error-category")).isEqualTo("EXTERNAL_PERMANENT");
        assertThat(header(dead, "x-error-cause")).doesNotContain("ARCHIVED");
        assertThat(order(orderId).getString("status")).isEqualTo("TECHNICAL_FAILURE");
    }

    // ---------------------------------------------------------------- arranque

    @Test
    void createsTheIndexesOnStartup() {
        List<String> names = orders().listIndexes().map(index -> index.getString("name")).into(new ArrayList<>());

        assertThat(names).contains("_id_", "eventId_1", "outbox_pending_createdAt", "status_1_processedAt_-1");
    }
}
