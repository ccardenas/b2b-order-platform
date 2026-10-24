package com.mariposa.b2b.orderprocessor.domain.validation;

import com.mariposa.b2b.orderprocessor.domain.model.Currency;
import com.mariposa.b2b.orderprocessor.domain.model.IncomingOrder;
import com.mariposa.b2b.orderprocessor.domain.model.Market;
import com.mariposa.b2b.orderprocessor.domain.model.OrderItem;
import com.mariposa.b2b.orderprocessor.domain.model.ValidatedOrder;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Valida el contrato de entrada (propuesta §5.1 paso 2). Se detiene en la
 * primera violacion encontrada: a diferencia de la elegibilidad (que junta
 * TODAS las razones, S9 de la propuesta), nada en el enunciado pide
 * acumular fallos de validacion, y devolver el primero mantiene el caso de
 * uso simple.
 */
public final class OrderValidator {

    private static final Map<Market, Currency> CURRENCY_BY_MARKET = Map.of(
            Market.MX, Currency.MXN,
            Market.CO, Currency.COP,
            Market.PE, Currency.PEN
    );

    public ValidationResult validate(IncomingOrder order) {
        if (isBlank(order.eventId())) {
            return invalid(ValidationFailure.MISSING_EVENT_ID);
        }
        if (order.eventVersion() == null) {
            return invalid(ValidationFailure.MISSING_EVENT_VERSION);
        }
        if (order.eventVersion() < 1) {
            return invalid(ValidationFailure.INVALID_EVENT_VERSION);
        }
        if (isBlank(order.orderId())) {
            return invalid(ValidationFailure.MISSING_ORDER_ID);
        }
        if (isBlank(order.market())) {
            return invalid(ValidationFailure.MISSING_MARKET);
        }
        Market market = parseEnum(Market.class, order.market());
        if (market == null) {
            return invalid(ValidationFailure.UNSUPPORTED_MARKET);
        }
        if (isBlank(order.currency())) {
            return invalid(ValidationFailure.MISSING_CURRENCY);
        }
        Currency currency = parseEnum(Currency.class, order.currency());
        if (currency != CURRENCY_BY_MARKET.get(market)) {
            return invalid(ValidationFailure.CURRENCY_MARKET_MISMATCH);
        }
        if (isBlank(order.clientId())) {
            return invalid(ValidationFailure.MISSING_CLIENT_ID);
        }
        if (order.items() == null || order.items().isEmpty()) {
            return invalid(ValidationFailure.EMPTY_ITEMS);
        }

        ValidationResult itemsFailure = validateItems(order.items());
        if (itemsFailure != null) {
            return itemsFailure;
        }

        ValidatedOrder validated = new ValidatedOrder(
                order.eventId(),
                order.eventVersion(),
                order.orderId(),
                market,
                currency,
                order.clientId(),
                order.items()
        );
        return new ValidationResult.Valid(validated);
    }

    private ValidationResult validateItems(List<OrderItem> items) {
        Set<String> seenProductIds = new HashSet<>();
        for (OrderItem item : items) {
            if (isBlank(item.productId())) {
                return invalid(ValidationFailure.MISSING_PRODUCT_ID);
            }
            if (!seenProductIds.add(item.productId())) {
                return invalid(ValidationFailure.DUPLICATE_PRODUCT_ID);
            }
            if (item.quantity() == null) {
                return invalid(ValidationFailure.MISSING_QUANTITY);
            }
            if (item.quantity() <= 0) {
                return invalid(ValidationFailure.NON_POSITIVE_QUANTITY);
            }
            if (item.unitPrice() == null) {
                return invalid(ValidationFailure.MISSING_UNIT_PRICE);
            }
            if (item.unitPrice().signum() < 0) {
                return invalid(ValidationFailure.NEGATIVE_UNIT_PRICE);
            }
        }
        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw) {
        try {
            return Enum.valueOf(type, raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static ValidationResult invalid(ValidationFailure failure) {
        return new ValidationResult.Invalid(failure);
    }
}
