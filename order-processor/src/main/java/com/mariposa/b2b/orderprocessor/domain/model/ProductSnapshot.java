package com.mariposa.b2b.orderprocessor.domain.model;

/**
 * Snapshot minimo del producto: solo los campos que justifican el calculo
 * (ver propuesta §8.5). No incluye precio, costo ni stock.
 */
public record ProductSnapshot(
        String productId,
        String sku,
        ProductStatus status,
        TaxCategory taxCategory
) {
}
