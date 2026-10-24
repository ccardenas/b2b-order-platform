package com.mariposa.b2b.orderprocessor.domain.model;

import java.math.BigDecimal;

/**
 * Resultado de aplicar {@code LinePricer} a una linea del pedido. Cada
 * importe monetario ya viene redondeado HALF_UP a escala 2 (ver
 * propuesta §4.1 y §7.1): grossSubtotal, discount, netSubtotal, taxAmount
 * y lineTotal son valores finales, no intermedios.
 */
public record PricedOrderLine(
        String productId,
        String sku,
        TaxCategory taxCategory,
        int quantity,
        BigDecimal unitPrice,
        BigDecimal grossSubtotal,
        BigDecimal discountRate,
        BigDecimal discount,
        BigDecimal netSubtotal,
        BigDecimal taxRate,
        BigDecimal taxAmount,
        BigDecimal lineTotal
) {
}
