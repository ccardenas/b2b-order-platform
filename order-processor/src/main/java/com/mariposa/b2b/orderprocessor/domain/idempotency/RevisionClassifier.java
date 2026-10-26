package com.mariposa.b2b.orderprocessor.domain.idempotency;

import com.mariposa.b2b.orderprocessor.domain.model.OrderStatus;
import com.mariposa.b2b.orderprocessor.domain.model.StoredRevision;

import java.util.Optional;

/**
 * Tabla de clasificacion de la propuesta §6.2, como funcion pura. La usa el
 * caso de uso para el chequeo temprano (optimizacion, §5.1 paso 3) y la usara
 * el adaptador Mongo para clasificar tras un E11000. La garantia de
 * idempotencia sigue siendo la escritura condicional, no esta clase.
 */
public final class RevisionClassifier {

    public RevisionComparison classify(Optional<StoredRevision> stored, int incomingVersion, String incomingEventId) {
        if (stored.isEmpty()) {
            return RevisionComparison.NEWER;
        }
        StoredRevision current = stored.get();
        if (current.eventVersion() < incomingVersion) {
            return RevisionComparison.NEWER;
        }
        if (current.eventVersion() > incomingVersion) {
            return RevisionComparison.STALE;
        }
        if (!current.eventId().equals(incomingEventId)) {
            return RevisionComparison.VERSION_CONFLICT;
        }
        return current.status() == OrderStatus.TECHNICAL_FAILURE
                ? RevisionComparison.RETRY_OF_TECHNICAL_FAILURE
                : RevisionComparison.DUPLICATE;
    }
}
