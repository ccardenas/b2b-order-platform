package com.mariposa.b2b.orderprocessor.infrastructure.metrics;

import com.mariposa.b2b.orderprocessor.application.port.out.OrderMetrics;
import com.mariposa.b2b.orderprocessor.domain.model.Market;
import com.mariposa.b2b.orderprocessor.domain.model.OrderStatus;
import com.mariposa.b2b.orderprocessor.domain.model.RejectionReason;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Nombres con la convencion de Micrometer (puntos); el registry de Prometheus los
 * publica como en el anexo: orders.processed -> orders_processed_total. Las etiquetas
 * son enums: cardinalidad acotada (3 status x 3 mercados, 5 razones).
 */
public final class MicrometerOrderMetrics implements OrderMetrics {

    private final MeterRegistry registry;

    public MicrometerOrderMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void processed(OrderStatus status, Market market) {
        registry.counter("orders.processed", "status", status.name(), "market", market.name()).increment();
    }

    @Override
    public void rejected(RejectionReason reason) {
        registry.counter("orders.rejected", "reason", reason.name()).increment();
    }

    @Override
    public void duplicate() {
        registry.counter("orders.duplicates").increment();
    }

    @Override
    public void stale() {
        registry.counter("orders.stale").increment();
    }
}
