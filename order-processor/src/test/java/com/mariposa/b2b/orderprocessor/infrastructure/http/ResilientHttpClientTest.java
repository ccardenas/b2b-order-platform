package com.mariposa.b2b.orderprocessor.infrastructure.http;

import com.mariposa.b2b.orderprocessor.application.port.out.ExternalCallFailedException;
import com.mariposa.b2b.orderprocessor.application.port.out.ExternalCallFailedException.Kind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResilientHttpClientTest extends WireMockHttpTestBase {

    private static final String PATH = "/things/1";

    private URI uri() {
        return URI.create(baseUrl + PATH);
    }

    private void stubSequence(int... statuses) {
        String scenario = "sequence";
        for (int i = 0; i < statuses.length; i++) {
            wireMock.stubFor(get(urlEqualTo(PATH)).inScenario(scenario)
                    .whenScenarioStateIs(i == 0 ? "Started" : "step-" + i)
                    .willSetStateTo("step-" + (i + 1))
                    .willReturn(aResponse().withStatus(statuses[i]).withBody("{}")));
        }
    }

    private void verifyRequests(int expected) {
        wireMock.verify(expected, getRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void returnsTheFirstSuccessfulResponseWithoutRetrying() {
        wireMock.stubFor(get(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200).withBody("{\"ok\":true}")));

        ResilientHttpClient.Response response = http.get("svc", uri());

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("{\"ok\":true}");
        assertThat(response.attempts()).isEqualTo(1);
        assertThat(sleeps).isEmpty();
        assertThat(registry.find("http.client.retries").counter()).isNull();
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 502, 503, 504})
    void retriesServerErrorsAndSucceedsWhenTheServiceRecovers(int status) {
        stubSequence(status, 200);

        ResilientHttpClient.Response response = http.get("svc", uri());

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.attempts()).isEqualTo(2);
        verifyRequests(2);
    }

    @Test
    void givesUpAfterMaxAttemptsWithExponentialBackoffAndJitter() {
        wireMock.stubFor(get(urlEqualTo(PATH)).willReturn(aResponse().withStatus(503).withBody(errorBody("SERVICE_UNAVAILABLE"))));

        assertThatThrownBy(() -> http.get("products-api", uri()))
                .isInstanceOfSatisfying(ExternalCallFailedException.class, e -> {
                    assertThat(e.kind()).isEqualTo(Kind.TRANSIENT);
                    assertThat(e.attempts()).isEqualTo(3);
                    assertThat(e.getMessage()).isEqualTo("products-api: HTTP 503 after 3 attempt(s)");
                });

        verifyRequests(3);
        // http_client_retries_total{target}: 2 reintentos para 3 intentos.
        assertThat(registry.counter("http.client.retries", "target", "products-api").count()).isEqualTo(2);
        // base 200 ms y 800 ms, con equal jitter fijo en 0.5 -> 150 ms y 600 ms (rango real: [base/2, base])
        assertThat(sleeps).containsExactly(Duration.ofMillis(150), Duration.ofMillis(600));
    }

    @Test
    void honorsRetryAfterInsteadOfTheBackoff() {
        wireMock.stubFor(get(urlEqualTo(PATH)).inScenario("rate").whenScenarioStateIs("Started")
                .willSetStateTo("limited")
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "1").withBody(errorBody("RATE_LIMITED"))));
        wireMock.stubFor(get(urlEqualTo(PATH)).inScenario("rate").whenScenarioStateIs("limited")
                .willReturn(aResponse().withStatus(200).withBody("{}")));

        ResilientHttpClient.Response response = http.get("svc", uri());

        assertThat(response.status()).isEqualTo(200);
        assertThat(sleeps).containsExactly(Duration.ofSeconds(1));
    }

    @Test
    void treatsRetryAfterAboveTheCapAsExhaustedWithoutWaiting() {
        wireMock.stubFor(get(urlEqualTo(PATH)).willReturn(
                aResponse().withStatus(429).withHeader("Retry-After", "3").withBody(errorBody("RATE_LIMITED"))));

        assertThatThrownBy(() -> http.get("clients-api", uri()))
                .isInstanceOfSatisfying(ExternalCallFailedException.class, e -> {
                    assertThat(e.kind()).isEqualTo(Kind.TRANSIENT);
                    assertThat(e.attempts()).isEqualTo(1);
                    assertThat(e.getMessage()).contains("HTTP 429").contains("Retry-After above cap");
                });

        verifyRequests(1);
        assertThat(sleeps).isEmpty();
        assertThat(registry.find("http.client.retries").counter()).as("giving up is not a retry").isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "soon", "-5", "Wed, 21 Oct 2026 07:28:00 GMT"})
    void fallsBackToTheBackoffWhenRetryAfterIsMissingOrNotInSeconds(String header) {
        var response = aResponse().withStatus(429);
        wireMock.stubFor(get(urlEqualTo(PATH)).inScenario("rate").whenScenarioStateIs("Started")
                .willSetStateTo("limited")
                .willReturn(header.isEmpty() ? response : response.withHeader("Retry-After", header)));
        wireMock.stubFor(get(urlEqualTo(PATH)).inScenario("rate").whenScenarioStateIs("limited")
                .willReturn(aResponse().withStatus(200).withBody("{}")));

        http.get("svc", uri());

        assertThat(sleeps).containsExactly(Duration.ofMillis(150));
    }

    @Test
    void retriesTimeoutsAndReportsThemAsTransient() {
        wireMock.stubFor(get(urlEqualTo(PATH)).willReturn(
                aResponse().withStatus(200).withBody("{}").withFixedDelay((int) TIMEOUT_DELAY.toMillis())));

        assertThatThrownBy(() -> http.get("products-api", uri()))
                .isInstanceOfSatisfying(ExternalCallFailedException.class, e -> {
                    assertThat(e.kind()).isEqualTo(Kind.TRANSIENT);
                    assertThat(e.attempts()).isEqualTo(3);
                    assertThat(e.getMessage()).isEqualTo("products-api: timeout after 3 attempt(s)");
                });

        verifyRequests(3);
    }

    @Test
    void retriesConnectionFailuresAndDoesNotLeakHostDetails() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        ResilientHttpClient unreachable = httpClient(URI.create("http://localhost:" + closedPort));

        assertThatThrownBy(() -> unreachable.get("clients-api", URI.create("http://localhost:" + closedPort + PATH)))
                .isInstanceOfSatisfying(ExternalCallFailedException.class, e -> {
                    assertThat(e.kind()).isEqualTo(Kind.TRANSIENT);
                    assertThat(e.attempts()).isEqualTo(3);
                    assertThat(e.getMessage()).startsWith("clients-api: I/O error ").doesNotContain("localhost");
                });
        assertThat(sleeps).hasSize(2);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 404, 409})
    void returnsClientErrorsUntouchedAndNeverRetriesThem(int status) {
        wireMock.stubFor(get(urlEqualTo(PATH)).willReturn(aResponse().withStatus(status).withBody("{\"error\":{}}")));

        ResilientHttpClient.Response response = http.get("svc", uri());

        assertThat(response.status()).isEqualTo(status);
        assertThat(response.attempts()).isEqualTo(1);
        verifyRequests(1);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void doesNotFollowRedirects() {
        wireMock.stubFor(get(urlEqualTo(PATH)).willReturn(aResponse().withStatus(302).withHeader("Location", "/elsewhere")));

        assertThat(http.get("svc", uri()).status()).isEqualTo(302);
        wireMock.verify(0, getRequestedFor(urlEqualTo("/elsewhere")));
    }

    @Test
    void rejectsAPolicyWithoutAnyAttempt() {
        assertThatThrownBy(() -> new RetryPolicy(0, Duration.ofMillis(1), 2, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
