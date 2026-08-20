package com.example.heimdall.node.config;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MinioConfig {

    private static final Logger log = LoggerFactory.getLogger(MinioConfig.class);

    @Bean
    public MinioClient minioClient(MinioProperties properties) {
        return MinioClient.builder()
                .endpoint(properties.getEndpoint())
                .credentials(properties.getAccessKey(), properties.getSecretKey())
                .build();
    }

    /**
     * Ensures this node's bucket exists before it serves any traffic. Each
     * node owns one bucket, named after its node id, inside the shared MinIO
     * deployment - which keeps every node's data genuinely partitioned from
     * every other node's, the same way separate disks would on separate hosts.
     */
    @Configuration
    public static class BucketInitializer {

        private final MinioClient minioClient;
        private final NodeProperties nodeProperties;

        public BucketInitializer(MinioClient minioClient, NodeProperties nodeProperties) {
            this.minioClient = minioClient;
            this.nodeProperties = nodeProperties;
        }

        @PostConstruct
        public void ensureBucketExists() throws Exception {
            String bucket = nodeProperties.getId();
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("[{}] created MinIO bucket '{}'", nodeProperties.getId(), bucket);
            } else {
                log.info("[{}] using existing MinIO bucket '{}'", nodeProperties.getId(), bucket);
            }
        }
    }
}
