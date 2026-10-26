package com.mariposa.b2b.orderprocessor.infrastructure.outbox;

import com.mariposa.b2b.orderprocessor.application.port.out.OrderResultStore;
import com.mariposa.b2b.orderprocessor.infrastructure.kafka.out.ProcessedEventJson;
import com.mariposa.b2b.orderprocessor.infrastructure.kafka.out.ProcessedEventSender;
import com.mariposa.b2b.orderprocessor.infrastructure.mongo.MongoPendingOutbox;
import com.mariposa.b2b.orderprocessor.infrastructure.mongo.MongoPendingOutbox.PendingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

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

    public OutboxRelay(MongoPendingOutbox pending, OrderResultStore store, ProcessedEventSender sender,
                       ProcessedEventJson json, Clock clock, Duration minAge, int batchSize) {
        this.pending = pending;
        this.store = store;
        this.sender = sender;
        this.json = json;
        this.clock = clock;
        this.minAge = minAge;
        this.batchSize = batchSize;
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
                log.info("outbox event relayed orderId={} outboxEventId={}", event.orderId(), event.outboxEventId());
            } catch (RuntimeException e) {
                // Si el broker o Mongo fallan, el resto del lote fallaria igual: se corta y se reintenta en la proxima pasada.
                log.warn("outbox relay stopped, will retry orderId={} outboxEventId={} error={}",
                        event.orderId(), event.outboxEventId(), e.getClass().getSimpleName());
                break;
            }
        }
        return published;
    }
}
