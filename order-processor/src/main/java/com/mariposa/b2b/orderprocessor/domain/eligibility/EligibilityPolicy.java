package com.mariposa.b2b.orderprocessor.domain.eligibility;

import com.mariposa.b2b.orderprocessor.domain.model.ClientSnapshot;
import com.mariposa.b2b.orderprocessor.domain.model.ClientStatus;
import com.mariposa.b2b.orderprocessor.domain.model.OrderItem;
import com.mariposa.b2b.orderprocessor.domain.model.ProductSnapshot;
import com.mariposa.b2b.orderprocessor.domain.model.ProductStatus;
import com.mariposa.b2b.orderprocessor.domain.model.RejectionReason;
import com.mariposa.b2b.orderprocessor.domain.model.ValidatedOrder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Evalua elegibilidad (propuesta §5.1 paso 5 / enunciado §7). Junta TODAS
 * las razones de rechazo que apliquen (S9 de la propuesta), en orden
 * determinista: primero el cliente, luego los productos en el orden del
 * pedido. Una lista vacia significa que el pedido es elegible.
 *
 * {@code client} ausente y {@code productsByProductId} sin una clave
 * representan, respectivamente, "cliente no encontrado" y "producto no
 * encontrado en el mercado": son resultados de negocio del enriquecimiento
 * (un 404 clasificado aguas arriba), no errores tecnicos.
 */
public final class EligibilityPolicy {

    public List<RejectionReason> evaluate(
            ValidatedOrder order,
            Optional<ClientSnapshot> client,
            Map<String, ProductSnapshot> productsByProductId
    ) {
        List<RejectionReason> reasons = new ArrayList<>();

        if (client.isEmpty()) {
            reasons.add(RejectionReason.CLIENT_NOT_FOUND);
        } else {
            ClientSnapshot snapshot = client.get();
            if (snapshot.status() == ClientStatus.BLOCKED) {
                reasons.add(RejectionReason.CLIENT_BLOCKED);
            }
            if (snapshot.market() != order.market()) {
                reasons.add(RejectionReason.CLIENT_MARKET_MISMATCH);
            }
        }

        for (OrderItem item : order.items()) {
            ProductSnapshot product = productsByProductId.get(item.productId());
            if (product == null) {
                reasons.add(RejectionReason.PRODUCT_NOT_FOUND);
            } else if (product.status() == ProductStatus.DISCONTINUED) {
                reasons.add(RejectionReason.PRODUCT_DISCONTINUED);
            }
        }

        return List.copyOf(reasons);
    }
}
