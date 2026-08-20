package com.example.heimdall.node.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class HttpClientConfig {

    /**
     * Streaming-capable REST client used for primary -> replica replication
     * pushes. {@code setBufferRequestBody(false)} is the important bit: it
     * makes the underlying HttpURLConnection stream the request body straight
     * through instead of buffering the whole object in memory first.
     */
    @Bean
    public RestClient nodeRestClient(NodeProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setBufferRequestBody(false);
        factory.setConnectTimeout(properties.getReplicationTimeoutMs());
        factory.setReadTimeout(properties.getReplicationTimeoutMs());
        return RestClient.builder().requestFactory(factory).build();
    }

    @Bean(destroyMethod = "shutdown")
    public ExecutorService replicationExecutor() {
        return Executors.newFixedThreadPool(4);
    }
}
