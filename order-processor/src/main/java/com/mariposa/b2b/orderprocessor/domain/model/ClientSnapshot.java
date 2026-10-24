package com.mariposa.b2b.orderprocessor.domain.model;

/**
 * Snapshot minimo del cliente: solo los campos que justifican el calculo
 * (ver propuesta §8.5). No replica el maestro completo de clients-api.
 */
public record ClientSnapshot(
        String clientId,
        ClientStatus status,
        ClientSegment segment,
        TaxRegime taxRegime,
        Market market
) {
}
