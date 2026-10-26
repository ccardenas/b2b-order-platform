package com.mariposa.b2b.orderprocessor.infrastructure.kafka.out;

import com.mariposa.b2b.orderprocessor.application.port.out.OrderStorageException;
import com.mariposa.b2b.orderprocessor.domain.model.ErrorCategory;
import com.mariposa.b2b.orderprocessor.infrastructure.kafka.in.DeadLetterException;
import com.mariposa.b2b.orderprocessor.infrastructure.kafka.in.InboundOrder;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.serializer.DeserializationException;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;

/**
 * Headers de la DLT de la propuesta §5.5. Todos los valores van como texto UTF-8,
 * legibles con cualquier consumidor de consola. La causa es siempre un texto propio
 * (mensajes de este servicio o el nombre de la clase de la excepcion): nunca el
 * mensaje de una excepcion de terceros, que puede traer datos del payload o hosts.
 */
public final class DeadLetterHeaders {

    static final String COMPONENT = "order-processor";
    static final int MAX_CAUSE_LENGTH = 500;

    private final Clock clock;

    public DeadLetterHeaders(Clock clock) {
        this.clock = clock;
    }

    public record Classification(ErrorCategory category, String cause) {
    }

    /** La misma clasificacion que va en x-error-category / x-error-cause, para logs y metricas. */
    public Classification classify(Exception exception) {
        Classification classification = classify(unwrap(exception));
        return new Classification(classification.category(), sanitize(classification.cause()));
    }

    public Headers build(ConsumerRecord<?, ?> record, Exception exception) {
        Classification classification = classify(unwrap(exception));
        RecordHeaders headers = new RecordHeaders();

        String orderId = null;
        String eventId = null;
        if (record.value() instanceof InboundOrder inbound) {
            orderId = inbound.message().orderId();
            eventId = inbound.message().eventId();
        }
        if (orderId == null && record.key() instanceof String key) {
            // La clave del topico es el orderId (contrato de orders.created.v1).
            orderId = key;
        }
        add(headers, "x-order-id", orderId);
        add(headers, "x-event-id", eventId);
        add(headers, "x-error-category", classification.category().name());
        add(headers, "x-error-cause", sanitize(classification.cause()));
        add(headers, "x-attempts", String.valueOf(deliveryAttempts(record)));
        add(headers, "x-failed-at", clock.instant().toString());
        add(headers, "x-component", COMPONENT);
        add(headers, "x-original-topic", record.topic());
        add(headers, "x-original-partition", String.valueOf(record.partition()));
        add(headers, "x-original-offset", String.valueOf(record.offset()));
        return headers;
    }

    private static Classification classify(Throwable failure) {
        return switch (failure) {
            case DeserializationException e -> new Classification(ErrorCategory.DESERIALIZATION,
                    e.getCause() != null ? e.getCause().getMessage() : "value could not be deserialized");
            case DeadLetterException e -> new Classification(e.category(), e.getMessage());
            case OrderStorageException e -> new Classification(ErrorCategory.PERSISTENCE,
                    e.getMessage() + (e.getCause() != null ? " (" + e.getCause().getClass().getSimpleName() + ")" : ""));
            default -> new Classification(ErrorCategory.UNEXPECTED, failure.getClass().getSimpleName());
        };
    }

    private static Throwable unwrap(Exception exception) {
        Throwable current = exception;
        while (current instanceof ListenerExecutionFailedException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    /** Lo agrega el contenedor (deliveryAttemptHeader=true). Sin el header, fue el primer intento. */
    private static int deliveryAttempts(ConsumerRecord<?, ?> record) {
        Header attempt = record.headers().lastHeader(KafkaHeaders.DELIVERY_ATTEMPT);
        return attempt != null && attempt.value().length == Integer.BYTES ? ByteBuffer.wrap(attempt.value()).getInt() : 1;
    }

    static String sanitize(String cause) {
        String singleLine = cause == null ? "" : cause.replaceAll("[\\r\\n\\t]+", " ").strip();
        return singleLine.length() <= MAX_CAUSE_LENGTH ? singleLine : singleLine.substring(0, MAX_CAUSE_LENGTH);
    }

    private static void add(RecordHeaders headers, String name, String value) {
        if (value != null) {
            headers.add(name, value.getBytes(StandardCharsets.UTF_8));
        }
    }
}
