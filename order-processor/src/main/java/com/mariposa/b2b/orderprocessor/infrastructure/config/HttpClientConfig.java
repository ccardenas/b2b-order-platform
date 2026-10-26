package com.mariposa.b2b.orderprocessor.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mariposa.b2b.orderprocessor.application.port.out.ClientCatalog;
import com.mariposa.b2b.orderprocessor.application.port.out.ProductCatalog;
import com.mariposa.b2b.orderprocessor.infrastructure.http.ClientsApiClient;
import com.mariposa.b2b.orderprocessor.infrastructure.http.ProductsApiClient;
import com.mariposa.b2b.orderprocessor.infrastructure.http.ResilientHttpClient;
import com.mariposa.b2b.orderprocessor.infrastructure.http.RetryPolicy;
import com.mariposa.b2b.orderprocessor.infrastructure.http.Sleeper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.util.concurrent.ThreadLocalRandom;

@Configuration
@EnableConfigurationProperties(HttpClientProperties.class)
class HttpClientConfig {

    @Bean
    ResilientHttpClient resilientHttpClient(HttpClientProperties props) {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(props.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        RetryPolicy policy = new RetryPolicy(
                props.maxAttempts(), props.initialBackoff(), props.backoffMultiplier(), props.retryAfterCap());
        return new ResilientHttpClient(http, props.readTimeout(), policy, Sleeper.SYSTEM,
                () -> ThreadLocalRandom.current().nextDouble());
    }

    @Bean
    ClientCatalog clientCatalog(ResilientHttpClient http, ObjectMapper mapper, HttpClientProperties props) {
        return new ClientsApiClient(http, mapper, props.clientsBaseUrl());
    }

    @Bean
    ProductCatalog productCatalog(ResilientHttpClient http, ObjectMapper mapper, HttpClientProperties props) {
        return new ProductsApiClient(http, mapper, props.productsBaseUrl());
    }
}
