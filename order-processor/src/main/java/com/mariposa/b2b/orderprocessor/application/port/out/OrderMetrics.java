package com.mariposa.b2b.orderprocessor.application.port.out;

import com.mariposa.b2b.orderprocessor.domain.model.Market;
import com.mariposa.b2b.orderprocessor.domain.model.OrderStatus;
import com.mariposa.b2b.orderprocessor.domain.model.RejectionReason;

/**
 * Metricas de negocio del anexo de observabilidad. Es un puerto para que application/
 * no dependa de Micrometer; las metricas tecnicas (retries HTTP, DLT, duracion, outbox,
 * lag) se registran directamente en infrastructure/.
 */
public interface OrderMetrics {

    /** orders_processed_total{status,market}: un resultado escrito, incluido TECHNICAL_FAILURE. */
    void processed(OrderStatus status, Market market);

    /** orders_rejected_total{reason}: una vez por cada razon de un pedido rechazado. */
    void rejected(RejectionReason reason);

    /** orders_duplicates_total */
    void duplicate();

    /** orders_stale_total */
    void stale();
}
