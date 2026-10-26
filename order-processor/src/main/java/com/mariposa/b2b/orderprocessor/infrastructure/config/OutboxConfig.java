package com.mariposa.b2b.orderprocessor.infrastructure.config;

import com.mariposa.b2b.orderprocessor.application.port.out.OrderResultStore;
import com.mariposa.b2b.orderprocessor.infrastructure.kafka.out.ProcessedEventJson;
import com.mariposa.b2b.orderprocessor.infrastructure.kafka.out.ProcessedEventSender;
import com.mariposa.b2b.orderprocessor.infrastructure.mongo.MongoPendingOutbox;
import com.mariposa.b2b.orderprocessor.infrastructure.outbox.OutboxRelay;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import java.time.Clock;

/** Programa el relay con fixed delay: una pasada no se solapa con la anterior. */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(OutboxRelayProperties.class)
class OutboxConfig implements SchedulingConfigurer {

    private final OutboxRelay relay;
    private final OutboxRelayProperties properties;

    OutboxConfig(OutboxRelay relay, OutboxRelayProperties properties) {
        this.relay = relay;
        this.properties = properties;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(new FixedDelayTask(relay::relayPending, properties.relayInterval(), properties.relayInterval()));
    }

    @Configuration
    static class RelayBean {

        @Bean
        OutboxRelay outboxRelay(MongoPendingOutbox pending, OrderResultStore store, ProcessedEventSender sender,
                                ProcessedEventJson json, Clock clock, OutboxRelayProperties properties) {
            return new OutboxRelay(pending, store, sender, json, clock, properties.minAge(), properties.batchSize());
        }
    }
}
