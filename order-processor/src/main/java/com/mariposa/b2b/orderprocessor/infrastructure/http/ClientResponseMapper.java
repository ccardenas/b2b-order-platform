package com.mariposa.b2b.orderprocessor.infrastructure.http;

import com.mariposa.b2b.orderprocessor.domain.model.ClientSegment;
import com.mariposa.b2b.orderprocessor.domain.model.ClientSnapshot;
import com.mariposa.b2b.orderprocessor.domain.model.ClientStatus;
import com.mariposa.b2b.orderprocessor.domain.model.Market;
import com.mariposa.b2b.orderprocessor.domain.model.TaxRegime;

final class ClientResponseMapper {

    private ClientResponseMapper() {
    }

    /** @throws IllegalArgumentException si falta un campo, un enum es desconocido o el id no es el pedido */
    static ClientSnapshot toDomain(ClientResponse response, String requestedClientId) {
        String clientId = ApiErrors.required("clientId", response.clientId());
        if (!clientId.equals(requestedClientId)) {
            throw new IllegalArgumentException("clientId does not match the request");
        }
        return new ClientSnapshot(
                clientId,
                ApiErrors.enumValue(ClientStatus.class, "status", response.status()),
                ApiErrors.enumValue(ClientSegment.class, "segment", response.segment()),
                ApiErrors.enumValue(TaxRegime.class, "taxRegime", response.taxRegime()),
                ApiErrors.enumValue(Market.class, "market", response.market())
        );
    }
}
