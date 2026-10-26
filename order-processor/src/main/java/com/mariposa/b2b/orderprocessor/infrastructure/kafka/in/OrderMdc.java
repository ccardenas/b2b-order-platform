package com.mariposa.b2b.orderprocessor.infrastructure.kafka.in;

import com.mariposa.b2b.orderprocessor.application.TransitionLog;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.MDC;

/**
 * Contexto de log de un registro (anexo de observabilidad): orderId, eventId,
 * eventVersion, partition y offset. Se pone al recibir el registro y se limpia al
 * terminar; el recoverer de la DLT lo vuelve a poner porque corre despues.
 */
public final class OrderMdc {

    static final String ORDER_ID = "orderId";
    static final String EVENT_ID = "eventId";
    static final String EVENT_VERSION = "eventVersion";
    static final String PARTITION = "partition";
    static final String OFFSET = "offset";

    private OrderMdc() {
    }

    public static void put(ConsumerRecord<?, ?> record) {
        MDC.put(PARTITION, String.valueOf(record.partition()));
        MDC.put(OFFSET, String.valueOf(record.offset()));
        if (record.value() instanceof InboundOrder inbound) {
            putIfPresent(ORDER_ID, inbound.message().orderId());
            putIfPresent(EVENT_ID, inbound.message().eventId());
            if (inbound.message().eventVersion() != null) {
                MDC.put(EVENT_VERSION, String.valueOf(inbound.message().eventVersion()));
            }
        }
        if (MDC.get(ORDER_ID) == null && record.key() instanceof String key) {
            // Mensaje ilegible: la clave del topico es el orderId.
            MDC.put(ORDER_ID, key);
        }
    }

    public static void clear() {
        MDC.remove(ORDER_ID);
        MDC.remove(EVENT_ID);
        MDC.remove(EVENT_VERSION);
        MDC.remove(PARTITION);
        MDC.remove(OFFSET);
        MDC.remove(TransitionLog.OUTCOME);
    }

    private static void putIfPresent(String key, String value) {
        if (value != null) {
            MDC.put(key, value);
        }
    }
}
