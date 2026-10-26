package com.mariposa.b2b.orderprocessor.infrastructure.kafka.in;

import com.mariposa.b2b.orderprocessor.domain.model.ErrorCategory;

/**
 * El caso de uso termino el mensaje con un resultado que va a la DLT (VALIDATION,
 * VERSION_CONFLICT, EXTERNAL_*). Se lanza para que el DefaultErrorHandler lo envie
 * con el DeadLetterPublishingRecoverer sin reintentar: asi todos los envios a la DLT,
 * incluidos los de deserializacion, salen por el mismo camino y con los mismos headers.
 */
public final class DeadLetterException extends RuntimeException {

    private final ErrorCategory category;

    public DeadLetterException(ErrorCategory category, String cause) {
        super(cause);
        this.category = category;
    }

    public ErrorCategory category() {
        return category;
    }
}
