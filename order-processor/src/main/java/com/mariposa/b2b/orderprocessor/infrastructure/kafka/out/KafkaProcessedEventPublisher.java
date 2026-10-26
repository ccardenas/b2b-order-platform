package com.mariposa.b2b.orderprocessor.infrastructure.kafka.out;

import com.mariposa.b2b.orderprocessor.application.port.out.ProcessedEventPublisher;
import com.mariposa.b2b.orderprocessor.application.port.out.ProcessedOrder;
import com.mariposa.b2b.orderprocessor.infrastructure.mongo.OrderDocumentMapper;

/**
 * Camino rapido del outbox (§7.2). Publica el mismo payload que quedo persistido en
 * outbox.payload (mismo mapper, misma serializacion), no una version propia.
 */
public final class KafkaProcessedEventPublisher implements ProcessedEventPublisher {

    private final ProcessedEventSender sender;
    private final ProcessedEventJson json;

    public KafkaProcessedEventPublisher(ProcessedEventSender sender, ProcessedEventJson json) {
        this.sender = sender;
        this.json = json;
    }

    @Override
    public void publish(ProcessedOrder processed) {
        sender.send(processed.order().orderId(), json.toJson(OrderDocumentMapper.payload(processed)));
    }
}
