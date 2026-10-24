package com.mariposa.b2b.orderprocessor.domain.pricing;

import com.mariposa.b2b.orderprocessor.domain.model.ClientSegment;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class DiscountPolicyTest {

    private final DiscountPolicy policy = new DiscountPolicy();

    @ParameterizedTest(name = "WHOLESALE con {0} unidades -> tasa {1}")
    @CsvSource({
            "19, 0.00",
            "20, 0.03",
            "21, 0.03"
    })
    void appliesTheWholesaleDiscountAtTwentyUnitsOrMore(int quantity, String expectedRate) {
        BigDecimal rate = policy.discountRateFor(ClientSegment.WHOLESALE, quantity);

        assertThat(rate).isEqualByComparingTo(new BigDecimal(expectedRate));
    }

    @ParameterizedTest(name = "RETAIL con {0} unidades -> sin descuento")
    @CsvSource({"19", "20", "21", "100"})
    void neverDiscountsRetailClientsRegardlessOfQuantity(int quantity) {
        BigDecimal rate = policy.discountRateFor(ClientSegment.RETAIL, quantity);

        assertThat(rate).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
