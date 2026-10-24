package com.mariposa.b2b.orderprocessor.domain.model;

import java.util.List;

/**
 * Representacion cruda del evento orders.created.v1, tal como la arma el
 * mapper de infraestructura a partir del DTO de Kafka. market y currency
 * quedan como String (no como enum) a proposito: validar "mercado
 * soportado" y "moneda coherente con el mercado" es trabajo de
 * {@link com.mariposa.b2b.orderprocessor.domain.validation.OrderValidator},
 * y ese trabajo necesita poder recibir un valor no soportado sin que la
 * construccion del objeto falle antes de tiempo.
 */
public record IncomingOrder(
        String eventId,
        Integer eventVersion,
        String orderId,
        String market,
        String currency,
        String clientId,
        List<OrderItem> items
) {
}
