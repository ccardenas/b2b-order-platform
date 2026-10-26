package com.mariposa.b2b.orderprocessor.domain.idempotency;

public enum RevisionComparison {
    /** No hay pedido persistido, o el evento trae una version mayor: se escribe. */
    NEWER,
    /** Misma version y mismo eventId sobre un TECHNICAL_FAILURE: se reintenta (§6.2). */
    RETRY_OF_TECHNICAL_FAILURE,
    DUPLICATE,
    STALE,
    VERSION_CONFLICT;

    /** Verdadero si la escritura condicional de §6.2 aceptaria el evento. */
    public boolean isWritable() {
        return this == NEWER || this == RETRY_OF_TECHNICAL_FAILURE;
    }
}
