package com.mariposa.b2b.orderprocessor.domain.model;

import java.math.BigDecimal;

/**
 * Linea del pedido tal como llega del evento de entrada (antes de pricing).
 * Los campos son deliberadamente nullable (Integer, no int) para que
 * {@link com.mariposa.b2b.orderprocessor.domain.validation.OrderValidator}
 * pueda representar "ausente" sin depender de excepciones de construccion.
 * Una vez que un pedido paso la validacion, se asume (por contrato, no por
 * el tipo) que quantity y unitPrice no son null.
 */
public record OrderItem(
        String productId,
        Integer quantity,
        BigDecimal unitPrice
) {
}
