package com.mariposa.b2b.orderprocessor.domain.model;

import java.util.List;

/**
 * Resultado de una validacion exitosa de {@link IncomingOrder}: mismo
 * contenido, pero con market/currency ya tipados como enum y eventVersion
 * garantizado. Solo {@link com.mariposa.b2b.orderprocessor.domain.validation.OrderValidator}
 * deberia construir instancias de este tipo.
 */
public record ValidatedOrder(
        String eventId,
        int eventVersion,
        String orderId,
        Market market,
        Currency currency,
        String clientId,
        List<OrderItem> items
) {
}
