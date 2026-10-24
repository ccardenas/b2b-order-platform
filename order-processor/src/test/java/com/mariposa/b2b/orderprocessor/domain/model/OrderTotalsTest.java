package com.mariposa.b2b.orderprocessor.domain.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OrderTotalsTest {

    @Test
    void sumsAlreadyRoundedLineAmountsInsteadOfRoundingTheSumOfRawAmounts() {
        // Dos lineas cuyo taxAmount sin redondear seria 1.004 cada una.
        // Redondeado POR LINEA (lo que exige la propuesta): 1.00 + 1.00 = 2.00.
        // Si en cambio se sumara antes de redondear: 1.004 + 1.004 = 2.008 -> 2.01.
        // Esta prueba falla si OrderTotals llegara a recalcular desde valores
        // sin redondear en vez de sumar los campos ya redondeados de cada linea.
        PricedOrderLine lineA = lineWithRawTax("PRD-1", "1.004");
        PricedOrderLine lineB = lineWithRawTax("PRD-2", "1.004");

        OrderTotals totals = OrderTotals.aggregate(List.of(lineA, lineB));

        assertThat(totals.tax()).isEqualByComparingTo("2.00");
        assertThat(totals.grossSubtotal()).isEqualByComparingTo("20.00");
        assertThat(totals.netSubtotal()).isEqualByComparingTo("20.00");
        assertThat(totals.grandTotal()).isEqualByComparingTo("22.00");
    }

    @Test
    void aggregatesAnEmptyListToZero() {
        OrderTotals totals = OrderTotals.aggregate(List.of());

        assertThat(totals.grossSubtotal()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(totals.grandTotal()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    private static PricedOrderLine lineWithRawTax(String productId, String rawTaxAmount) {
        BigDecimal roundedTax = new BigDecimal(rawTaxAmount).setScale(2, RoundingMode.HALF_UP);
        BigDecimal gross = new BigDecimal("10.00");
        BigDecimal discount = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        BigDecimal net = gross.subtract(discount);
        BigDecimal total = net.add(roundedTax);

        return new PricedOrderLine(
                productId,
                "SKU-" + productId,
                TaxCategory.STANDARD,
                1,
                gross,
                gross,
                BigDecimal.ZERO,
                discount,
                net,
                new BigDecimal("0.10"),
                roundedTax,
                total
        );
    }
}
