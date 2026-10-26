package com.mariposa.b2b.orderprocessor.application.port.in;

import com.mariposa.b2b.orderprocessor.domain.model.ErrorCategory;
import com.mariposa.b2b.orderprocessor.domain.model.OrderStatus;

/**
 * Lo que el listener necesita saber para cerrar el mensaje (§5.2, §6.4). Todas
 * las variantes implican "ya se puede confirmar el offset"; lo que cambia es
 * si ademas hay que enviar el mensaje original a la DLT.
 */
public sealed interface ProcessOrderResult {

    /** Resultado de negocio persistido (APPROVED o REJECTED). No requiere DLT. */
    record Completed(OrderStatus status) implements ProcessOrderResult {
    }

    /** Mensaje terminado sin efecto nuevo (DUPLICATE o STALE). No requiere DLT. */
    record Skipped(ErrorCategory category) implements ProcessOrderResult {
    }

    /**
     * Requiere DLT. persisted indica si quedo un TECHNICAL_FAILURE en Mongo
     * (fallos externos) o no se escribio nada (VALIDATION, VERSION_CONFLICT).
     */
    record Failed(ErrorCategory category, String cause, boolean persisted) implements ProcessOrderResult {
    }
}
