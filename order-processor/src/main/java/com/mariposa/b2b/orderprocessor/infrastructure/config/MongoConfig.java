package com.mariposa.b2b.orderprocessor.infrastructure.config;

import com.mariposa.b2b.orderprocessor.application.port.out.OrderResultStore;
import com.mariposa.b2b.orderprocessor.infrastructure.mongo.MongoOrderResultStore;
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
     * Los indices se crean al construir el store: nada que dependa de el (el listener,
     * en el hito 7) arranca antes. Con Mongo caido, la aplicacion no arranca.
     * WriteConcern.MAJORITY explicito: "durable" es la condicion para confirmar el
     * offset (§6.4), y no se quiere depender del default del servidor.
     */
    @Bean
    OrderResultStore orderResultStore(MongoClient client, MongoProperties properties) {
        MongoCollection<Document> orders = client.getDatabase(properties.getMongoClientDatabase())
                .getCollection(ORDERS_COLLECTION)
                .withWriteConcern(WriteConcern.MAJORITY);
        OrderIndexes.ensure(orders);
        return new MongoOrderResultStore(orders);
    }
}
