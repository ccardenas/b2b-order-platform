package com.mariposa.b2b.orderprocessor.infrastructure.http;

import java.time.Duration;

/**
 * Politica de la propuesta §5.3. Con 3 intentos, 200 ms y multiplicador 4 las
 * esperas nominales son 200 ms y 800 ms. retryAfterCap es el maximo que se esta
 * dispuesto a esperar por un Retry-After del servidor.
 */
public record RetryPolicy(int maxAttempts, Duration initialBackoff, double backoffMultiplier, Duration retryAfterCap) {

    public RetryPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
    }
}
