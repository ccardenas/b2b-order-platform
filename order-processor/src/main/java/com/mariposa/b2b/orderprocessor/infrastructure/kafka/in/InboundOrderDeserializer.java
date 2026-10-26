package com.mariposa.b2b.orderprocessor.infrastructure.kafka.in;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.Deserializer;

import java.io.IOException;

/**
 * Delegado del ErrorHandlingDeserializer. Lo que lanza aqui se convierte en una
 * DeserializationException que no bloquea la particion y termina en la DLT como
 * DESERIALIZATION. El mensaje de error nunca incluye el contenido recibido: las
 * excepciones de Jackson citan fragmentos del payload.
 */
public final class InboundOrderDeserializer implements Deserializer<InboundOrder> {

    private final ObjectMapper mapper;

    public InboundOrderDeserializer(ObjectMapper baseMapper) {
        ObjectMapper strict = baseMapper.copy()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                // quantity 2.5 no se trunca a 2, y eventVersion 1.0 no pasa como 1.
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
        // El contrato declara numeros: "35.50" como string es un productor roto, no un precio.
        strict.coercionConfigFor(LogicalType.Integer).setCoercion(CoercionInputShape.String, CoercionAction.Fail);
        strict.coercionConfigFor(LogicalType.Float).setCoercion(CoercionInputShape.String, CoercionAction.Fail);
        this.mapper = strict;
    }

    @Override
    public InboundOrder deserialize(String topic, byte[] data) {
        if (data == null || data.length == 0) {
            throw new SerializationException("value is empty");
        }
        OrderCreatedMessage message;
        try {
            message = mapper.readValue(data, OrderCreatedMessage.class);
        } catch (IOException e) {
            throw new SerializationException(
                    "value is not a valid orders.created.v1 JSON document (" + e.getClass().getSimpleName() + ")");
        }
        if (message == null) {
            throw new SerializationException("value is JSON null");
        }
        return new InboundOrder(message, data);
    }
}
