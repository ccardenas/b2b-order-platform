package com.mariposa.b2b.orderprocessor.flow;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.mariposa.b2b.orderprocessor.KafkaTestContainer;
import com.mariposa.b2b.orderprocessor.MongoTestContainer;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/**
 * Aplicacion completa contra Kafka y MongoDB reales (Testcontainers) y las dos APIs
 * simuladas con WireMock. Cada clase cierra su contexto al terminar: dos contextos
 * vivos con el mismo group id se repartirian las particiones y se robarian mensajes.
 * Cada test usa orderIds propios, asi los topicos compartidos no interfieren.
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
abstract class FlowTestSupport {

    static final String DATABASE = "flow_it";
    static final WireMockServer apis = new WireMockServer(options().dynamicPort());

    static {
        apis.start();
    }

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KafkaTestContainer.KAFKA::getBootstrapServers);
        registry.add("spring.data.mongodb.uri", () -> MongoTestContainer.MONGO.getReplicaSetUrl(DATABASE));
        registry.add("order-processor.http.clients-base-url", apis::baseUrl);
        registry.add("order-processor.http.products-base-url", apis::baseUrl);
        // Politicas reales, tiempos cortos: el comportamiento es el mismo y la suite no tarda minutos.
        registry.add("order-processor.http.read-timeout", () -> "500ms");
        registry.add("order-processor.http.initial-backoff", () -> "10ms");
        registry.add("order-processor.kafka.persistence-initial-backoff", () -> "50ms");
        registry.add("order-processor.kafka.persistence-max-backoff", () -> "200ms");
        registry.add("order-processor.outbox.relay-interval", () -> "300ms");
        registry.add("order-processor.outbox.min-age", () -> "1s");
    }

    @Autowired
    MongoClient mongoClient;

    @BeforeEach
    void resetApis() {
        apis.resetAll();
    }

    // ---------------------------------------------------------------- datos

    static String newOrderId() {
        return "ORD-" + UUID.randomUUID();
    }

    static String event(String eventId, int version, String orderId, String clientId, String... items) {
        return """
                {"eventId":"%s","eventVersion":%d,"occurredAt":"2026-10-26T12:00:00Z","orderId":"%s",\
                "market":"MX","currency":"MXN","clientId":"%s","channel":"C1","items":[%s]}"""
                .formatted(eventId, version, orderId, clientId, String.join(",", items));
    }

    static String item(String productId, int quantity, String unitPrice) {
        return "{\"productId\":\"%s\",\"quantity\":%d,\"unitPrice\":%s}".formatted(productId, quantity, unitPrice);
    }

    static void stubClient(String clientId, String status, String segment) {
        apis.stubFor(get(urlEqualTo("/clients/" + clientId)).willReturn(aResponse().withStatus(200).withBody("""
                {"clientId":"%s","name":"Cliente","status":"%s","segment":"%s","taxRegime":"GENERAL","market":"MX"}"""
                .formatted(clientId, status, segment))));
    }

    static void stubClientNotFound(String clientId) {
        apis.stubFor(get(urlEqualTo("/clients/" + clientId)).willReturn(aResponse().withStatus(404)
                .withBody("{\"error\":{\"code\":\"CLIENT_NOT_FOUND\",\"message\":\"x\",\"traceId\":\"t\"}}")));
    }

    static void stubProduct(String productId, String status, String taxCategory) {
        apis.stubFor(get(urlEqualTo("/products/" + productId + "?market=MX")).willReturn(aResponse().withStatus(200).withBody("""
                {"productId":"%s","name":"Producto","sku":"SKU-%s","status":"%s","taxCategory":"%s"}"""
                .formatted(productId, productId, status, taxCategory))));
    }

    static void stubProductStatus(String productId, int httpStatus) {
        apis.stubFor(get(urlEqualTo("/products/" + productId + "?market=MX")).willReturn(aResponse().withStatus(httpStatus)
                .withBody("{\"error\":{\"code\":\"SERVICE_UNAVAILABLE\",\"message\":\"x\",\"traceId\":\"t\"}}")));
    }

    // ---------------------------------------------------------------- Kafka

    static void publish(String key, String value) {
        publish(key, value.getBytes(StandardCharsets.UTF_8));
    }

    static void publish(String key, byte[] value) {
        Map<String, Object> props = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainer.KAFKA.getBootstrapServers(),
                ProducerConfig.ACKS_CONFIG, "all");
        try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(props, new StringSerializer(), new ByteArraySerializer())) {
            producer.send(new ProducerRecord<>(KafkaTestContainer.INPUT, key, value)).get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Todo lo que hay en el topico con esa clave, desde el principio. */
    static List<ConsumerRecord<String, byte[]>> records(String topic, String key) {
        Map<String, Object> props = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainer.KAFKA.getBootstrapServers(),
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(props, new StringDeserializer(), new ByteArrayDeserializer())) {
            List<TopicPartition> partitions = consumer.partitionsFor(topic).stream()
                    .map(PartitionInfo::partition).map(partition -> new TopicPartition(topic, partition)).toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            Map<TopicPartition, Long> end = consumer.endOffsets(partitions);
            List<ConsumerRecord<String, byte[]>> found = new ArrayList<>();
            while (partitions.stream().anyMatch(partition -> consumer.position(partition) < end.get(partition))) {
                consumer.poll(Duration.ofMillis(200)).forEach(record -> {
                    if (key.equals(record.key())) {
                        found.add(record);
                    }
                });
            }
            return found;
        }
    }

    static String header(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- Mongo

    MongoCollection<Document> orders() {
        return mongoClient.getDatabase(DATABASE).getCollection("orders");
    }

    Document order(String orderId) {
        return orders().find(Filters.eq("_id", orderId)).first();
    }
}
