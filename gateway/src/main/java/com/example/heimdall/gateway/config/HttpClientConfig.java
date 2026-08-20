package com.example.heimdall.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class HttpClientConfig {

    /**
     * Used for every gateway -> node call, including forwarding an upload's
     * body straight through to the owning primary. {@code setBufferRequestBody(false)}
     * keeps a large video upload from ever being held whole in the gateway's memory.
     */
    @Bean
    public RestClient nodeRestClient(GatewayProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setBufferRequestBody(false);
        factory.setConnectTimeout(properties.getNodeRequestTimeoutMs());
        factory.setReadTimeout(properties.getNodeRequestTimeoutMs());
        return RestClient.builder().requestFactory(factory).build();
    }

    /** Bounded pool for fetching multiple chunks from multiple replicas concurrently. */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService streamingExecutor(GatewayProperties properties) {
        return Executors.newFixedThreadPool(properties.getFetchPoolSize());
    }
}
