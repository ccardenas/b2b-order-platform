package com.mariposa.b2b.orderprocessor.domain.model;

/**
 * Independiente de {@link ClientSegment} y {@link ClientStatus}: el enunciado
 * de la prueba (§6) expone status/segment/taxRegime como tres campos separados
 * del cliente.
 */
public enum TaxRegime {
    GENERAL,
    SIMPLIFIED,
    EXEMPT
}
