package com.mariposa.b2b.orderprocessor;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.testcontainers.kafka.KafkaContainer;

import java.util.List;
import java.util.Map;

/**
 * Un broker por JVM, misma imagen que docker-compose.yml. Los topicos se crean
 * explicitamente con 3 particiones, igual que kafka-init (nota I1): el DLT debe tener
 * al menos las particiones del origen.
 */
public final class KafkaTestContainer {

    public static final String INPUT = "orders.created.v1";
    public static final String PROCESSED = "orders.processed.v1";
    public static final String DLT = "orders.processing.dlt";

    public static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.2");

    static {
        KAFKA.start();
        try (AdminClient admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(INPUT, PROCESSED, DLT).stream()
                    .map(name -> new NewTopic(name, 3, (short) 1)).toList()).all().get();
        } catch (Exception e) {
            throw new IllegalStateException("could not create test topics", e);
        }
    }

    private KafkaTestContainer() {
    }
}
