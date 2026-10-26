package com.mariposa.b2b.orderprocessor.infrastructure.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Utilidades comunes a los dos adaptadores: contrato de error compartido (§4.4) y construccion de rutas. */
final class ApiErrors {

    private ApiErrors() {
    }

    /**
     * code del contrato {"error":{"code":...}}, o null si el cuerpo no lo respeta.
     * Importa porque un 404 solo es "el recurso no existe" si trae el code esperado:
     * un ROUTE_NOT_FOUND (URL base mal configurada) no es un rechazo de negocio.
     */
    static String code(ObjectMapper mapper, String body) {
        try {
            JsonNode code = mapper.readTree(body).path("error").path("code");
            return code.isTextual() ? code.asText() : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Codifica un id como segmento de ruta: un "/" o ".." en el evento no debe cambiar la ruta pedida. */
    static String pathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static String trimTrailingSlash(String baseUrl) {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /** Enum desconocido o campo ausente. No incluye el valor recibido: viene de un tercero y acaba en la DLT. */
    static <E extends Enum<E>> E enumValue(Class<E> type, String field, String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("missing " + field);
        }
        try {
            return Enum.valueOf(type, raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown value for " + field);
        }
    }

    static String required(String field, String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("missing " + field);
        }
        return raw;
    }
}
