package com.mariposa.b2b.orderprocessor.infrastructure.mongo;

import com.mariposa.b2b.orderprocessor.application.port.out.OrderStorageException;
import com.mongodb.MongoException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Sorts;
import org.bson.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import static com.mariposa.b2b.orderprocessor.infrastructure.mongo.OrderDocumentMapper.ID;
import static com.mariposa.b2b.orderprocessor.infrastructure.mongo.OrderDocumentMapper.OUTBOX;
import static com.mariposa.b2b.orderprocessor.infrastructure.mongo.OrderDocumentMapper.OUTBOX_CREATED_AT;
import static com.mariposa.b2b.orderprocessor.infrastructure.mongo.OrderDocumentMapper.OUTBOX_EVENT_ID;
import static com.mariposa.b2b.orderprocessor.infrastructure.mongo.OrderDocumentMapper.OUTBOX_PENDING;
import static com.mariposa.b2b.orderprocessor.infrastructure.mongo.OrderDocumentMapper.OUTBOX_STATUS;

/** Lectura del relay: eventos PENDING mas viejos que un corte. Usa el indice parcial de §7.4. */
public final class MongoPendingOutbox {

    public record PendingEvent(String orderId, String outboxEventId, Document payload) {
    }

    private final MongoCollection<Document> orders;

    public MongoPendingOutbox(MongoCollection<Document> orders) {
        this.orders = orders;
    }

    public List<PendingEvent> olderThan(Instant cutoff, int limit) {
        try {
            return orders.find(Filters.and(
                            Filters.eq(OUTBOX_STATUS, OUTBOX_PENDING),
                            Filters.lt(OUTBOX_CREATED_AT, Date.from(cutoff))))
                    .sort(Sorts.ascending(OUTBOX_CREATED_AT))
                    .limit(limit)
                    .projection(Projections.include(OUTBOX_EVENT_ID, OUTBOX + ".payload"))
                    .map(document -> {
                        Document outbox = document.get(OUTBOX, Document.class);
                        return new PendingEvent(document.getString(ID), outbox.getString("eventId"),
                                outbox.get("payload", Document.class));
                    })
                    .into(new ArrayList<>());
        } catch (MongoException e) {
            throw new OrderStorageException("could not read pending outbox events", e);
        }
    }

    /** createdAt del PENDING mas viejo, para outbox_pending_age_seconds. Tambien usa el indice parcial. */
    public Optional<Instant> oldestPendingCreatedAt() {
        try {
            Document oldest = orders.find(Filters.eq(OUTBOX_STATUS, OUTBOX_PENDING))
                    .sort(Sorts.ascending(OUTBOX_CREATED_AT))
                    .projection(Projections.include(OUTBOX_CREATED_AT))
                    .first();
            return Optional.ofNullable(oldest).map(document -> document.get(OUTBOX, Document.class).getDate("createdAt").toInstant());
        } catch (MongoException e) {
            throw new OrderStorageException("could not read the oldest pending outbox event", e);
        }
    }
}
