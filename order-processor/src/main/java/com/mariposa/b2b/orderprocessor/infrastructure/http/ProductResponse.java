package com.mariposa.b2b.orderprocessor.infrastructure.http;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** DTO de GET /products/{productId} (contracts/http/products-api.openapi.yaml). Enums como String, igual que {@link ClientResponse}. */
@JsonIgnoreProperties(ignoreUnknown = true)
record ProductResponse(String productId, String sku, String status, String taxCategory) {
}
