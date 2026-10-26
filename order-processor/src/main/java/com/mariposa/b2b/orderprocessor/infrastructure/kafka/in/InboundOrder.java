package com.mariposa.b2b.orderprocessor.infrastructure.kafka.in;

/**
 * Valor deserializado de un registro junto con sus bytes originales. La DLT debe
 * conservar el valor original (§5.5) tambien cuando el mensaje si se pudo leer
 * (VALIDATION, VERSION_CONFLICT, fallos externos); sin esto solo quedaria el objeto
 * y habria que re-serializarlo, que no es lo mismo.
 */
public record InboundOrder(OrderCreatedMessage message, byte[] raw) {
}
