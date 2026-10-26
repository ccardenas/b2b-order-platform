package com.mariposa.b2b.orderprocessor.application.port.out;

import com.mariposa.b2b.orderprocessor.domain.model.StoredRevision;

import java.time.Instant;
import java.util.Optional;

public interface OrderResultStore {

    enum WriteResult {
        APPLIED,
        DUPLICATE,
        STALE,
        VERSION_CONFLICT
    }

    /** Lectura para el chequeo temprano (§5.1 paso 3). No es una garantia de idempotencia. */
    Optional<StoredRevision> findRevision(String orderId);

    /**
     * Escritura condicional atomica (§6.2). Es la unica garantia de idempotencia:
     * el resultado distinto de APPLIED significa que no se escribio nada.
     *
     * @throws OrderStorageException si la base no esta disponible
     */
    WriteResult save(ProcessedOrder processed);

    /** Marca el outbox PUBLISHED solo si sigue PENDING con ese eventId (§7.2). */
    void markPublished(String orderId, String outboxEventId, Instant publishedAt);
}
