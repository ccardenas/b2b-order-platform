package com.mariposa.b2b.orderprocessor.application.port.in;

import com.mariposa.b2b.orderprocessor.domain.model.IncomingOrder;

/** Un evento orders.created.v1 ya deserializado, mas de donde vino (para auditoria en el documento). */
public record ProcessOrderCommand(IncomingOrder order, Source source) {

    public record Source(String topic, int partition, long offset) {
    }
}
