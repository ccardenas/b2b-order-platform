package com.mariposa.b2b.orderprocessor.infrastructure.kafka.out;

import com.mariposa.b2b.orderprocessor.application.TransitionLog;
import com.mariposa.b2b.orderprocessor.infrastructure.kafka.in.OrderMdc;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;

/**
 * Envuelve al DeadLetterPublishingRecoverer para registrar la transicion "dlt" y
 * orders_dlt_total{category}. Solo cuenta y loguea despues de un envio confirmado: si
 * el envio falla, el delegado lanza, el offset no se confirma y no hubo DLT todavia.
 */
public final class ObservedDeadLetterRecoverer implements ConsumerRecordRecoverer {

    private static final Logger log = LoggerFactory.getLogger(ObservedDeadLetterRecoverer.class);

    private final ConsumerRecordRecoverer delegate;
    private final DeadLetterHeaders headers;
    private final MeterRegistry registry;

    public ObservedDeadLetterRecoverer(ConsumerRecordRecoverer delegate, DeadLetterHeaders headers, MeterRegistry registry) {
        this.delegate = delegate;
        this.headers = headers;
        this.registry = registry;
    }

    @Override
    public void accept(ConsumerRecord<?, ?> record, Exception exception) {
        delegate.accept(record, exception);

        DeadLetterHeaders.Classification classification = headers.classify(exception);
        registry.counter("orders.dlt", "category", classification.category().name()).increment();
        OrderMdc.put(record);
        try {
            TransitionLog.warn(log, "dlt", "category", classification.category(), "cause", classification.cause());
        } finally {
            OrderMdc.clear();
        }
    }
}
