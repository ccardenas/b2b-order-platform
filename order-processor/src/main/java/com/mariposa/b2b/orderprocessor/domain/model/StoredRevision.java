package com.mariposa.b2b.orderprocessor.domain.model;

/** Lo minimo del pedido ya persistido que hace falta para decidir idempotencia. */
public record StoredRevision(int eventVersion, String eventId, OrderStatus status) {
}
