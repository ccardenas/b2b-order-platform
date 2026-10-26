package com.mariposa.b2b.orderprocessor.infrastructure.http;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * DTO de GET /clients/{clientId} (contracts/http/clients-api.openapi.yaml). Los enums
 * llegan como String: convertirlos es trabajo del mapper, para que un valor desconocido
 * sea un error clasificable y no una excepcion de Jackson.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record ClientResponse(String clientId, String status, String segment, String taxRegime, String market) {
}
