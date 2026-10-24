package com.mariposa.b2b.orderprocessor.domain.pricing;

import com.mariposa.b2b.orderprocessor.domain.model.Market;
import com.mariposa.b2b.orderprocessor.domain.model.TaxCategory;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Tasas de impuesto por mercado y categoria fiscal (enunciado §7
 * "Impuestos"). Vive en order-processor, no en products-api: la categoria
 * es un dato de catalogo, la tasa es una regla del proceso de pedidos
 * (propuesta §3 "Ubicacion de las reglas fiscales").
 */
public final class TaxRateTable {

    private static final Map<Market, Map<TaxCategory, BigDecimal>> RATES = Map.of(
            Market.MX, Map.of(
                    TaxCategory.STANDARD, new BigDecimal("0.16"),
                    TaxCategory.REDUCED, new BigDecimal("0.08"),
                    TaxCategory.EXEMPT, BigDecimal.ZERO
            ),
            Market.CO, Map.of(
                    TaxCategory.STANDARD, new BigDecimal("0.19"),
                    TaxCategory.REDUCED, new BigDecimal("0.05"),
                    TaxCategory.EXEMPT, BigDecimal.ZERO
            ),
            Market.PE, Map.of(
                    TaxCategory.STANDARD, new BigDecimal("0.18"),
                    TaxCategory.REDUCED, new BigDecimal("0.10"),
                    TaxCategory.EXEMPT, BigDecimal.ZERO
            )
    );

    public BigDecimal rateFor(Market market, TaxCategory category) {
        return RATES.get(market).get(category);
    }
}
