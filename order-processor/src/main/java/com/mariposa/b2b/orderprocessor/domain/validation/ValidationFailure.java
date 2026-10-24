package com.mariposa.b2b.orderprocessor.domain.validation;

/**
 * Razones por las que un {@code IncomingOrder} no pasa la validacion de
 * contrato (propuesta §5.1 paso 2 / enunciado §5 "Validaciones minimas").
 * Externamente todas se clasifican como VALIDATION (propuesta §5.2); esta
 * granularidad es interna, para logs y tests.
 */
public enum ValidationFailure {
    MISSING_EVENT_ID,
    MISSING_EVENT_VERSION,
    INVALID_EVENT_VERSION,
    MISSING_ORDER_ID,
    MISSING_MARKET,
    UNSUPPORTED_MARKET,
    MISSING_CURRENCY,
    CURRENCY_MARKET_MISMATCH,
    MISSING_CLIENT_ID,
    EMPTY_ITEMS,
    MISSING_PRODUCT_ID,
    DUPLICATE_PRODUCT_ID,
    MISSING_QUANTITY,
    NON_POSITIVE_QUANTITY,
    MISSING_UNIT_PRICE,
    NEGATIVE_UNIT_PRICE
}
