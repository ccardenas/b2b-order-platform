package com.mariposa.b2b.orderprocessor.application.port.out;

import com.mariposa.b2b.orderprocessor.domain.model.ErrorCategory;

/**
 * Una dependencia HTTP no pudo dar una respuesta utilizable. Los 404 de
 * negocio NO llegan aqui: los puertos los devuelven como Optional.empty().
 * El mensaje es seguro para persistir y para la DLT: sin cuerpos de respuesta
 * ni datos del cliente.
 */
public class ExternalCallFailedException extends RuntimeException {

    public enum Kind {
        /** 429, 5xx, timeout, error de red: reintentable, aqui ya se agotaron los intentos. */
        TRANSIENT,
        /** Otros 4xx, cuerpo invalido, enum desconocido: reintentar no cambia nada. */
        PERMANENT
    }

    private final Kind kind;
    private final int attempts;

    public ExternalCallFailedException(Kind kind, String message, int attempts) {
        super(message);
        this.kind = kind;
        this.attempts = attempts;
    }

    public Kind kind() {
        return kind;
    }

    public int attempts() {
        return attempts;
    }

    public ErrorCategory category() {
        return kind == Kind.TRANSIENT ? ErrorCategory.EXTERNAL_TRANSIENT : ErrorCategory.EXTERNAL_PERMANENT;
    }
}
