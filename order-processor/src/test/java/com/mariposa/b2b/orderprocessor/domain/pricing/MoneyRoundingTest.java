package com.mariposa.b2b.orderprocessor.domain.pricing;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class MoneyRoundingTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "10.125,  10.13",
            "10.115,  10.12",
            "2.005,   2.01",
            "2.004,   2.00",
            "100.995, 101.00",
            "0.005,   0.01",
            "0.004,   0.00"
    })
    void roundsHalfUpToTwoDecimalsOnTheExactMidpoint(String rawValue, String expected) {
        BigDecimal result = MoneyRounding.round(new BigDecimal(rawValue));

        assertThat(result).isEqualByComparingTo(new BigDecimal(expected));
        assertThat(result.scale()).isEqualTo(2);
    }
}
