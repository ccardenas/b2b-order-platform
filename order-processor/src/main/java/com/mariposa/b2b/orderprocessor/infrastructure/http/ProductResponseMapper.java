package com.mariposa.b2b.orderprocessor.infrastructure.http;

import com.mariposa.b2b.orderprocessor.domain.model.ProductSnapshot;
import com.mariposa.b2b.orderprocessor.domain.model.ProductStatus;
import com.mariposa.b2b.orderprocessor.domain.model.TaxCategory;

final class ProductResponseMapper {

    private ProductResponseMapper() {
    }

    /** @throws IllegalArgumentException si falta un campo, un enum es desconocido o el id no es el pedido */
    static ProductSnapshot toDomain(ProductResponse response, String requestedProductId) {
        String productId = ApiErrors.required("productId", response.productId());
        if (!productId.equals(requestedProductId)) {
            throw new IllegalArgumentException("productId does not match the request");
        }
        return new ProductSnapshot(
                productId,
                ApiErrors.required("sku", response.sku()),
                ApiErrors.enumValue(ProductStatus.class, "status", response.status()),
                ApiErrors.enumValue(TaxCategory.class, "taxCategory", response.taxCategory())
        );
    }
}
