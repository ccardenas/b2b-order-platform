package com.mariposa.b2b.orderprocessor.infrastructure.kafka.in;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mariposa.b2b.orderprocessor.ContractSchemas;
import com.mariposa.b2b.orderprocessor.domain.model.IncomingOrder;
import com.mariposa.b2b.orderprocessor.domain.model.OrderItem;
import org.apache.kafka.common.errors.SerializationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InboundOrderDeserializerTest {

    private final InboundOrderDeserializer deserializer = new InboundOrderDeserializer(new ObjectMapper());

    private static byte[] json(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void readsTheContractFixtureKeepingPricesExactAndTheOriginalBytes() {
        byte[] raw = ContractSchemas.fixture("events/orders.created.v1/valid/approved-multi-item.json");

        InboundOrder inbound = deserializer.deserialize("orders.created.v1", raw);

        assertThat(inbound.raw()).isSameAs(raw);
        OrderCreatedMessage message = inbound.message();
        assertThat(message.orderId()).isEqualTo("ORD-MX-000147");
        assertThat(message.eventVersion()).isEqualTo(1);
        assertThat(message.items()).hasSize(2);
        // BigDecimal exacto, sin pasar por double. 35.5 conserva su escala original.
        assertThat(message.items().get(0).unitPrice()).isEqualTo(new BigDecimal("35.5"));
    }

    @Test
    void missingFieldsAreNotADeserializationProblem() {
        InboundOrder inbound = deserializer.deserialize("t", json("{\"orderId\":\"ORD-1\"}"));

        IncomingOrder order = InboundOrderMapper.toDomain(inbound.message());
        assertThat(order.orderId()).isEqualTo("ORD-1");
        assertThat(order.eventId()).isNull();
        assertThat(order.items()).isNull();
    }

    @Test
    void aNullItemBecomesAnEmptyLineForTheValidatorToReject() {
        InboundOrder inbound = deserializer.deserialize("t", json("{\"items\":[null]}"));

        assertThat(InboundOrderMapper.toDomain(inbound.message()).items()).containsExactly(new OrderItem(null, null, null));
    }

    @Test
    void ignoresUnknownFields() {
        InboundOrder inbound = deserializer.deserialize("t", json("{\"orderId\":\"ORD-1\",\"channel\":\"C1\",\"future\":{\"x\":1}}"));

        assertThat(inbound.message().orderId()).isEqualTo("ORD-1");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not json",
            "{\"orderId\":",
            "[1,2]",
            "\"a string\"",
            "null",
            "{\"eventVersion\":\"1\"}",
            "{\"eventVersion\":1.0}",
            "{\"items\":[{\"productId\":\"P\",\"quantity\":2.5,\"unitPrice\":1}]}",
            "{\"items\":[{\"productId\":\"P\",\"quantity\":2,\"unitPrice\":\"35.50\"}]}",
            "{\"orderId\":\"ORD-1\"} trailing"
    })
    void rejectsValuesThatAreNotAnOrdersCreatedDocument(String value) {
        assertThatThrownBy(() -> deserializer.deserialize("t", json(value)))
                .isInstanceOf(SerializationException.class);
    }

    @Test
    void theErrorMessageNeverQuotesThePayload() {
        assertThatThrownBy(() -> deserializer.deserialize("t", json("{\"clientId\": CLI-SECRET-123}")))
                .isInstanceOf(SerializationException.class)
                .message().doesNotContain("SECRET").startsWith("value is not a valid orders.created.v1 JSON document");
    }

    @Test
    void rejectsAnEmptyOrNullValue() {
        assertThatThrownBy(() -> deserializer.deserialize("t", null)).isInstanceOf(SerializationException.class);
        assertThatThrownBy(() -> deserializer.deserialize("t", new byte[0])).isInstanceOf(SerializationException.class);
    }
}
