package com.mariposa.b2b.orderprocessor.domain.pricing;

import com.mariposa.b2b.orderprocessor.domain.model.ClientSegment;
import com.mariposa.b2b.orderprocessor.domain.model.ClientSnapshot;
import com.mariposa.b2b.orderprocessor.domain.model.ClientStatus;
import com.mariposa.b2b.orderprocessor.domain.model.Market;
import com.mariposa.b2b.orderprocessor.domain.model.OrderItem;
import com.mariposa.b2b.orderprocessor.domain.model.PricedOrderLine;
import com.mariposa.b2b.orderprocessor.domain.model.ProductSnapshot;
import com.mariposa.b2b.orderprocessor.domain.model.ProductStatus;
import com.mariposa.b2b.orderprocessor.domain.model.TaxCategory;
import com.mariposa.b2b.orderprocessor.domain.model.TaxRegime;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class LinePricerTest {

    private final LinePricer pricer = new LinePricer(new TaxRateTable(), new DiscountPolicy());

    @Test
    void pricesTheReferenceLineFromTheArchitectureProposalExactly() {
        // ORD-MX-000147, linea PRD-001 del documento de ejemplo (propuesta §7.1):
        // 24 x 35.50, cliente WHOLESALE/GENERAL en MX, producto STANDARD.
        OrderItem item = new OrderItem("PRD-001", 24, new BigDecimal("35.50"));
        ProductSnapshot product = new ProductSnapshot("PRD-001", "BEB-600-PET", ProductStatus.ACTIVE, TaxCategory.STANDARD);
        ClientSnapshot client = new ClientSnapshot("CLI-99821", ClientStatus.ACTIVE, ClientSegment.WHOLESALE, TaxRegime.GENERAL, Market.MX);

        PricedOrderLine line = pricer.price(item, product, Market.MX, client);

        assertThat(line.grossSubtotal()).isEqualByComparingTo("852.00");
        assertThat(line.discountRate()).isEqualByComparingTo("0.03");
        assertThat(line.discount()).isEqualByComparingTo("25.56");
        assertThat(line.netSubtotal()).isEqualByComparingTo("826.44");
        assertThat(line.taxRate()).isEqualByComparingTo("0.16");
        assertThat(line.taxAmount()).isEqualByComparingTo("132.23");
        assertThat(line.lineTotal()).isEqualByComparingTo("958.67");
    }

    @Test
    void exemptClientPaysZeroTaxRegardlessOfProductTaxCategory() {
        OrderItem item = new OrderItem("PRD-050", 1, new BigDecimal("100.00"));
        ProductSnapshot product = new ProductSnapshot("PRD-050", "SKU-050", ProductStatus.ACTIVE, TaxCategory.STANDARD);
        ClientSnapshot exemptClient = new ClientSnapshot("CLI-2", ClientStatus.ACTIVE, ClientSegment.RETAIL, TaxRegime.EXEMPT, Market.CO);

        PricedOrderLine line = pricer.price(item, product, Market.CO, exemptClient);

        assertThat(line.taxRate()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(line.taxAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(line.lineTotal()).isEqualByComparingTo(line.netSubtotal());
    }

    @Test
    void roundsEveryMonetaryFieldToExactlyTwoDecimalsHalfUp() {
        // 7 x 1.075 = 7.525 sin redondear: el tercer decimal (5) debe
        // redondear hacia arriba a 7.53 en cuanto se calcula grossSubtotal,
        // no al final.
        OrderItem item = new OrderItem("PRD-900", 7, new BigDecimal("1.075"));
        ProductSnapshot product = new ProductSnapshot("PRD-900", "SKU-900", ProductStatus.ACTIVE, TaxCategory.EXEMPT);
        ClientSnapshot client = new ClientSnapshot("CLI-3", ClientStatus.ACTIVE, ClientSegment.RETAIL, TaxRegime.GENERAL, Market.PE);

        PricedOrderLine line = pricer.price(item, product, Market.PE, client);

        assertThat(line.grossSubtotal()).isEqualByComparingTo("7.53");
        assertThat(line.grossSubtotal().scale()).isEqualTo(2);
        assertThat(line.discount().scale()).isEqualTo(2);
        assertThat(line.netSubtotal().scale()).isEqualTo(2);
        assertThat(line.taxAmount().scale()).isEqualTo(2);
        assertThat(line.lineTotal().scale()).isEqualTo(2);
    }
}
