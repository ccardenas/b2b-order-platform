package com.mariposa.b2b.orderprocessor.infrastructure.http;

import com.mariposa.b2b.orderprocessor.application.port.out.ExternalCallFailedException;
import com.mariposa.b2b.orderprocessor.application.port.out.ExternalCallFailedException.Kind;
import com.mariposa.b2b.orderprocessor.domain.model.ClientSegment;
import com.mariposa.b2b.orderprocessor.domain.model.ClientSnapshot;
import com.mariposa.b2b.orderprocessor.domain.model.ClientStatus;
import com.mariposa.b2b.orderprocessor.domain.model.Market;
import com.mariposa.b2b.orderprocessor.domain.model.TaxRegime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class ClientsApiClientTest extends WireMockHttpTestBase {

    private static final String PATH = "/clients/CLI-99821";

    private ClientsApiClient client;

    @BeforeEach
    void setUp() {
        client = new ClientsApiClient(http, mapper, baseUrl);
    }

    private static String clientJson(String status, String segment, String taxRegime, String market) {
        return """
                {"clientId":"CLI-99821","name":"Distribuidora Norte","status":"%s","segment":"%s",\
                "taxRegime":"%s","market":"%s","creditLimit":1500.50}"""
                .formatted(status, segment, taxRegime, market);
    }

    private void stubJson(int status, String body) {
        wireMock.stubFor(get(urlEqualTo(PATH)).willReturn(aResponse().withStatus(status).withBody(body)));
    }

    private ExternalCallFailedException failure() {
        Throwable thrown = catchThrowable(() -> client.findById("CLI-99821"));
        assertThat(thrown).isInstanceOf(ExternalCallFailedException.class);
        return (ExternalCallFailedException) thrown;
    }

    @Test
    void mapsTheThreeIndependentClientFieldsAndIgnoresUnknownOnes() {
        stubJson(200, clientJson("ACTIVE", "WHOLESALE", "SIMPLIFIED", "CO"));

        Optional<ClientSnapshot> result = client.findById("CLI-99821");

        assertThat(result).contains(new ClientSnapshot(
                "CLI-99821", ClientStatus.ACTIVE, ClientSegment.WHOLESALE, TaxRegime.SIMPLIFIED, Market.CO));
        wireMock.verify(1, getRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void aBlockedClientIsStillAFoundClient() {
        stubJson(200, clientJson("BLOCKED", "RETAIL", "GENERAL", "MX"));

        assertThat(client.findById("CLI-99821")).map(ClientSnapshot::status).contains(ClientStatus.BLOCKED);
    }

    @Test
    void clientNotFoundIsABusinessResultNotAnError() {
        stubJson(404, errorBody("CLIENT_NOT_FOUND"));

        assertThat(client.findById("CLI-99821")).isEmpty();
        wireMock.verify(1, getRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void a404WithAnotherCodeIsAMisconfigurationNotAMissingClient() {
        stubJson(404, errorBody("ROUTE_NOT_FOUND"));

        ExternalCallFailedException e = failure();

        assertThat(e.kind()).isEqualTo(Kind.PERMANENT);
        assertThat(e.getMessage()).isEqualTo("clients-api: unexpected HTTP 404");
    }

    @Test
    void a404WithoutTheErrorContractIsPermanent() {
        stubJson(404, "<html>nginx</html>");

        assertThat(failure().kind()).isEqualTo(Kind.PERMANENT);
    }

    @Test
    void otherClientErrorsArePermanentAndNotRetried() {
        stubJson(400, errorBody("INVALID_REQUEST"));

        ExternalCallFailedException e = failure();

        assertThat(e.kind()).isEqualTo(Kind.PERMANENT);
        assertThat(e.attempts()).isEqualTo(1);
        wireMock.verify(1, getRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void retriesARateLimitHonoringRetryAfterAndThenSucceeds() {
        wireMock.stubFor(get(urlEqualTo(PATH)).inScenario("rate").whenScenarioStateIs("Started")
                .willSetStateTo("limited")
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "1").withBody(errorBody("RATE_LIMITED"))));
        wireMock.stubFor(get(urlEqualTo(PATH)).inScenario("rate").whenScenarioStateIs("limited")
                .willReturn(aResponse().withStatus(200).withBody(clientJson("ACTIVE", "RETAIL", "GENERAL", "MX"))));

        assertThat(client.findById("CLI-99821")).isPresent();
        assertThat(sleeps).containsExactly(Duration.ofSeconds(1));
    }

    @Test
    void aRateLimitBeyondTheCapIsTransientAfterOneAttempt() {
        wireMock.stubFor(get(urlEqualTo(PATH)).willReturn(
                aResponse().withStatus(429).withHeader("Retry-After", "30").withBody(errorBody("RATE_LIMITED"))));

        ExternalCallFailedException e = failure();

        assertThat(e.kind()).isEqualTo(Kind.TRANSIENT);
        assertThat(e.attempts()).isEqualTo(1);
    }

    @Test
    void serviceUnavailableIsRetriedThenReportedAsTransient() {
        stubJson(503, errorBody("SERVICE_UNAVAILABLE"));

        ExternalCallFailedException e = failure();

        assertThat(e.kind()).isEqualTo(Kind.TRANSIENT);
        assertThat(e.attempts()).isEqualTo(3);
        wireMock.verify(3, getRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void aTimeoutIsRetriedThenReportedAsTransient() {
        wireMock.stubFor(get(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200)
                .withBody(clientJson("ACTIVE", "RETAIL", "GENERAL", "MX")).withFixedDelay((int) TIMEOUT_DELAY.toMillis())));

        ExternalCallFailedException e = failure();

        assertThat(e.kind()).isEqualTo(Kind.TRANSIENT);
        assertThat(e.getMessage()).isEqualTo("clients-api: timeout after 3 attempt(s)");
    }

    @Test
    void anUnknownEnumValueIsPermanentAndNeverRetried() {
        stubJson(200, clientJson("SUSPENDED", "RETAIL", "GENERAL", "MX"));

        ExternalCallFailedException e = failure();

        assertThat(e.kind()).isEqualTo(Kind.PERMANENT);
        assertThat(e.getMessage()).isEqualTo("clients-api: invalid response: unknown value for status");
        assertThat(e.getMessage()).doesNotContain("SUSPENDED");
        wireMock.verify(1, getRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void aMissingFieldIsPermanent() {
        stubJson(200, "{\"clientId\":\"CLI-99821\",\"status\":\"ACTIVE\",\"segment\":\"RETAIL\",\"market\":\"MX\"}");

        assertThat(failure().getMessage()).isEqualTo("clients-api: invalid response: missing taxRegime");
    }

    @Test
    void aBodyThatIsNotJsonIsPermanent() {
        stubJson(200, "not json");

        ExternalCallFailedException e = failure();

        assertThat(e.kind()).isEqualTo(Kind.PERMANENT);
        assertThat(e.getMessage()).isEqualTo("clients-api: response body is not valid JSON");
    }

    @Test
    void aResponseForAnotherClientIsPermanent() {
        stubJson(200, clientJson("ACTIVE", "RETAIL", "GENERAL", "MX").replace("CLI-99821", "CLI-OTHER"));

        assertThat(failure().getMessage()).isEqualTo("clients-api: invalid response: clientId does not match the request");
    }

    @Test
    void aSuccessStatusOtherThan200IsPermanent() {
        stubJson(204, "");

        assertThat(failure().getMessage()).isEqualTo("clients-api: unexpected HTTP 204");
    }

    @Test
    void encodesTheIdSoItCannotChangeTheRequestedPath() {
        wireMock.stubFor(get(urlEqualTo("/clients/a%2Fb%20c")).willReturn(
                aResponse().withStatus(404).withBody(errorBody("CLIENT_NOT_FOUND"))));

        assertThat(client.findById("a/b c")).isEmpty();
    }

    @Test
    void toleratesATrailingSlashInTheBaseUrl() {
        client = new ClientsApiClient(http, mapper, URI.create(baseUrl + "/"));
        stubJson(404, errorBody("CLIENT_NOT_FOUND"));

        assertThat(client.findById("CLI-99821")).isEmpty();
    }
}
