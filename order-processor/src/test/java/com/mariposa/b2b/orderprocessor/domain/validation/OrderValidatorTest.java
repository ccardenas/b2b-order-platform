package com.mariposa.b2b.orderprocessor.domain.validation;

import com.mariposa.b2b.orderprocessor.domain.model.Currency;
import com.mariposa.b2b.orderprocessor.domain.model.IncomingOrder;
import com.mariposa.b2b.orderprocessor.domain.model.Market;
import com.mariposa.b2b.orderprocessor.domain.model.OrderItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class OrderValidatorTest {

    private final OrderValidator validator = new OrderValidator();

    private static IncomingOrder validOrder() {
        return new IncomingOrder(
                "EVT-1",
                1,
                "ORD-MX-0001",
                "MX",
                "MXN",
                "CLI-1",
                List.of(new OrderItem("PRD-1", 2, new BigDecimal("10.00")))
        );
    }

    @Test
    void acceptsAWellFormedOrderAndTypesMarketAndCurrency() {
        ValidationResult result = validator.validate(validOrder());

        assertThat(result).isInstanceOf(ValidationResult.Valid.class);
        var valid = (ValidationResult.Valid) result;
        assertThat(valid.order().market()).isEqualTo(Market.MX);
        assertThat(valid.order().currency()).isEqualTo(Currency.MXN);
        assertThat(valid.order().items()).hasSize(1);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidOrders")
    void rejectsInvalidOrdersWithTheExpectedFailure(String description, IncomingOrder order, ValidationFailure expected) {
        ValidationResult result = validator.validate(order);

        assertThat(result).isInstanceOf(ValidationResult.Invalid.class);
        assertThat(((ValidationResult.Invalid) result).failure()).isEqualTo(expected);
    }

    private static Stream<Arguments> invalidOrders() {
        IncomingOrder base = validOrder();
        return Stream.of(
                Arguments.of("eventId ausente", withEventId(base, null), ValidationFailure.MISSING_EVENT_ID),
                Arguments.of("eventId en blanco", withEventId(base, "   "), ValidationFailure.MISSING_EVENT_ID),
                Arguments.of("eventVersion ausente", withEventVersion(base, null), ValidationFailure.MISSING_EVENT_VERSION),
                Arguments.of("eventVersion invalida (0)", withEventVersion(base, 0), ValidationFailure.INVALID_EVENT_VERSION),
                Arguments.of("orderId ausente", withOrderId(base, null), ValidationFailure.MISSING_ORDER_ID),
                Arguments.of("market ausente", withMarket(base, null), ValidationFailure.MISSING_MARKET),
                Arguments.of("market no soportado", withMarket(base, "BR"), ValidationFailure.UNSUPPORTED_MARKET),
                Arguments.of("currency ausente", withCurrency(base, null), ValidationFailure.MISSING_CURRENCY),
                Arguments.of("currency no corresponde al mercado", withCurrency(base, "COP"), ValidationFailure.CURRENCY_MARKET_MISMATCH),
                Arguments.of("currency desconocida", withCurrency(base, "USD"), ValidationFailure.CURRENCY_MARKET_MISMATCH),
                Arguments.of("clientId ausente", withClientId(base, null), ValidationFailure.MISSING_CLIENT_ID),
                Arguments.of("items vacio", withItems(base, List.of()), ValidationFailure.EMPTY_ITEMS),
                Arguments.of("productId ausente en un item",
                        withItems(base, List.of(new OrderItem(null, 1, BigDecimal.ONE))),
                        ValidationFailure.MISSING_PRODUCT_ID),
                Arguments.of("productId repetido",
                        withItems(base, List.of(
                                new OrderItem("PRD-1", 1, BigDecimal.ONE),
                                new OrderItem("PRD-1", 2, BigDecimal.TEN)
                        )),
                        ValidationFailure.DUPLICATE_PRODUCT_ID),
                Arguments.of("quantity ausente",
                        withItems(base, List.of(new OrderItem("PRD-1", null, BigDecimal.ONE))),
                        ValidationFailure.MISSING_QUANTITY),
                Arguments.of("quantity cero",
                        withItems(base, List.of(new OrderItem("PRD-1", 0, BigDecimal.ONE))),
                        ValidationFailure.NON_POSITIVE_QUANTITY),
                Arguments.of("quantity negativa",
                        withItems(base, List.of(new OrderItem("PRD-1", -1, BigDecimal.ONE))),
                        ValidationFailure.NON_POSITIVE_QUANTITY),
                Arguments.of("unitPrice ausente",
                        withItems(base, List.of(new OrderItem("PRD-1", 1, null))),
                        ValidationFailure.MISSING_UNIT_PRICE),
                Arguments.of("unitPrice negativo",
                        withItems(base, List.of(new OrderItem("PRD-1", 1, new BigDecimal("-0.01")))),
                        ValidationFailure.NEGATIVE_UNIT_PRICE)
        );
    }

    private static IncomingOrder withEventId(IncomingOrder o, String v) {
        return new IncomingOrder(v, o.eventVersion(), o.orderId(), o.market(), o.currency(), o.clientId(), o.items());
    }

    private static IncomingOrder withEventVersion(IncomingOrder o, Integer v) {
        return new IncomingOrder(o.eventId(), v, o.orderId(), o.market(), o.currency(), o.clientId(), o.items());
    }

    private static IncomingOrder withOrderId(IncomingOrder o, String v) {
        return new IncomingOrder(o.eventId(), o.eventVersion(), v, o.market(), o.currency(), o.clientId(), o.items());
    }

    private static IncomingOrder withMarket(IncomingOrder o, String v) {
        return new IncomingOrder(o.eventId(), o.eventVersion(), o.orderId(), v, o.currency(), o.clientId(), o.items());
    }

    private static IncomingOrder withCurrency(IncomingOrder o, String v) {
        return new IncomingOrder(o.eventId(), o.eventVersion(), o.orderId(), o.market(), v, o.clientId(), o.items());
    }

    private static IncomingOrder withClientId(IncomingOrder o, String v) {
        return new IncomingOrder(o.eventId(), o.eventVersion(), o.orderId(), o.market(), o.currency(), v, o.items());
    }

    private static IncomingOrder withItems(IncomingOrder o, List<OrderItem> v) {
        return new IncomingOrder(o.eventId(), o.eventVersion(), o.orderId(), o.market(), o.currency(), o.clientId(), v);
    }
}
