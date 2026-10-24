package com.mariposa.b2b.orderprocessor.domain.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.function.Function;

/**
 * Totales del pedido. {@link #aggregate(List)} suma los importes de linea
 * YA redondeados (grossSubtotal, discount, netSubtotal, taxAmount,
 * lineTotal de cada {@link PricedOrderLine}), nunca recalcula a partir de
 * valores sin redondear: es la regla explicita de la propuesta ("los
 * totales del pedido son la suma de los importes de linea ya redondeados").
 */
public record OrderTotals(
        BigDecimal grossSubtotal,
        BigDecimal discount,
        BigDecimal netSubtotal,
        BigDecimal tax,
        BigDecimal grandTotal
) {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    public static OrderTotals aggregate(List<PricedOrderLine> lines) {
        return new OrderTotals(
                sum(lines, PricedOrderLine::grossSubtotal),
                sum(lines, PricedOrderLine::discount),
                sum(lines, PricedOrderLine::netSubtotal),
                sum(lines, PricedOrderLine::taxAmount),
                sum(lines, PricedOrderLine::lineTotal)
        );
    }

    private static BigDecimal sum(List<PricedOrderLine> lines, Function<PricedOrderLine, BigDecimal> field) {
        return lines.stream()
                .map(field)
                .reduce(ZERO, BigDecimal::add);
    }
}
