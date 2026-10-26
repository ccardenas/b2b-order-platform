package com.mariposa.b2b.orderprocessor.infrastructure.outbox;

import com.mariposa.b2b.orderprocessor.application.TransitionLog;
import com.mariposa.b2b.orderprocessor.application.port.out.OrderResultStore;
import com.mariposa.b2b.orderprocessor.infrastructure.kafka.out.ProcessedEventJson;
import com.mariposa.b2b.orderprocessor.infrastructure.kafka.out.ProcessedEventSender;
import com.mariposa.b2b.orderprocessor.infrastructure.mongo.MongoPendingOutbox;
import com.mariposa.b2b.orderprocessor.infrastructure.mongo.MongoPendingOutbox.PendingEvent;
import org.slf4j.Logger;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Camino de recuperacion del outbox (§7.2): publica los eventos que siguen PENDING
 * despues de minAge, con su eventId estable, y los marca PUBLISHED. minAge deja que
 * la publicacion inmediata termine primero; aun asi, una publicacion doble es posible
 * (dos instancias, o un fallo entre publicar y marcar) y es aceptable porque el
 * consumidor deduplica por eventId.
 */
public final class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final MongoPendingOutbox pending;
    private final OrderResultStore store;
    private final ProcessedEventSender sender;
    private final ProcessedEventJson json;
    private final Clock clock;
    private final Duration minAge;
    private final int batchSize;
    /** Segundos del PENDING mas viejo segun la ultima pasada; 0 si no hay pendientes. */
    private final AtomicReference<Double> oldestPendingAgeSeconds = new AtomicReference<>(0.0);

    public OutboxRelay(MongoPendingOutbox pending, OrderResultStore store, ProcessedEventSender sender,
                       ProcessedEventJson json, Clock clock, Duration minAge, int batchSize, MeterRegistry registry) {
        this.pending = pending;
        this.store = store;
        this.sender = sender;
        this.json = json;
        this.clock = clock;
        this.minAge = minAge;
        this.batchSize = batchSize;
        // outbox_pending_age_seconds (§7.3): se actualiza en cada pasada del relay, no en cada scrape,
        // para que Prometheus no dispare una consulta a Mongo por cada lectura.
        Gauge.builder("outbox.pending.age", oldestPendingAgeSeconds, AtomicReference::get)
                .description("Antiguedad del evento de outbox PENDING mas viejo")
                .baseUnit("seconds")
                .register(registry);
    }

    /** @return cuantos eventos se publicaron en esta pasada */
    public int relayPending() {
        List<PendingEvent> events;
        try {
            events = pending.olderThan(clock.instant().minus(minAge), batchSize);
        } catch (RuntimeException e) {
            log.warn("outbox relay could not read pending events error={}", e.getClass().getSimpleName());
            return 0;
        }

        int published = 0;
        for (PendingEvent event : events) {
            try {
                sender.send(event.orderId(), json.toJson(event.payload()));
                store.markPublished(event.orderId(), event.outboxEventId(), clock.instant());
                published++;
                MDC.put("orderId", event.orderId());
                TransitionLog.info(log, "published", "outboxEventId", event.outboxEventId(), "path", "relay");
            } catch (RuntimeException e) {
                // Si el broker o Mongo fallan, el resto del lote fallaria igual: se corta y se reintenta en la proxima pasada.
                MDC.put("orderId", event.orderId());
                TransitionLog.warn(log, "publication_pending", "outboxEventId", event.outboxEventId(),
                        "path", "relay", "error", e.getClass().getSimpleName());
                break;
            } finally {
                MDC.remove("orderId");
            }
        }
        updatePendingAge();
        return published;
    }

    private void updatePendingAge() {
        try {
            double age = pending.oldestPendingCreatedAt()
                    .map(createdAt -> Math.max(0, Duration.between(createdAt, clock.instant()).toMillis() / 1000.0))
                    .orElse(0.0);
            oldestPendingAgeSeconds.set(age);
        } catch (RuntimeException e) {
            // Se conserva el ultimo valor: con Mongo caido el gauge no debe bajar a 0 y ocultar el problema.
            log.warn("outbox relay could not measure pending age error={}", e.getClass().getSimpleName());
        }
    }
}
