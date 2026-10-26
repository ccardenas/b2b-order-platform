package com.mariposa.b2b.orderprocessor.infrastructure.kafka.out;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bson.types.Decimal128;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Convierte outbox.payload (BSON) en el JSON de orders.processed.v1. Lo usan la
 * publicacion inmediata y el relay, asi ambos producen exactamente los mismos bytes.
 * Decimal128 -> numero JSON sin notacion cientifica (958.67, nunca 9.5867E+2);
 * Date -> ISO-8601 UTC. Los null se conservan: el esquema exige totals y reason.
 */
public final class ProcessedEventJson {

    private final ObjectMapper mapper;

    public ProcessedEventJson(ObjectMapper baseMapper) {
        this.mapper = baseMapper.copy().enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN);
    }

    public byte[] toJson(Map<String, Object> payload) {
        try {
            return mapper.writeValueAsBytes(plain(payload));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not serialize processed event", e);
        }
    }

    private static Object plain(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, nested) -> copy.put((String) key, plain(nested)));
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            list.forEach(nested -> copy.add(plain(nested)));
            return copy;
        }
        if (value instanceof Decimal128 decimal) {
            return decimal.bigDecimalValue();
        }
        if (value instanceof Date date) {
            return date.toInstant().toString();
        }
        return value;
    }
}
