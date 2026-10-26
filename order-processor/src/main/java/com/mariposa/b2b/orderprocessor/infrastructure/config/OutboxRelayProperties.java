package com.mariposa.b2b.orderprocessor.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** Valores de §7.2: cada 5 s, eventos PENDING con mas de 10 s. */
@ConfigurationProperties("order-processor.outbox")
public record OutboxRelayProperties(
        @DefaultValue("5s") Duration relayInterval,
        @DefaultValue("10s") Duration minAge,
        @DefaultValue("100") int batchSize
) {
}
