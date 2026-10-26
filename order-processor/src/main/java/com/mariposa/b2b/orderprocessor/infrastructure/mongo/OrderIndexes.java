package com.mariposa.b2b.orderprocessor.infrastructure.mongo;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import org.bson.Document;

/**
 * Indices de la propuesta §7.4. createIndex es idempotente con la misma
 * especificacion, asi que se ejecuta en cada arranque. Si alguien cambia las
 * opciones de un indice con el mismo nombre, Mongo falla al arrancar en vez de
 * quedar con un indice distinto del versionado aqui. _id no se declara: es unico
 * por definicion y es el arbitro de concurrencia.
 */
public final class OrderIndexes {

    static final String BY_EVENT_ID = "eventId_1";
    static final String OUTBOX_PENDING = "outbox_pending_createdAt";
    static final String BY_STATUS_RECENT = "status_1_processedAt_-1";

    private OrderIndexes() {
    }

    public static void ensure(MongoCollection<Document> orders) {
        orders.createIndex(Indexes.ascending(OrderDocumentMapper.EVENT_ID), new IndexOptions().name(BY_EVENT_ID));
        orders.createIndex(
                Indexes.ascending(OrderDocumentMapper.OUTBOX_STATUS, OrderDocumentMapper.OUTBOX_CREATED_AT),
                new IndexOptions().name(OUTBOX_PENDING)
                        .partialFilterExpression(Filters.eq(OrderDocumentMapper.OUTBOX_STATUS, OrderDocumentMapper.OUTBOX_PENDING)));
        orders.createIndex(
                Indexes.compoundIndex(Indexes.ascending(OrderDocumentMapper.STATUS), Indexes.descending(OrderDocumentMapper.PROCESSED_AT)),
                new IndexOptions().name(BY_STATUS_RECENT));
    }
}
