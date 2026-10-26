package com.mariposa.b2b.orderprocessor.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mariposa.b2b.orderprocessor.application.ProcessOrderUseCase;
import com.mariposa.b2b.orderprocessor.application.port.out.ProcessedEventPublisher;
import com.mariposa.b2b.orderprocessor.infrastructure.kafka.in.DeadLetterException;
import com.mariposa.b2b.orderprocessor.infrastructure.kafka.in.InboundOrder;
import com.mariposa.b2b.orderprocessor.infrastructure.kafka.in.InboundOrderDeserializer;
import com.mariposa.b2b.orderprocessor.infrastructure.kafka.in.OrderCreatedListener;
import com.mariposa.b2b.orderprocessor.infrastructure.kafka.out.DeadLetterHeaders;
import com.mariposa.b2b.orderprocessor.infrastructure.kafka.out.KafkaProcessedEventPublisher;
import com.mariposa.b2b.orderprocessor.infrastructure.kafka.out.ObservedDeadLetterRecoverer;
import com.mariposa.b2b.orderprocessor.infrastructure.kafka.out.ProcessedEventJson;
import com.mariposa.b2b.orderprocessor.infrastructure.kafka.out.ProcessedEventSender;
import com.mariposa.b2b.orderprocessor.infrastructure.kafka.out.RawValueSerializer;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.MicrometerConsumerListener;
import org.springframework.kafka.core.MicrometerProducerListener;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer.HeaderNames;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

import java.time.Clock;
import java.util.Map;

/**
 * Wiring de Kafka explicito en vez del autoconfig de Boot: ack mode, error handler,
 * serializadores y DLT son decisiones de la propuesta (§5.1, §5.5, §6.4) y tienen que
 * poder leerse en un solo lugar. De KafkaProperties (spring.kafka.*) solo se toman
 * conexion y group id.
 */
@Configuration
@EnableConfigurationProperties(MessagingProperties.class)
class KafkaConfig {

    @Bean
    ConsumerFactory<String, InboundOrder> consumerFactory(KafkaProperties kafka, MessagingProperties messaging,
                                                          ObjectMapper mapper, MeterRegistry registry) {
        Map<String, Object> props = kafka.buildConsumerProperties(null);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, messaging.maxPollRecords());
        // Un mensaje ilegible no lanza en el poll (bloquearia la particion): llega al error handler.
        DefaultKafkaConsumerFactory<String, InboundOrder> factory = new DefaultKafkaConsumerFactory<>(props,
                new StringDeserializer(), new ErrorHandlingDeserializer<>(new InboundOrderDeserializer(mapper)));
        // Metricas del cliente Kafka, entre ellas el lag del consumidor del anexo
        // (kafka_consumer_fetch_manager_records_lag_max). Con fabrica propia, Boot no lo agrega solo.
        factory.addListener(new MicrometerConsumerListener<>(registry));
        return factory;
    }

    @Bean
    ProducerFactory<String, Object> producerFactory(KafkaProperties kafka, MeterRegistry registry) {
        Map<String, Object> props = kafka.buildProducerProperties(null);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        // Acota cuanto bloquea una publicacion con el broker caido (delivery >= request + linger).
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5_000);
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10_000);
        DefaultKafkaProducerFactory<String, Object> factory =
                new DefaultKafkaProducerFactory<>(props, new StringSerializer(), new RawValueSerializer());
        factory.addListener(new MicrometerProducerListener<>(registry));
        return factory;
    }

    @Bean
    KafkaTemplate<String, Object> kafkaTemplate(ProducerFactory<String, Object> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    @Bean
    DeadLetterHeaders deadLetterHeaders(Clock clock) {
        return new DeadLetterHeaders(clock);
    }

    /**
     * Unico camino a la DLT. No se reintentan: deserializacion (default de Spring) y
     * DeadLetterException (el caso de uso ya decidio). Todo lo demas, en la practica
     * PERSISTENCE, se reintenta con backoff y despues va a la DLT. Si el envio a la DLT
     * falla, el registro no se confirma y se vuelve a procesar.
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, Object> template, MessagingProperties messaging,
                                          DeadLetterHeaders headers, MeterRegistry registry) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
                // Misma particion que el original: el DLT tiene las mismas particiones (nota I1).
                (record, exception) -> new TopicPartition(messaging.deadLetterTopic(), record.partition()));
        // Solo los headers x-* de §5.5: los de Spring incluyen el stack trace y el mensaje crudo.
        recoverer.excludeHeader(HeaderNames.HeadersToAdd.values());
        recoverer.setHeadersFunction(headers::build);

        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(messaging.persistenceMaxAttempts() - 1);
        backOff.setInitialInterval(messaging.persistenceInitialBackoff().toMillis());
        backOff.setMultiplier(messaging.persistenceBackoffMultiplier());
        backOff.setMaxInterval(messaging.persistenceMaxBackoff().toMillis());

        DefaultErrorHandler handler = new DefaultErrorHandler(
                new ObservedDeadLetterRecoverer(recoverer, headers, registry), backOff);
        handler.addNotRetryableExceptions(DeadLetterException.class);
        return handler;
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, InboundOrder> kafkaListenerContainerFactory(
            ConsumerFactory<String, InboundOrder> consumerFactory, DefaultErrorHandler kafkaErrorHandler,
            MessagingProperties messaging) {
        ConcurrentKafkaListenerContainerFactory<String, InboundOrder> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(kafkaErrorHandler);
        factory.setConcurrency(messaging.concurrency());
        ContainerProperties container = factory.getContainerProperties();
        container.setAckMode(ContainerProperties.AckMode.RECORD);
        // x-attempts de la DLT sale de este header (KafkaHeaders.DELIVERY_ATTEMPT).
        container.setDeliveryAttemptHeader(true);
        // Equivalente a spring.threads.virtual.enabled para esta fabrica propia.
        SimpleAsyncTaskExecutor listenerThreads = new SimpleAsyncTaskExecutor("order-listener-");
        listenerThreads.setVirtualThreads(true);
        container.setListenerTaskExecutor(listenerThreads);
        return factory;
    }

    @Bean
    ProcessedEventJson processedEventJson(ObjectMapper mapper) {
        return new ProcessedEventJson(mapper);
    }

    @Bean
    ProcessedEventSender processedEventSender(KafkaTemplate<String, Object> template, MessagingProperties messaging) {
        return new ProcessedEventSender(template, messaging.processedTopic(), messaging.publishTimeout());
    }

    @Bean
    ProcessedEventPublisher processedEventPublisher(ProcessedEventSender sender, ProcessedEventJson json) {
        return new KafkaProcessedEventPublisher(sender, json);
    }

    @Bean
    OrderCreatedListener orderCreatedListener(ProcessOrderUseCase useCase, MessagingProperties messaging,
                                              MeterRegistry registry) {
        return new OrderCreatedListener(useCase, messaging.inputTopic(), registry);
    }
}
