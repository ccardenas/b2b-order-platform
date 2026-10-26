package com.mariposa.b2b.orderprocessor.infrastructure.kafka.in;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.List;

/**
 * DTO de orders.created.v1 (contracts/events/orders.created.v1.schema.json). Todo
 * es nullable a proposito: la presencia de los campos la valida el dominio
 * (OrderValidator) y un faltante es VALIDATION, no DESERIALIZATION. Los campos
 * extra (occurredAt, channel, futuros) se ignoran: el esquema es additionalProperties.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderCreatedMessage(
        String eventId,
        Integer eventVersion,
        String orderId,
        String market,
        String currency,
        String clientId,
        List<Item> items
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(String productId, Integer quantity, BigDecimal unitPrice) {
    }
}
