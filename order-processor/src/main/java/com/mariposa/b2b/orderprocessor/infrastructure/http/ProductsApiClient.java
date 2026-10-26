package com.mariposa.b2b.orderprocessor.infrastructure.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mariposa.b2b.orderprocessor.application.port.out.ExternalCallFailedException;
import com.mariposa.b2b.orderprocessor.application.port.out.ExternalCallFailedException.Kind;
import com.mariposa.b2b.orderprocessor.application.port.out.ProductCatalog;
import com.mariposa.b2b.orderprocessor.domain.model.Market;
import com.mariposa.b2b.orderprocessor.domain.model.ProductSnapshot;

import java.net.URI;
import java.util.Optional;

/** Adaptador de GET /products/{productId}?market=. Misma clasificacion que {@link ClientsApiClient}. */
public final class ProductsApiClient implements ProductCatalog {

    private static final String SERVICE = "products-api";
    private static final String NOT_FOUND_CODE = "PRODUCT_NOT_FOUND";

    private final ResilientHttpClient http;
    private final ObjectMapper mapper;
    private final String baseUrl;

    public ProductsApiClient(ResilientHttpClient http, ObjectMapper mapper, URI baseUrl) {
        this.http = http;
        this.mapper = mapper;
        this.baseUrl = ApiErrors.trimTrailingSlash(baseUrl.toString());
    }

    @Override
    public Optional<ProductSnapshot> findById(String productId, Market market) {
        URI uri = URI.create(baseUrl + "/products/" + ApiErrors.pathSegment(productId) + "?market=" + market.name());
        ResilientHttpClient.Response response = http.get(SERVICE, uri);

        if (response.status() == 200) {
            return Optional.of(parse(response, productId));
        }
        if (response.status() == 404 && NOT_FOUND_CODE.equals(ApiErrors.code(mapper, response.body()))) {
            return Optional.empty();
        }
        throw permanent("unexpected HTTP " + response.status(), response.attempts());
    }

    private ProductSnapshot parse(ResilientHttpClient.Response response, String productId) {
        try {
            return ProductResponseMapper.toDomain(mapper.readValue(response.body(), ProductResponse.class), productId);
        } catch (JsonProcessingException e) {
            throw permanent("response body is not valid JSON", response.attempts());
        } catch (IllegalArgumentException e) {
            throw permanent("invalid response: " + e.getMessage(), response.attempts());
        }
    }

    private static ExternalCallFailedException permanent(String cause, int attempts) {
        return new ExternalCallFailedException(Kind.PERMANENT, SERVICE + ": " + cause, attempts);
    }
}
