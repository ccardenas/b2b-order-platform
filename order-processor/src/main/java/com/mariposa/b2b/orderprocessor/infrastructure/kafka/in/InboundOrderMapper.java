package com.mariposa.b2b.orderprocessor.infrastructure.kafka.in;

import com.mariposa.b2b.orderprocessor.domain.model.IncomingOrder;
import com.mariposa.b2b.orderprocessor.domain.model.OrderItem;


/** DTO de Kafka -> modelo de dominio, sin validar: eso lo hace OrderValidator. */
final class InboundOrderMapper {

    private InboundOrderMapper() {
    }

    static IncomingOrder toDomain(OrderCreatedMessage message) {
        return new IncomingOrder(
                message.eventId(),
                message.eventVersion(),
                message.orderId(),
                message.market(),
                message.currency(),
                message.clientId(),
                message.items() == null ? null : message.items().stream().map(InboundOrderMapper::item).toList()
        );
    }

    /** Un elemento null en items se mapea a una linea vacia para que el validador lo rechace por campo. */
    private static OrderItem item(OrderCreatedMessage.Item item) {
        if (item == null) {
            return new OrderItem(null, null, null);
        }
        return new OrderItem(item.productId(), item.quantity(), item.unitPrice());
    }
}
