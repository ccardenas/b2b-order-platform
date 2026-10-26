package com.mariposa.b2b.orderprocessor.application.port.out;

public interface ProcessedEventPublisher {

    /**
     * Publica orders.processed.v1 para un resultado APPROVED o REJECTED ya
     * persistido, con processed.outboxEventId() como eventId.
     *
     * @throws RuntimeException si el broker no confirma; el caso de uso lo
     *         tolera y deja el outbox PENDING para el relay
     */
    void publish(ProcessedOrder processed);
}
