package com.mariposa.b2b.orderprocessor.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Topicos y politicas de Kafka. Los reintentos de persistencia son los de §5.3:
 * 6 intentos con backoff exponencial (2, 4, 8, 16, 32 s = ~1 min) y luego DLT.
 *
 * maxPollRecords acota el lote de cada poll: cada registro puede tardar hasta el
 * presupuesto por mensaje (~15 s), y el lote completo debe procesarse dentro de
 * max.poll.interval.ms (5 min por defecto) para no provocar un rebalanceo.
 */
@ConfigurationProperties("order-processor.kafka")
public record MessagingProperties(
        @DefaultValue("orders.created.v1") String inputTopic,
        @DefaultValue("orders.processed.v1") String processedTopic,
        @DefaultValue("orders.processing.dlt") String deadLetterTopic,
        @DefaultValue("3") int concurrency,
        @DefaultValue("10") int maxPollRecords,
        @DefaultValue("10s") Duration publishTimeout,
        @DefaultValue("6") int persistenceMaxAttempts,
        @DefaultValue("2s") Duration persistenceInitialBackoff,
        @DefaultValue("2") double persistenceBackoffMultiplier,
        @DefaultValue("32s") Duration persistenceMaxBackoff
) {
}
