package com.mariposa.b2b.orderprocessor.infrastructure.kafka.in;

import com.mariposa.b2b.orderprocessor.application.ProcessOrderUseCase;
import com.mariposa.b2b.orderprocessor.application.TransitionLog;
import com.mariposa.b2b.orderprocessor.application.port.in.ProcessOrderCommand;
import com.mariposa.b2b.orderprocessor.application.port.in.ProcessOrderResult;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;

/**
 * Listener bloqueante (§6.1). Con AckMode.RECORD el offset se confirma al volver de
 * este metodo sin excepcion, o despues de que el error handler envio el registro a la
 * DLT: en ambos casos ya hay un estado durable (§6.4). Una excepcion de persistencia
 * se propaga tal cual y el error handler reintenta el mensaje completo.
 */
public class OrderCreatedListener {

    private static final Logger log = LoggerFactory.getLogger(OrderCreatedListener.class);

    private final ProcessOrderUseCase useCase;
    private final String inputTopic;
    private final Timer processingDuration;

    public OrderCreatedListener(ProcessOrderUseCase useCase, String inputTopic, MeterRegistry registry) {
        this.useCase = useCase;
        this.inputTopic = inputTopic;
        // orders_processing_duration_seconds: histograma para p50/p95/p99 en Prometheus.
        this.processingDuration = Timer.builder("orders.processing.duration")
                .description("Tiempo de procesamiento de un registro de orders.created.v1, de la recepcion al resultado")
                .publishPercentileHistogram()
                .register(registry);
    }

    /** Lo lee la anotacion (__listener es este bean): el topico sale de MessagingProperties y no se duplica. */
    public String inputTopic() {
        return inputTopic;
    }

    @KafkaListener(id = "order-created-listener", topics = "#{__listener.inputTopic()}")
    public void onMessage(ConsumerRecord<String, InboundOrder> record) {
        OrderMdc.put(record);
        Timer.Sample sample = Timer.start();
        try {
            TransitionLog.info(log, "received", "topic", record.topic());
            ProcessOrderCommand command = new ProcessOrderCommand(
                    InboundOrderMapper.toDomain(record.value().message()),
                    new ProcessOrderCommand.Source(record.topic(), record.partition(), record.offset()));

            switch (useCase.process(command)) {
                case ProcessOrderResult.Completed completed -> {
                    // resultado durable y publicado (o pendiente para el relay)
                }
                case ProcessOrderResult.Skipped skipped -> {
                    // DUPLICATE o STALE: nada que hacer, solo confirmar el offset
                }
                case ProcessOrderResult.Failed failed -> throw new DeadLetterException(failed.category(), failed.cause());
            }
        } finally {
            sample.stop(processingDuration);
            OrderMdc.clear();
        }
    }
}
