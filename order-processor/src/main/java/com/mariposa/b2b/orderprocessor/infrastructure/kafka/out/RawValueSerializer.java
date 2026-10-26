package com.mariposa.b2b.orderprocessor.infrastructure.kafka.out;

import com.mariposa.b2b.orderprocessor.infrastructure.kafka.in.InboundOrder;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.Serializer;

/**
 * Serializador de valores del productor. Nunca re-serializa: escribe bytes tal cual.
 * byte[] es el JSON de orders.processed.v1, o el valor original que el recoverer saca
 * de una DeserializationException; InboundOrder es un registro que si se pudo leer y
 * va a la DLT con sus bytes originales (§5.5).
 */
public final class RawValueSerializer implements Serializer<Object> {

    @Override
    public byte[] serialize(String topic, Object value) {
        if (value == null || value instanceof byte[]) {
            return (byte[]) value;
        }
        if (value instanceof InboundOrder inbound) {
            return inbound.raw();
        }
        throw new SerializationException("unsupported value type " + value.getClass().getName());
    }
}
