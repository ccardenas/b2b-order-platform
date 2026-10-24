package com.mariposa.b2b.orderprocessor.domain.pricing;

import com.mariposa.b2b.orderprocessor.domain.model.ClientSegment;

import java.math.BigDecimal;

/**
 * Descuento mayorista (enunciado §7 "Descuento"): 3% sobre el subtotal de
 * la linea cuando el cliente es WHOLESALE y la linea tiene 20 unidades o
 * mas de ese producto. Es un umbral por linea, no por pedido completo.
 */
public final class DiscountPolicy {

    static final int WHOLESALE_MIN_QUANTITY = 20;
    static final BigDecimal WHOLESALE_DISCOUNT_RATE = new BigDecimal("0.03");

    public BigDecimal discountRateFor(ClientSegment segment, int quantity) {
        if (segment == ClientSegment.WHOLESALE && quantity >= WHOLESALE_MIN_QUANTITY) {
            return WHOLESALE_DISCOUNT_RATE;
        }
        return BigDecimal.ZERO;
    }
}
