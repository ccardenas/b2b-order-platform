package com.mariposa.b2b.orderprocessor.domain.model;

/**
 * Independiente de {@link ClientSegment} y {@link ClientStatus}: ver docs/prueba
 * tecnica Tech Lead.pdf §6, que expone status/segment/taxRegime como tres
 * campos separados del cliente.
 */
public enum TaxRegime {
    GENERAL,
    SIMPLIFIED,
    EXEMPT
}
