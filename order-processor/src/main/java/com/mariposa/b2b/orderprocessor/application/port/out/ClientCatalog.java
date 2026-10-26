package com.mariposa.b2b.orderprocessor.application.port.out;

import com.mariposa.b2b.orderprocessor.domain.model.ClientSnapshot;

import java.util.Optional;

public interface ClientCatalog {

    /**
     * @return vacio si el cliente no existe (404 CLIENT_NOT_FOUND)
     * @throws ExternalCallFailedException cualquier otro fallo
     */
    Optional<ClientSnapshot> findById(String clientId);
}
