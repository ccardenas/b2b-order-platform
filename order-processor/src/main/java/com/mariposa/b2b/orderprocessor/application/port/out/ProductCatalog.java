package com.mariposa.b2b.orderprocessor.application.port.out;

import com.mariposa.b2b.orderprocessor.domain.model.Market;
import com.mariposa.b2b.orderprocessor.domain.model.ProductSnapshot;

import java.util.Optional;

public interface ProductCatalog {

    /**
     * @return vacio si el producto no existe en ese mercado (404 PRODUCT_NOT_FOUND)
     * @throws ExternalCallFailedException cualquier otro fallo
     */
    Optional<ProductSnapshot> findById(String productId, Market market);
}
