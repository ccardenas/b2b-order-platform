package com.mariposa.b2b.orderprocessor.domain.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Redondeo monetario unico del sistema: escala 2, HALF_UP por importe
 * (propuesta: "Dinero"). Se aplica a cada importe de linea de forma
 * independiente, no solo al total final.
 */
public final class MoneyRounding {

    private static final int SCALE = 2;

    private MoneyRounding() {
    }

    public static BigDecimal round(BigDecimal value) {
        return value.setScale(SCALE, RoundingMode.HALF_UP);
    }
}
