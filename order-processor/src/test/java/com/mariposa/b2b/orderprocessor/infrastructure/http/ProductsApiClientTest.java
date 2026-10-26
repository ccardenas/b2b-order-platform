package com.mariposa.b2b.orderprocessor.infrastructure.http;

import com.mariposa.b2b.orderprocessor.application.port.out.ExternalCallFailedException;
import com.mariposa.b2b.orderprocessor.application.port.out.ExternalCallFailedException.Kind;
import com.mariposa.b2b.orderprocessor.domain.model.Market;
import com.mariposa.b2b.orderprocessor.domain.model.ProductSnapshot;
import com.mariposa.b2b.orderprocessor.domain.model.ProductStatus;
import com.mariposa.b2b.orderprocessor.domain.model.TaxCategory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class ProductsApiClientTest extends WireMockHttpTestBase {

    private static final String URL = "/products/PRD-001?market=MX";

    private ProductsApiClient client;

    @BeforeEach
    void setUp() {
        client = new ProductsApiClient(http, mapper, baseUrl);
    }

    private static String productJson(String status, String taxCategory) {
        return """
                {"productId":"PRD-001","name":"Bebida 600 ml","sku":"BEB-600-PET","status":"%s",\
                "taxCategory":"%s","stock":10}"""
                .formatted(status, taxCategory);
    }

    private void stubJson(int status, String body) {
        wireMock.stubFor(get(urlEqualTo(URL)).willReturn(aResponse().withStatus(status).withBody(body)));
    }

    private ExternalCallFailedException failure() {
        Throwable thrown = catchThrowable(() -> client.findById("PRD-001", Market.MX));
        assertThat(thrown).isInstanceOf(ExternalCallFailedException.class);
        return (ExternalCallFailedException) thrown;
    }

    @Test
    void mapsTheProductAndAsksForTheOrderMarket() {
        stubJson(200, productJson("ACTIVE", "REDUCED"));

        assertThat(client.findById("PRD-001", Market.MX)).contains(
                new ProductSnapshot("PRD-001", "BEB-600-PET", ProductStatus.ACTIVE, TaxCategory.REDUCED));
        wireMock.verify(1, getRequestedFor(urlEqualTo(URL)));
    }

    @Test
    void aDiscontinuedProductIsStillAFoundProduct() {
        stubJson(200, productJson("DISCONTINUED", "EXEMPT"));

        assertThat(client.findById("PRD-001", Market.MX)).map(ProductSnapshot::status).contains(ProductStatus.DISCONTINUED);
    }

    @Test
    void productNotFoundInTheMarketIsABusinessResultNotAnError() {
        stubJson(404, errorBody("PRODUCT_NOT_FOUND"));

        assertThat(client.findById("PRD-001", Market.MX)).isEmpty();
        wireMock.verify(1, getRequestedFor(urlEqualTo(URL)));
    }

    @Test
    void a404WithAnotherCodeIsAMisconfigurationNotAMissingProduct() {
        stubJson(404, errorBody("ROUTE_NOT_FOUND"));

        ExternalCallFailedException e = failure();

        assertThat(e.kind()).isEqualTo(Kind.PERMANENT);
        assertThat(e.getMessage()).isEqualTo("products-api: unexpected HTTP 404");
    }

    @Test
    void otherClientErrorsArePermanentAndNotRetried() {
        stubJson(400, errorBody("INVALID_MARKET"));

        ExternalCallFailedException e = failure();

        assertThat(e.kind()).isEqualTo(Kind.PERMANENT);
        assertThat(e.attempts()).isEqualTo(1);
        wireMock.verify(1, getRequestedFor(urlEqualTo(URL)));
    }

    @Test
    void retriesARateLimitHonoringRetryAfterAndThenSucceeds() {
        wireMock.stubFor(get(urlEqualTo(URL)).inScenario("rate").whenScenarioStateIs("Started")
                .willSetStateTo("limited")
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "1").withBody(errorBody("RATE_LIMITED"))));
        wireMock.stubFor(get(urlEqualTo(URL)).inScenario("rate").whenScenarioStateIs("limited")
                .willReturn(aResponse().withStatus(200).withBody(productJson("ACTIVE", "STANDARD"))));

        assertThat(client.findById("PRD-001", Market.MX)).isPresent();
        assertThat(sleeps).containsExactly(Duration.ofSeconds(1));
    }

    @Test
    void aRateLimitBeyondTheCapIsTransientAfterOneAttempt() {
        wireMock.stubFor(get(urlEqualTo(URL)).willReturn(
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
        wireMock.verify(3, getRequestedFor(urlEqualTo(URL)));
    }

    @Test
    void aTimeoutIsRetriedThenReportedAsTransient() {
        wireMock.stubFor(get(urlEqualTo(URL)).willReturn(aResponse().withStatus(200)
                .withBody(productJson("ACTIVE", "STANDARD")).withFixedDelay((int) TIMEOUT_DELAY.toMillis())));

        ExternalCallFailedException e = failure();

        assertThat(e.kind()).isEqualTo(Kind.TRANSIENT);
        assertThat(e.getMessage()).isEqualTo("products-api: timeout after 3 attempt(s)");
    }

    @Test
    void anUnknownEnumValueIsPermanentAndNeverRetried() {
        stubJson(200, productJson("ARCHIVED", "STANDARD"));

        ExternalCallFailedException e = failure();

        assertThat(e.kind()).isEqualTo(Kind.PERMANENT);
        assertThat(e.getMessage()).isEqualTo("products-api: invalid response: unknown value for status");
        wireMock.verify(1, getRequestedFor(urlEqualTo(URL)));
    }

    @Test
    void anUnknownTaxCategoryIsPermanent() {
        stubJson(200, productJson("ACTIVE", "LUXURY"));

        assertThat(failure().getMessage()).isEqualTo("products-api: invalid response: unknown value for taxCategory");
    }

    @Test
    void aMissingFieldIsPermanent() {
        stubJson(200, "{\"productId\":\"PRD-001\",\"status\":\"ACTIVE\",\"taxCategory\":\"STANDARD\"}");

        assertThat(failure().getMessage()).isEqualTo("products-api: invalid response: missing sku");
    }

    @Test
    void aBodyThatIsNotJsonIsPermanent() {
        stubJson(200, "{");

        assertThat(failure().getMessage()).isEqualTo("products-api: response body is not valid JSON");
    }

    @Test
    void aResponseForAnotherProductIsPermanent() {
        stubJson(200, productJson("ACTIVE", "STANDARD").replace("PRD-001", "PRD-002"));

        assertThat(failure().getMessage()).isEqualTo("products-api: invalid response: productId does not match the request");
    }

    @Test
    void encodesTheIdSoItCannotChangeTheRequestedPath() {
        wireMock.stubFor(get(urlEqualTo("/products/..%2Fadmin?market=PE")).willReturn(
                aResponse().withStatus(404).withBody(errorBody("PRODUCT_NOT_FOUND"))));

        assertThat(client.findById("../admin", Market.PE)).isEmpty();
    }
}
