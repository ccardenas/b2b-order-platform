package com.mariposa.b2b.orderprocessor.infrastructure.config;

import com.mariposa.b2b.orderprocessor.application.ProcessOrderUseCase;
import com.mariposa.b2b.orderprocessor.application.port.out.ClientCatalog;
import com.mariposa.b2b.orderprocessor.application.port.out.IdGenerator;
import com.mariposa.b2b.orderprocessor.application.port.out.OrderMetrics;
import com.mariposa.b2b.orderprocessor.application.port.out.OrderResultStore;
import com.mariposa.b2b.orderprocessor.application.port.out.ProcessedEventPublisher;
import com.mariposa.b2b.orderprocessor.application.port.out.ProductCatalog;
import com.mariposa.b2b.orderprocessor.infrastructure.metrics.MicrometerOrderMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
class ApplicationConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** eventId del outbox. UUID v4: unico sin coordinacion, que es todo lo que pide §7.1. */
    @Bean
    IdGenerator idGenerator() {
        return () -> UUID.randomUUID().toString();
    }

    @Bean
    OrderMetrics orderMetrics(MeterRegistry registry) {
        return new MicrometerOrderMetrics(registry);
    }

    /** Un virtual thread por llamada HTTP de enriquecimiento (§6.1). */
    @Bean(destroyMethod = "close")
    ExecutorService enrichmentExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * enrichmentTimeout es la red de seguridad del presupuesto por mensaje (< 15 s, §5.3):
     * el peor caso de las llamadas HTTP medido es ~7 s (nota N12).
     */
    @Bean
    ProcessOrderUseCase processOrderUseCase(ClientCatalog clients, ProductCatalog products, OrderResultStore store,
                                            ProcessedEventPublisher publisher, IdGenerator ids, OrderMetrics metrics,
                                            Clock clock,
                                            ExecutorService enrichmentExecutor,
                                            @Value("${order-processor.enrichment-timeout:12s}") Duration enrichmentTimeout) {
        return new ProcessOrderUseCase(clients, products, store, publisher, ids, metrics, clock, enrichmentExecutor,
                enrichmentTimeout);
    }
}
