package com.example.heimdall.bench.e2e;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.SetBucketPolicyArgs;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Writes the benchmark object into each system under test through that
 * system's own native write path, then hands back a {@link Target} describing
 * how to read it back over plain HTTP.
 *
 * <p>The S3-backed targets get an anonymous read policy applied to their
 * benchmark bucket. That is what makes the comparison fair rather than
 * convenient: without it, reading MinIO or SeaweedFS would require SigV4
 * request signing, and the benchmark would be partly measuring the signing
 * code path on the client. With it, all four targets answer an identical
 * unauthenticated {@code GET} with a {@code Range} header.
 */
public final class TargetProvisioner {

    /** Grants anonymous read on one bucket - benchmark data only, never a real deployment's policy. */
    private static String publicReadPolicy(String bucket) {
        return """
                {
                  "Version": "2012-10-17",
                  "Statement": [{
                    "Effect": "Allow",
                    "Principal": {"AWS": ["*"]},
                    "Action": ["s3:GetObject"],
                    "Resource": ["arn:aws:s3:::%s/*"]
                  }]
                }""".formatted(bucket);
    }

    private final HttpClient httpClient;
    private final Duration timeout;

    public TargetProvisioner(HttpClient httpClient, Duration timeout) {
        this.httpClient = httpClient;
        this.timeout = timeout;
    }

    /** Uploads through the Heimdall gateway, exercising its ring routing, chunking and replication. */
    public Target heimdall(String baseUrl, String apiKey, String objectId, byte[] payload) {
        try {
            HttpResponse<String> response = httpClient.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/objects/" + objectId))
                            .timeout(Duration.ofMinutes(10))
                            .header("X-API-Key", apiKey)
                            .header("Content-Type", "video/mp4")
                            .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            boolean ok = response.statusCode() == 201 || response.statusCode() == 200;
            if (!ok) {
                System.err.println("[provision] heimdall upload failed: HTTP " + response.statusCode()
                        + " " + response.body());
            }
            return new Target("heimdall", "Heimdall gateway (chunked, replica fan-out, failover)",
                    URI.create(baseUrl + "/objects"), ok);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            System.err.println("[provision] heimdall unreachable: " + e);
            return new Target("heimdall", "Heimdall gateway", URI.create(baseUrl + "/objects"), false);
        }
    }

    /**
     * Uploads straight into MinIO - the same MinIO the Heimdall nodes store
     * their chunks in. This is the load-bearing comparison of the whole
     * exercise: same hardware, same storage engine, same bytes, with and
     * without everything Heimdall adds.
     */
    public Target minioDirect(String endpoint, String accessKey, String secretKey,
                              String bucket, String objectId, byte[] payload) {
        try {
            MinioClient client = MinioClient.builder()
                    .endpoint(endpoint)
                    .credentials(accessKey, secretKey)
                    .build();

            if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            }
            client.setBucketPolicy(SetBucketPolicyArgs.builder()
                    .bucket(bucket)
                    .config(publicReadPolicy(bucket))
                    .build());
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectId)
                    .stream(new ByteArrayInputStream(payload), (long) payload.length, -1L)
                    .contentType("video/mp4")
                    .build());

            return new Target("minio-direct", "MinIO S3 API, read directly (Heimdall's own backing store)",
                    URI.create(endpoint + "/" + bucket), true);
        } catch (Exception e) {
            System.err.println("[provision] minio-direct unavailable: " + e);
            return new Target("minio-direct", "MinIO S3 API, read directly",
                    URI.create(endpoint + "/" + bucket), false);
        }
    }

    /** Drops the object into the directory nginx serves; nothing to configure beyond the file existing. */
    public Target nginx(String baseUrl, Path dataDir, String objectId, byte[] payload) {
        try {
            Files.createDirectories(dataDir);
            Files.write(dataDir.resolve(objectId), payload);
            return new Target("nginx", "nginx serving the same bytes as a static file (the plain-HTTP ceiling)",
                    URI.create(baseUrl), true);
        } catch (IOException e) {
            System.err.println("[provision] nginx data dir not writable: " + e);
            return new Target("nginx", "nginx static file", URI.create(baseUrl), false);
        }
    }

    /** SeaweedFS through its S3 gateway; it accepts anonymous access when no identity config is supplied. */
    public Target seaweedfs(String endpoint, String bucket, String objectId, byte[] payload) {
        try {
            MinioClient client = MinioClient.builder()
                    .endpoint(endpoint)
                    .credentials("any", "any") // ignored: this SeaweedFS runs without an identity config
                    .build();

            if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            }
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectId)
                    .stream(new ByteArrayInputStream(payload), (long) payload.length, -1L)
                    .contentType("video/mp4")
                    .build());

            return new Target("seaweedfs", "SeaweedFS via its S3 gateway (distributed object store peer)",
                    URI.create(endpoint + "/" + bucket), true);
        } catch (Exception e) {
            System.err.println("[provision] seaweedfs unavailable: " + e);
            return new Target("seaweedfs", "SeaweedFS S3 gateway",
                    URI.create(endpoint + "/" + bucket), false);
        }
    }
}
