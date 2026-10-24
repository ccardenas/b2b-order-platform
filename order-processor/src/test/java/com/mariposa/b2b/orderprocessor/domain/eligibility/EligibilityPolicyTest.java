package com.mariposa.b2b.orderprocessor.domain.eligibility;

import com.mariposa.b2b.orderprocessor.domain.model.ClientSegment;
import com.mariposa.b2b.orderprocessor.domain.model.ClientSnapshot;
import com.mariposa.b2b.orderprocessor.domain.model.ClientStatus;
import com.mariposa.b2b.orderprocessor.domain.model.Currency;
import com.mariposa.b2b.orderprocessor.domain.model.Market;
import com.mariposa.b2b.orderprocessor.domain.model.OrderItem;
import com.mariposa.b2b.orderprocessor.domain.model.ProductSnapshot;
import com.mariposa.b2b.orderprocessor.domain.model.ProductStatus;
import com.mariposa.b2b.orderprocessor.domain.model.RejectionReason;
import com.mariposa.b2b.orderprocessor.domain.model.TaxCategory;
import com.mariposa.b2b.orderprocessor.domain.model.TaxRegime;
import com.mariposa.b2b.orderprocessor.domain.model.ValidatedOrder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class EligibilityPolicyTest {

    private final EligibilityPolicy policy = new EligibilityPolicy();

    @Test
    void eligibleWhenClientActiveMarketMatchesAndProductsActive() {
        ValidatedOrder order = orderFor(Market.MX, "PRD-1", "PRD-2");
        Map<String, ProductSnapshot> products = Map.of(
                "PRD-1", product("PRD-1", ProductStatus.ACTIVE),
                "PRD-2", product("PRD-2", ProductStatus.ACTIVE)
        );

        List<RejectionReason> reasons = policy.evaluate(order, Optional.of(client(ClientStatus.ACTIVE, Market.MX)), products);

        assertThat(reasons).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectionCases")
    void collectsEachRejectionReasonInOrder(
            String description,
            ValidatedOrder order,
            Optional<ClientSnapshot> client,
            Map<String, ProductSnapshot> products,
            List<RejectionReason> expectedReasons
    ) {
        List<RejectionReason> reasons = policy.evaluate(order, client, products);

        assertThat(reasons).containsExactlyElementsOf(expectedReasons);
    }

    private static Stream<Arguments> rejectionCases() {
        ValidatedOrder singleItemOrder = orderFor(Market.MX, "PRD-1");
        ValidatedOrder twoItemOrder = orderFor(Market.MX, "PRD-1", "PRD-2");

        return Stream.of(
                Arguments.of(
                        "cliente no encontrado",
                        singleItemOrder,
                        Optional.empty(),
                        Map.of("PRD-1", product("PRD-1", ProductStatus.ACTIVE)),
                        List.of(RejectionReason.CLIENT_NOT_FOUND)
                ),
                Arguments.of(
                        "cliente bloqueado",
                        singleItemOrder,
                        Optional.of(client(ClientStatus.BLOCKED, Market.MX)),
                        Map.of("PRD-1", product("PRD-1", ProductStatus.ACTIVE)),
                        List.of(RejectionReason.CLIENT_BLOCKED)
                ),
                Arguments.of(
                        "mercado del cliente no coincide con el del pedido",
                        singleItemOrder,
                        Optional.of(client(ClientStatus.ACTIVE, Market.CO)),
                        Map.of("PRD-1", product("PRD-1", ProductStatus.ACTIVE)),
                        List.of(RejectionReason.CLIENT_MARKET_MISMATCH)
                ),
                Arguments.of(
                        "producto no encontrado en el mercado",
                        singleItemOrder,
                        Optional.of(client(ClientStatus.ACTIVE, Market.MX)),
                        Map.<String, ProductSnapshot>of(),
                        List.of(RejectionReason.PRODUCT_NOT_FOUND)
                ),
                Arguments.of(
                        "producto discontinuado",
                        singleItemOrder,
                        Optional.of(client(ClientStatus.ACTIVE, Market.MX)),
                        Map.of("PRD-1", product("PRD-1", ProductStatus.DISCONTINUED)),
                        List.of(RejectionReason.PRODUCT_DISCONTINUED)
                ),
                Arguments.of(
                        "cliente bloqueado y fuera de mercado, mas producto discontinuado en el orden del pedido",
                        twoItemOrder,
                        Optional.of(client(ClientStatus.BLOCKED, Market.CO)),
                        Map.of(
                                "PRD-1", product("PRD-1", ProductStatus.ACTIVE),
                                "PRD-2", product("PRD-2", ProductStatus.DISCONTINUED)
                        ),
                        List.of(RejectionReason.CLIENT_BLOCKED, RejectionReason.CLIENT_MARKET_MISMATCH, RejectionReason.PRODUCT_DISCONTINUED)
                )
        );
    }

    private static ValidatedOrder orderFor(Market market, String... productIds) {
        List<OrderItem> items = Stream.of(productIds)
                .map(id -> new OrderItem(id, 1, BigDecimal.TEN))
                .toList();
        Currency currency = switch (market) {
            case MX -> Currency.MXN;
            case CO -> Currency.COP;
            case PE -> Currency.PEN;
        };
        return new ValidatedOrder("EVT-1", 1, "ORD-1", market, currency, "CLI-1", items);
    }

    private static ClientSnapshot client(ClientStatus status, Market market) {
        return new ClientSnapshot("CLI-1", status, ClientSegment.RETAIL, TaxRegime.GENERAL, market);
    }

    private static ProductSnapshot product(String id, ProductStatus status) {
        return new ProductSnapshot(id, "SKU-" + id, status, TaxCategory.STANDARD);
    }
}
