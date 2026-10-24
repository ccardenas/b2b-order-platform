package com.mariposa.b2b.orderprocessor.domain.pricing;

import com.mariposa.b2b.orderprocessor.domain.model.Market;
import com.mariposa.b2b.orderprocessor.domain.model.TaxCategory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class TaxRateTableTest {

    private final TaxRateTable table = new TaxRateTable();

    @ParameterizedTest(name = "{0} / {1} -> {2}")
    @CsvSource({
            "MX, STANDARD, 0.16",
            "MX, REDUCED,  0.08",
            "MX, EXEMPT,   0.00",
            "CO, STANDARD, 0.19",
            "CO, REDUCED,  0.05",
            "CO, EXEMPT,   0.00",
            "PE, STANDARD, 0.18",
            "PE, REDUCED,  0.10",
            "PE, EXEMPT,   0.00"
    })
    void returnsTheRateForEveryMarketAndTaxCategoryCombination(Market market, TaxCategory category, String expectedRate) {
        BigDecimal rate = table.rateFor(market, category);

        assertThat(rate).isEqualByComparingTo(new BigDecimal(expectedRate));
    }
}
