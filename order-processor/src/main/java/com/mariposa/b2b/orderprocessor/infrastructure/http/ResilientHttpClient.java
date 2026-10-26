package com.mariposa.b2b.orderprocessor.infrastructure.http;

import com.mariposa.b2b.orderprocessor.application.port.out.ExternalCallFailedException;
import com.mariposa.b2b.orderprocessor.application.port.out.ExternalCallFailedException.Kind;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Optional;
import java.util.function.DoubleSupplier;

/**
 * GET con timeout, reintentos con backoff exponencial y jitter, y respeto de
 * Retry-After (propuesta §5.3). Solo decide QUE se reintenta: 429, cualquier
 * 5xx, timeout y errores de red. Cualquier otra respuesta (200, 404, 400...)
 * se devuelve tal cual y cada adaptador decide que significa para su API.
 *
 * Si los intentos se agotan lanza {@link ExternalCallFailedException} TRANSIENT.
 *
 * Limitacion conocida: el timeout de HttpRequest cubre hasta recibir los headers,
 * no la lectura del cuerpo. Las respuestas son JSON de pocos cientos de bytes, y
 * el presupuesto total del caso de uso acota el peor caso.
 */
public final class ResilientHttpClient {

    public record Response(int status, String body, int attempts) {
    }

    private sealed interface Attempt {
        record Done(int status, String body) implements Attempt {
        }

        record Retry(String cause, Optional<Duration> retryAfter) implements Attempt {
        }
    }

    private final HttpClient http;
    private final Duration readTimeout;
    private final RetryPolicy policy;
    private final Sleeper sleeper;
    private final DoubleSupplier jitter;

    /** jitter devuelve un valor en [0, 1); se inyecta para poder fijarlo en los tests. */
    public ResilientHttpClient(HttpClient http, Duration readTimeout, RetryPolicy policy, Sleeper sleeper, DoubleSupplier jitter) {
        this.http = http;
        this.readTimeout = readTimeout;
        this.policy = policy;
        this.sleeper = sleeper;
        this.jitter = jitter;
    }

    public Response get(String service, URI uri) {
        for (int attempt = 1; ; attempt++) {
            Attempt result = attemptOnce(uri);
            if (result instanceof Attempt.Done done) {
                return new Response(done.status(), done.body(), attempt);
            }
            Attempt.Retry retry = (Attempt.Retry) result;
            if (attempt >= policy.maxAttempts()) {
                throw transientFailure(service, retry.cause(), attempt);
            }
            Duration wait = retry.retryAfter().isPresent() ? retry.retryAfter().get() : backoff(attempt);
            if (retry.retryAfter().isPresent() && wait.compareTo(policy.retryAfterCap()) > 0) {
                // Esperar mas del tope amplificaria una tormenta de limites: se da por agotado.
                throw transientFailure(service, retry.cause() + " with Retry-After above cap", attempt);
            }
            pause(wait);
        }
    }

    private Attempt attemptOnce(URI uri) {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(readTimeout)
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status == 429 || status >= 500) {
                return new Attempt.Retry("HTTP " + status, retryAfter(response));
            }
            return new Attempt.Done(status, response.body());
        } catch (HttpTimeoutException e) {
            return new Attempt.Retry("timeout", Optional.empty());
        } catch (IOException e) {
            // Solo la clase: el mensaje puede incluir hosts o rutas internas.
            return new Attempt.Retry("I/O error " + e.getClass().getSimpleName(), Optional.empty());
        } catch (InterruptedException e) {
            // El caso de uso cancela las llamadas al agotar su presupuesto o al apagarse.
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted during HTTP call", e);
        }
    }

    /** Equal jitter: la espera cae en [base/2, base], asi las instancias no se sincronizan. */
    private Duration backoff(int failedAttempt) {
        double base = policy.initialBackoff().toMillis() * Math.pow(policy.backoffMultiplier(), failedAttempt - 1);
        return Duration.ofMillis((long) (base / 2 + jitter.getAsDouble() * base / 2));
    }

    private void pause(Duration wait) {
        try {
            sleeper.sleep(wait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted during retry backoff", e);
        }
    }

    /** Solo segundos enteros, que es lo que declaran ambos OpenAPI. Un valor invalido se ignora. */
    private static Optional<Duration> retryAfter(HttpResponse<?> response) {
        return response.headers().firstValue("Retry-After").flatMap(raw -> {
            try {
                long seconds = Long.parseLong(raw.trim());
                return seconds < 0 ? Optional.empty() : Optional.of(Duration.ofSeconds(seconds));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        });
    }

    private static ExternalCallFailedException transientFailure(String service, String cause, int attempts) {
        return new ExternalCallFailedException(Kind.TRANSIENT,
                service + ": " + cause + " after " + attempts + " attempt(s)", attempts);
    }
}
