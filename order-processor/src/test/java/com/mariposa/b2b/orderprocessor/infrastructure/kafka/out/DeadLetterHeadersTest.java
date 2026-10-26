package com.mariposa.b2b.orderprocessor.infrastructure.kafka.out;

import com.mariposa.b2b.orderprocessor.application.port.out.OrderStorageException;
import com.mariposa.b2b.orderprocessor.domain.model.ErrorCategory;
import com.mariposa.b2b.orderprocessor.infrastructure.kafka.in.DeadLetterException;
import com.mariposa.b2b.orderprocessor.infrastructure.kafka.in.InboundOrder;
import com.mariposa.b2b.orderprocessor.infrastructure.kafka.in.OrderCreatedMessage;
import com.mongodb.MongoTimeoutException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.serializer.DeserializationException;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DeadLetterHeadersTest {

    private static final Instant NOW = Instant.parse("2026-10-26T15:00:00Z");

    private final DeadLetterHeaders headers = new DeadLetterHeaders(Clock.fixed(NOW, ZoneOffset.UTC));

    private static ConsumerRecord<Object, Object> record(Object key, Object value) {
        return new ConsumerRecord<>("orders.created.v1", 2, 1842L, key, value);
    }

    private static InboundOrder inbound(String orderId, String eventId) {
        return new InboundOrder(new OrderCreatedMessage(eventId, 1, orderId, "MX", "MXN", "CLI-1", List.of()), new byte[0]);
    }

    private static Map<String, String> asMap(Headers headers) {
        Map<String, String> map = new LinkedHashMap<>();
        for (Header header : headers) {
            map.put(header.key(), new String(header.value(), StandardCharsets.UTF_8));
        }
        return map;
    }

    /** Como lo entrega el contenedor: la excepcion del listener envuelta. */
    private static Exception fromListener(Exception cause) {
        return new ListenerExecutionFailedException("Listener method threw exception", cause);
    }

    @Test
    void carriesEveryHeaderOfTheProposal() {
        Map<String, String> built = asMap(headers.build(record("ORD-1", inbound("ORD-1", "EVT-1")),
                fromListener(new DeadLetterException(ErrorCategory.VALIDATION, "EMPTY_ITEMS"))));

        assertThat(built).containsExactly(
                Map.entry("x-order-id", "ORD-1"),
                Map.entry("x-event-id", "EVT-1"),
                Map.entry("x-error-category", "VALIDATION"),
                Map.entry("x-error-cause", "EMPTY_ITEMS"),
                Map.entry("x-attempts", "1"),
                Map.entry("x-failed-at", "2026-10-26T15:00:00Z"),
                Map.entry("x-component", "order-processor"),
                Map.entry("x-original-topic", "orders.created.v1"),
                Map.entry("x-original-partition", "2"),
                Map.entry("x-original-offset", "1842"));
    }

    @Test
    void aDeserializationFailureUsesTheKeyAsOrderIdAndHasNoEventId() {
        DeserializationException failure = new DeserializationException("failed to deserialize", "x".getBytes(), false,
                new SerializationException("value is not a valid orders.created.v1 JSON document (JsonParseException)"));

        Map<String, String> built = asMap(headers.build(record("ORD-KEY", null), failure));

        assertThat(built).containsEntry("x-order-id", "ORD-KEY")
                .containsEntry("x-error-category", "DESERIALIZATION")
                .containsEntry("x-error-cause", "value is not a valid orders.created.v1 JSON document (JsonParseException)")
                .doesNotContainKey("x-event-id");
    }

    @Test
    void omitsTheOrderIdWhenItCannotBeExtracted() {
        Map<String, String> built = asMap(headers.build(record(null, null),
                new DeserializationException("failed", null, false, null)));

        assertThat(built).doesNotContainKeys("x-order-id", "x-event-id");
        assertThat(built.get("x-error-cause")).isEqualTo("value could not be deserialized");
    }

    @Test
    void aPersistenceFailureReportsTheDeliveryAttemptsAndOnlyTheClassOfTheDriverError() {
        ConsumerRecord<Object, Object> record = record("ORD-1", inbound("ORD-1", "EVT-1"));
        record.headers().add(KafkaHeaders.DELIVERY_ATTEMPT, ByteBuffer.allocate(Integer.BYTES).putInt(6).array());
        var failure = new OrderStorageException("conditional write failed",
                new MongoTimeoutException("Timed out while waiting for a server mongo-prod-1.internal:27017"));

        Map<String, String> built = asMap(headers.build(record, fromListener(failure)));

        assertThat(built).containsEntry("x-error-category", "PERSISTENCE")
                .containsEntry("x-error-cause", "conditional write failed (MongoTimeoutException)")
                .containsEntry("x-attempts", "6");
    }

    @Test
    void anUnexpectedErrorExposesOnlyItsClassName() {
        Map<String, String> built = asMap(headers.build(record("ORD-1", inbound("ORD-1", "EVT-1")),
                fromListener(new IllegalStateException("client CLI-1 has secret data"))));

        assertThat(built).containsEntry("x-error-category", "UNEXPECTED")
                .containsEntry("x-error-cause", "IllegalStateException");
    }

    @Test
    void theCauseIsASingleLineOfAtMost500Characters() {
        String longCause = "line one\nline two\r\n" + "x".repeat(600);

        String sanitized = DeadLetterHeaders.sanitize(longCause);

        assertThat(sanitized).hasSize(500).startsWith("line one line two x").doesNotContain("\n", "\r");
    }
}
