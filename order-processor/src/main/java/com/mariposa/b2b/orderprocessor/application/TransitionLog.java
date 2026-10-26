package com.mariposa.b2b.orderprocessor.application;

import org.slf4j.Logger;
import org.slf4j.spi.LoggingEventBuilder;

/**
 * Una linea de log por transicion del anexo de observabilidad (received, validated,
 * enriched, evaluated, persisted, published, dlt, duplicate, stale). El nombre va en
 * el mensaje y en el campo transition; los detalles, como pares clave-valor, que el
 * formato JSON emite como campos propios. orderId, eventId, eventVersion, partition y
 * offset no se repiten aqui: los pone el MDC del listener.
 */
public final class TransitionLog {

    /** Clave MDC con el resultado del pedido, una vez que se conoce. */
    public static final String OUTCOME = "outcome";

    private TransitionLog() {
    }

    public static void info(Logger log, String transition, Object... keyValues) {
        emit(log.atInfo(), transition, keyValues);
    }

    public static void warn(Logger log, String transition, Object... keyValues) {
        emit(log.atWarn(), transition, keyValues);
    }

    private static void emit(LoggingEventBuilder event, String transition, Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("keyValues must come in pairs");
        }
        LoggingEventBuilder builder = event.addKeyValue("transition", transition);
        for (int i = 0; i < keyValues.length; i += 2) {
            builder = builder.addKeyValue((String) keyValues[i], keyValues[i + 1]);
        }
        builder.log(transition);
    }
}
