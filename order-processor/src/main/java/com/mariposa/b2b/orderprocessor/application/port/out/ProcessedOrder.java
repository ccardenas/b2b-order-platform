package com.mariposa.b2b.orderprocessor.application.port.out;

import com.mariposa.b2b.orderprocessor.application.port.in.ProcessOrderCommand;
import com.mariposa.b2b.orderprocessor.domain.model.ProcessingOutcome;
import com.mariposa.b2b.orderprocessor.domain.model.ValidatedOrder;

import java.time.Instant;
import java.util.Optional;

/**
 * Todo lo que se persiste (y de lo que se deriva orders.processed.v1) para un
 * evento. outboxEventId solo existe para APPROVED y REJECTED: se genera una
 * vez aqui para que sea el mismo en la publicacion inmediata y en el relay.
 */
public record ProcessedOrder(
        ValidatedOrder order,
        ProcessingOutcome outcome,
        ProcessOrderCommand.Source source,
        Instant receivedAt,
        Instant processedAt,
        Optional<String> outboxEventId
) {
}
