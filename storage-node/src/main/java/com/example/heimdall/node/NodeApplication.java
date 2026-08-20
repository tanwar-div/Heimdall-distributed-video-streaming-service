package com.example.heimdall.node;

import com.example.heimdall.node.config.NodeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@ConfigurationPropertiesScan
public class NodeApplication {

    public static void main(String[] args) {
        SpringApplication.run(NodeApplication.class, args);
    }

    @Bean
    public CommandLineRunner startupBanner(NodeProperties properties) {
        Logger log = LoggerFactory.getLogger(NodeApplication.class);
        return args -> {
            if (properties.isPrimary()) {
                log.info("[{}] started as PRIMARY, chunkSize={}B, replicas={}",
                        properties.getId(), properties.getChunkSizeBytes(), properties.getReplicas().stream()
                                .map(NodeProperties.ReplicaTarget::getId).toList());
            } else {
                log.info("[{}] started as REPLICA, chunkSize={}B", properties.getId(), properties.getChunkSizeBytes());
            }
        };
    }
}
