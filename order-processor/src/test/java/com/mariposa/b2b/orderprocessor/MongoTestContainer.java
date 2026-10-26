package com.mariposa.b2b.orderprocessor;

import org.testcontainers.containers.MongoDBContainer;

/**
 * Un solo contenedor por JVM para todos los *IT: arrancarlo es lo caro. Misma
 * imagen que docker-compose.yml. Testcontainers (Ryuk) lo elimina al terminar.
 */
public final class MongoTestContainer {

    public static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8.0.34");

    static {
        MONGO.start();
    }

    private MongoTestContainer() {
    }
}
