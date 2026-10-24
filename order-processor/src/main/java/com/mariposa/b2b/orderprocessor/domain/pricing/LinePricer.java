package com.mariposa.b2b.orderprocessor.domain.pricing;

import com.mariposa.b2b.orderprocessor.domain.model.ClientSnapshot;
import com.mariposa.b2b.orderprocessor.domain.model.Market;
import com.mariposa.b2b.orderprocessor.domain.model.OrderItem;
import com.mariposa.b2b.orderprocessor.domain.model.PricedOrderLine;
import com.mariposa.b2b.orderprocessor.domain.model.ProductSnapshot;
import com.mariposa.b2b.orderprocessor.domain.model.TaxRegime;

import java.math.BigDecimal;

/**
 * Calcula una linea de pedido siguiendo el orden exacto de la propuesta
 * (§4.1 "Orden de calculo"): gross = qty*price, discount = gross*rate,
 * net = gross-discount, tax = net*taxRate, total = net+tax. Cada importe
 * se redondea HALF_UP a escala 2 de forma independiente, no solo al final.
 *
 * Un cliente con taxRegime=EXEMPT paga 0% sin importar la categoria
 * fiscal del producto (enunciado §7 "Impuestos"): esa excepcion vive aqui,
 * no en {@link TaxRateTable}, porque es una regla sobre el cliente, no
 * sobre la tabla de tasas por mercado/categoria.
 */
public final class LinePricer {

    private final TaxRateTable taxRateTable;
    private final DiscountPolicy discountPolicy;

    public LinePricer(TaxRateTable taxRateTable, DiscountPolicy discountPolicy) {
        this.taxRateTable = taxRateTable;
        this.discountPolicy = discountPolicy;
    }

    public PricedOrderLine price(OrderItem item, ProductSnapshot product, Market market, ClientSnapshot client) {
        BigDecimal grossSubtotal = MoneyRounding.round(
                item.unitPrice().multiply(BigDecimal.valueOf(item.quantity()))
        );

        BigDecimal discountRate = discountPolicy.discountRateFor(client.segment(), item.quantity());
        BigDecimal discount = MoneyRounding.round(grossSubtotal.multiply(discountRate));

        BigDecimal netSubtotal = MoneyRounding.round(grossSubtotal.subtract(discount));

        BigDecimal taxRate = effectiveTaxRate(client, market, product);
        BigDecimal taxAmount = MoneyRounding.round(netSubtotal.multiply(taxRate));

        BigDecimal lineTotal = MoneyRounding.round(netSubtotal.add(taxAmount));

        return new PricedOrderLine(
                item.productId(),
                product.sku(),
                product.taxCategory(),
                item.quantity(),
                item.unitPrice(),
                grossSubtotal,
                discountRate,
                discount,
                netSubtotal,
                taxRate,
                taxAmount,
                lineTotal
        );
    }

    private BigDecimal effectiveTaxRate(ClientSnapshot client, Market market, ProductSnapshot product) {
        if (client.taxRegime() == TaxRegime.EXEMPT) {
            return BigDecimal.ZERO;
        }
        return taxRateTable.rateFor(market, product.taxCategory());
    }
}
