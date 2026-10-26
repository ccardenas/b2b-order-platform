package com.mariposa.b2b.orderprocessor.infrastructure.mongo;

import com.mariposa.b2b.orderprocessor.application.port.out.OrderResultStore;
import com.mariposa.b2b.orderprocessor.application.port.out.OrderStorageException;
import com.mariposa.b2b.orderprocessor.application.port.out.ProcessedOrder;
import com.mariposa.b2b.orderprocessor.domain.idempotency.RevisionClassifier;
import com.mariposa.b2b.orderprocessor.domain.idempotency.RevisionComparison;
import com.mariposa.b2b.orderprocessor.domain.model.OrderStatus;
import com.mariposa.b2b.orderprocessor.domain.model.StoredRevision;
import com.mongodb.ErrorCategory;
import com.mongodb.MongoException;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Optional;

import static com.mariposa.b2b.orderprocessor.infrastructure.mongo.OrderDocumentMapper.EVENT_ID;
import static com.mariposa.b2b.orderprocessor.infrastructure.mongo.OrderDocumentMapper.EVENT_VERSION;
import static com.mariposa.b2b.orderprocessor.infrastructure.mongo.OrderDocumentMapper.FIRST_RECEIVED_AT;
import static com.mariposa.b2b.orderprocessor.infrastructure.mongo.OrderDocumentMapper.ID;
import static com.mariposa.b2b.orderprocessor.infrastructure.mongo.OrderDocumentMapper.OUTBOX;
import static com.mariposa.b2b.orderprocessor.infrastructure.mongo.OrderDocumentMapper.OUTBOX_EVENT_ID;
import static com.mariposa.b2b.orderprocessor.infrastructure.mongo.OrderDocumentMapper.OUTBOX_PENDING;
import static com.mariposa.b2b.orderprocessor.infrastructure.mongo.OrderDocumentMapper.OUTBOX_PUBLISHED;
import static com.mariposa.b2b.orderprocessor.infrastructure.mongo.OrderDocumentMapper.OUTBOX_PUBLISHED_AT;
import static com.mariposa.b2b.orderprocessor.infrastructure.mongo.OrderDocumentMapper.OUTBOX_PUBLISH_ATTEMPTS;
import static com.mariposa.b2b.orderprocessor.infrastructure.mongo.OrderDocumentMapper.OUTBOX_STATUS;
import static com.mariposa.b2b.orderprocessor.infrastructure.mongo.OrderDocumentMapper.STATUS;

/**
 * Escritura condicional de la propuesta §6.2: un unico updateOne con upsert cuyo
 * filtro solo coincide si el evento puede reemplazar lo guardado. Si el documento
 * existe y no coincide, el upsert intenta insertar con el mismo _id y Mongo responde
 * E11000: ese es el arbitro atomico entre escrituras concurrentes. Nunca se hace
 * exists + save.
 */
public final class MongoOrderResultStore implements OrderResultStore {

    private static final Logger log = LoggerFactory.getLogger(MongoOrderResultStore.class);

    /**
     * Tras un E11000 la relectura puede decir que el evento SI era escribible: el
     * documento cambio entre la escritura y la lectura (p. ej. otra instancia inserto
     * un TECHNICAL_FAILURE del mismo evento). Se reintenta la escritura; el tope solo
     * evita un bucle si algo inesperado lo provoca de forma persistente.
     */
    private static final int MAX_WRITE_ATTEMPTS = 3;

    private final MongoCollection<Document> orders;
    private final RevisionClassifier revisions = new RevisionClassifier();

    public MongoOrderResultStore(MongoCollection<Document> orders) {
        this.orders = orders;
    }

    @Override
    public Optional<StoredRevision> findRevision(String orderId) {
        try {
            Document stored = orders.find(Filters.eq(ID, orderId))
                    .projection(Projections.include(EVENT_VERSION, EVENT_ID, STATUS))
                    .first();
            return Optional.ofNullable(stored).map(MongoOrderResultStore::revision);
        } catch (MongoException e) {
            throw new OrderStorageException("could not read order revision", e);
        }
    }

    @Override
    public WriteResult save(ProcessedOrder processed) {
        String orderId = processed.order().orderId();
        int version = processed.order().eventVersion();
        String eventId = processed.order().eventId();

        for (int attempt = 1; attempt <= MAX_WRITE_ATTEMPTS; attempt++) {
            try {
                orders.updateOne(writableFilter(orderId, version, eventId), update(processed), new UpdateOptions().upsert(true));
                return WriteResult.APPLIED;
            } catch (MongoWriteException e) {
                if (e.getError().getCategory() != ErrorCategory.DUPLICATE_KEY) {
                    throw new OrderStorageException("conditional write failed", e);
                }
                // E11000 = "no aplicado". Solo se relee para clasificar, no para decidir otra cosa.
                RevisionComparison comparison = revisions.classify(findRevision(orderId), version, eventId);
                switch (comparison) {
                    case DUPLICATE:
                        return WriteResult.DUPLICATE;
                    case STALE:
                        return WriteResult.STALE;
                    case VERSION_CONFLICT:
                        return WriteResult.VERSION_CONFLICT;
                    case NEWER, RETRY_OF_TECHNICAL_FAILURE:
                        log.info("conditional write lost a race but is still writable, retrying attempt={}", attempt);
                }
            } catch (MongoException e) {
                throw new OrderStorageException("conditional write failed", e);
            }
        }
        throw new OrderStorageException("conditional write did not settle after " + MAX_WRITE_ATTEMPTS + " attempts", null);
    }

    @Override
    public void markPublished(String orderId, String outboxEventId, Instant publishedAt) {
        try {
            // Condicionado al eventId y a PENDING (§7.2): si una revision nueva ya reemplazo
            // el outbox, este update no toca el evento nuevo.
            orders.updateOne(
                    Filters.and(Filters.eq(ID, orderId), Filters.eq(OUTBOX_EVENT_ID, outboxEventId),
                            Filters.eq(OUTBOX_STATUS, OUTBOX_PENDING)),
                    Updates.combine(
                            Updates.set(OUTBOX_STATUS, OUTBOX_PUBLISHED),
                            Updates.set(OUTBOX_PUBLISHED_AT, OrderDocumentMapper.date(publishedAt)),
                            Updates.inc(OUTBOX_PUBLISH_ATTEMPTS, 1)));
        } catch (MongoException e) {
            throw new OrderStorageException("could not mark outbox as published", e);
        }
    }

    /** El filtro $or de §6.2: version guardada menor, o reintento del mismo evento tras un fallo tecnico. */
    private static Bson writableFilter(String orderId, int version, String eventId) {
        return Filters.and(
                Filters.eq(ID, orderId),
                Filters.or(
                        Filters.lt(EVENT_VERSION, version),
                        Filters.and(
                                Filters.eq(EVENT_VERSION, version),
                                Filters.eq(EVENT_ID, eventId),
                                Filters.eq(STATUS, OrderStatus.TECHNICAL_FAILURE.name()))));
    }

    private static Document update(ProcessedOrder processed) {
        Document update = new Document("$set", OrderDocumentMapper.fieldsToSet(processed))
                .append("$setOnInsert", new Document(FIRST_RECEIVED_AT, OrderDocumentMapper.date(processed.receivedAt())));
        if (!OrderDocumentMapper.hasOutbox(processed)) {
            // Un TECHNICAL_FAILURE no se publica: no debe quedar el outbox de una revision anterior.
            update.append("$unset", new Document(OUTBOX, ""));
        }
        return update;
    }

    private static StoredRevision revision(Document stored) {
        return new StoredRevision(
                stored.getInteger(EVENT_VERSION),
                stored.getString(EVENT_ID),
                OrderStatus.valueOf(stored.getString(STATUS)));
    }
}
