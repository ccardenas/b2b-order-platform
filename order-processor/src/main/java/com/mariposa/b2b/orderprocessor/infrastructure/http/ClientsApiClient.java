package com.mariposa.b2b.orderprocessor.infrastructure.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mariposa.b2b.orderprocessor.application.port.out.ClientCatalog;
import com.mariposa.b2b.orderprocessor.application.port.out.ExternalCallFailedException;
import com.mariposa.b2b.orderprocessor.application.port.out.ExternalCallFailedException.Kind;
import com.mariposa.b2b.orderprocessor.domain.model.ClientSnapshot;

import java.net.URI;
import java.util.Optional;

/**
 * Adaptador de GET /clients/{clientId}. La clasificacion de errores de §5.2:
 * 200 -> cliente; 404 CLIENT_NOT_FOUND -> vacio (rechazo de negocio); 429/5xx/timeout
 * -> ya reintentados por {@link ResilientHttpClient}, llegan como TRANSIENT; todo lo
 * demas (otros 4xx, cuerpo invalido, enum desconocido) -> PERMANENT sin reintento.
 */
public final class ClientsApiClient implements ClientCatalog {

    private static final String SERVICE = "clients-api";
    private static final String NOT_FOUND_CODE = "CLIENT_NOT_FOUND";

    private final ResilientHttpClient http;
    private final ObjectMapper mapper;
    private final String baseUrl;

    public ClientsApiClient(ResilientHttpClient http, ObjectMapper mapper, URI baseUrl) {
        this.http = http;
        this.mapper = mapper;
        this.baseUrl = ApiErrors.trimTrailingSlash(baseUrl.toString());
    }

    @Override
    public Optional<ClientSnapshot> findById(String clientId) {
        URI uri = URI.create(baseUrl + "/clients/" + ApiErrors.pathSegment(clientId));
        ResilientHttpClient.Response response = http.get(SERVICE, uri);

        if (response.status() == 200) {
            return Optional.of(parse(response, clientId));
        }
        if (response.status() == 404 && NOT_FOUND_CODE.equals(ApiErrors.code(mapper, response.body()))) {
            return Optional.empty();
        }
        throw permanent("unexpected HTTP " + response.status(), response.attempts());
    }

    private ClientSnapshot parse(ResilientHttpClient.Response response, String clientId) {
        try {
            return ClientResponseMapper.toDomain(mapper.readValue(response.body(), ClientResponse.class), clientId);
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
