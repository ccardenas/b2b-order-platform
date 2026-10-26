package com.mariposa.b2b.orderprocessor.domain.model;

import java.util.List;
import java.util.Optional;

/**
 * Resultado de evaluar un pedido ya validado. Aprobado y rechazado son
 * resultados de negocio (se publican en orders.processed.v1); el fallo
 * tecnico no lo es: se persiste pero nunca se publica (propuesta §5.2).
 */
public sealed interface ProcessingOutcome {

    OrderStatus status();

    record Approved(
            ClientSnapshot client,
            List<PricedOrderLine> lines,
            OrderTotals totals
    ) implements ProcessingOutcome {

        @Override
        public OrderStatus status() {
            return OrderStatus.APPROVED;
        }
    }

    /** client queda vacio solo cuando el motivo incluye CLIENT_NOT_FOUND. */
    record Rejected(
            Optional<ClientSnapshot> client,
            List<RejectionReason> reasons
    ) implements ProcessingOutcome {

        @Override
        public OrderStatus status() {
            return OrderStatus.REJECTED;
        }
    }

    /** category es EXTERNAL_TRANSIENT o EXTERNAL_PERMANENT; attempts, las llamadas HTTP hechas. */
    record TechnicalFailure(
            ErrorCategory category,
            String cause,
            int attempts
    ) implements ProcessingOutcome {

        @Override
        public OrderStatus status() {
            return OrderStatus.TECHNICAL_FAILURE;
        }
    }
}
