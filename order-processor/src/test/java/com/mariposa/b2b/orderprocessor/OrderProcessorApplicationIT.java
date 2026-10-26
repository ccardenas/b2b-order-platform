package com.mariposa.b2b.orderprocessor;

import com.mariposa.b2b.orderprocessor.application.port.out.OrderResultStore;
import com.mongodb.client.MongoClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class OrderProcessorApplicationIT {

    @DynamicPropertySource
    static void mongo(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", () -> MongoTestContainer.MONGO.getReplicaSetUrl("context_it"));
    }

    @Autowired
    OrderResultStore store;

    @Autowired
    MongoClient client;

    @Test
    void wiresTheMongoStoreAndCreatesTheIndexesOnStartup() {
        assertThat(store).isNotNull();
        List<String> indexes = client.getDatabase("context_it").getCollection("orders").listIndexes()
                .map(index -> index.getString("name")).into(new ArrayList<>());

        assertThat(indexes).contains("_id_", "eventId_1", "outbox_pending_createdAt", "status_1_processedAt_-1");
    }
}
