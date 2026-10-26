package com.mariposa.b2b.orderprocessor.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.time.Duration;

/** Valores iniciales de la propuesta §5.3. Se sobreescriben con order-processor.http.* o variables de entorno. */
@ConfigurationProperties("order-processor.http")
public record HttpClientProperties(
        @DefaultValue("http://localhost:3000") URI clientsBaseUrl,
        @DefaultValue("http://localhost:8080") URI productsBaseUrl,
        @DefaultValue("500ms") Duration connectTimeout,
        @DefaultValue("2s") Duration readTimeout,
        @DefaultValue("3") int maxAttempts,
        @DefaultValue("200ms") Duration initialBackoff,
        @DefaultValue("4") double backoffMultiplier,
        @DefaultValue("2s") Duration retryAfterCap
) {
}
