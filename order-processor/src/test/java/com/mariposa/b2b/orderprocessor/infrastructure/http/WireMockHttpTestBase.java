package com.mariposa.b2b.orderprocessor.infrastructure.http;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

/**
 * Base de los tests con WireMock. Politica de la propuesta §5.3 (3 intentos,
 * 200 ms x4, tope de Retry-After 2 s) con read timeout corto para que el test
 * de timeout no tarde, y un Sleeper que registra las esperas en vez de dormir.
 * El jitter queda fijo en 0.5, asi las esperas son 150 ms y 600 ms.
 */
abstract class WireMockHttpTestBase {

    static final Duration READ_TIMEOUT = Duration.ofMillis(200);
    static final Duration TIMEOUT_DELAY = Duration.ofMillis(800);

    @RegisterExtension
    static final WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    final List<Duration> sleeps = new ArrayList<>();
    final ObjectMapper mapper = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    ResilientHttpClient http;
    URI baseUrl;

    @BeforeEach
    void setUpHttp() {
        sleeps.clear();
        baseUrl = URI.create(wireMock.baseUrl());
        http = httpClient(baseUrl);
    }

    ResilientHttpClient httpClient(URI target) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(500))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        RetryPolicy policy = new RetryPolicy(3, Duration.ofMillis(200), 4, Duration.ofSeconds(2));
        return new ResilientHttpClient(client, READ_TIMEOUT, policy, sleeps::add, () -> 0.5);
    }

    static String errorBody(String code) {
        return "{\"error\":{\"code\":\"" + code + "\",\"message\":\"x\",\"traceId\":\"t-1\"}}";
    }
}
