package com.mariposa.b2b.orderprocessor.infrastructure.config;

import com.mariposa.b2b.orderprocessor.application.port.out.OrderResultStore;
import com.mariposa.b2b.orderprocessor.infrastructure.mongo.MongoOrderResultStore;
import com.mariposa.b2b.orderprocessor.infrastructure.mongo.MongoPendingOutbox;
import com.mariposa.b2b.orderprocessor.infrastructure.mongo.OrderIndexes;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import org.bson.Document;
import org.springframework.boot.autoconfigure.mongo.MongoProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class MongoConfig {

    static final String ORDERS_COLLECTION = "orders";

    /**
     * Los indices se crean al construir la coleccion: nada que la use (store, relay,
     * listener) arranca antes. Con Mongo caido, la aplicacion no arranca.
     * WriteConcern.MAJORITY explicito: "durable" es la condicion para confirmar el
     * offset (§6.4), y no se quiere depender del default del servidor.
     */
    @Bean
    MongoCollection<Document> ordersCollection(MongoClient client, MongoProperties properties) {
        MongoCollection<Document> orders = client.getDatabase(properties.getMongoClientDatabase())
                .getCollection(ORDERS_COLLECTION)
                .withWriteConcern(WriteConcern.MAJORITY);
        OrderIndexes.ensure(orders);
        return orders;
    }

    @Bean
    OrderResultStore orderResultStore(MongoCollection<Document> ordersCollection) {
        return new MongoOrderResultStore(ordersCollection);
    }

    @Bean
    MongoPendingOutbox pendingOutbox(MongoCollection<Document> ordersCollection) {
        return new MongoPendingOutbox(ordersCollection);
    }
}
