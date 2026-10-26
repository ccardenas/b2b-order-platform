package com.mariposa.b2b.orderprocessor.infrastructure.kafka.out;

import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Envio sincrono a orders.processed.v1 con clave orderId. Sincrono porque solo despues
 * de la confirmacion del broker (acks=all) se puede marcar el outbox PUBLISHED.
 */
public final class ProcessedEventSender {

    private final KafkaTemplate<String, Object> template;
    private final String topic;
    private final Duration timeout;

    public ProcessedEventSender(KafkaTemplate<String, Object> template, String topic, Duration timeout) {
        this.template = template;
        this.topic = topic;
        this.timeout = timeout;
    }

    public void send(String orderId, byte[] json) {
        try {
            template.send(topic, orderId, json).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PublicationException("interrupted while publishing", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new PublicationException("broker did not acknowledge the processed event", e);
        }
    }
}
