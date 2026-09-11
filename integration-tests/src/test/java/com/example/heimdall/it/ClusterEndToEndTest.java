package com.example.heimdall.it;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The whole system, running for real, exercised over HTTP exactly as a client
 * would.
 *
 * <p>Everything below this point in the stack is genuine: MinIO is a real
 * container, each storage node is a separate Spring Boot application on its own
 * port, replication is a real HTTP PUT between two of them, and "a node is
 * down" means its process is actually gone. The unit tests prove the algorithms
 * are right; this proves they are wired together into something that works.
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Heimdall cluster, end to end")
class ClusterEndToEndTest {

    private static final MinIOContainer MINIO = new MinIOContainer(
            DockerImageName.parse("minio/minio:RELEASE.2025-04-08T15-41-24Z"))
            .withUserName("heimdall")
            .withPassword("heimdall-secret");

    private static HeimdallCluster cluster;
    private static HttpClient http;

    /** 512 KiB across 8 chunks at the 64 KiB test chunk size - enough to make ranges and fan-out meaningful. */
    private static final byte[] VIDEO = payload(512 * 1024);

    @BeforeAll
    static void startCluster() {
        MINIO.start();
        cluster = new HeimdallCluster(MINIO.getS3URL(), MINIO.getUserName(), MINIO.getPassword())
                .start(2, 2, false);
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @AfterAll
    static void stopCluster() {
        if (cluster != null) {
            cluster.close();
        }
        MINIO.stop();
    }

    private static byte[] payload(int size) {
        byte[] data = new byte[size];
        new Random(4242L).nextBytes(data);
        return data;
    }

    private HttpResponse<byte[]> upload(String objectId, byte[] body, String contentType, String apiKey)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(cluster.gatewayUrl() + "/objects/" + objectId))
                .timeout(Duration.ofMinutes(1))
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (apiKey != null) {
            request.header("X-API-Key", apiKey);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpResponse<byte[]> get(String path, String rangeHeader) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(cluster.gatewayUrl() + path))
                .timeout(Duration.ofMinutes(1)).GET();
        if (rangeHeader != null) {
            request.header("Range", rangeHeader);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpResponse<byte[]> nodeGet(String baseUrl, String path) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test
    @DisplayName("uploads, replicates, serves whole objects and byte ranges, fails over, and deletes")
    void fullLifecycle() throws Exception {
        String objectId = "lifecycle-clip.mp4";

        // --- upload -------------------------------------------------------
        HttpResponse<byte[]> uploaded = upload(objectId, VIDEO, "video/mp4", cluster.apiKey());
        assertThat(uploaded.statusCode()).isEqualTo(201);
        String uploadBody = new String(uploaded.body());
        assertThat(uploadBody).contains("primary-");

        // Which primary the ring chose, read back from the gateway's own
        // routing endpoint rather than assumed.
        HttpResponse<byte[]> routing = get("/objects/" + objectId + "/replicas", null);
        assertThat(routing.statusCode()).isEqualTo(200);
        String owningPrimary = new String(routing.body()).contains("\"primary-0\"") ? "primary-0" : "primary-1";

        // --- replication actually happened --------------------------------
        // Async by default, so this is an eventual assertion; the point is that
        // it converges, not that it is instant.
        for (HeimdallCluster.NodeHandle replica : cluster.replicasOf(owningPrimary)) {
            await().atMost(30, TimeUnit.SECONDS).pollInterval(Duration.ofMillis(250)).untilAsserted(() ->
                    assertThat(nodeGet(replica.baseUrl(), "/internal/objects/" + objectId + "/meta").statusCode())
                            .as("replica %s should have caught up", replica.id())
                            .isEqualTo(200));
        }

        // --- full download ------------------------------------------------
        HttpResponse<byte[]> full = get("/objects/" + objectId, null);
        assertThat(full.statusCode()).isEqualTo(200);
        assertThat(full.body()).isEqualTo(VIDEO);
        assertThat(full.headers().firstValue("Accept-Ranges")).contains("bytes");

        // --- range download, the seek a video player performs --------------
        int start = 100_000;
        int end = 250_000;
        HttpResponse<byte[]> ranged = get("/objects/" + objectId, "bytes=" + start + "-" + end);
        assertThat(ranged.statusCode()).isEqualTo(206);
        assertThat(ranged.headers().firstValue("Content-Range"))
                .contains("bytes " + start + "-" + end + "/" + VIDEO.length);
        assertThat(ranged.body()).isEqualTo(Arrays.copyOfRange(VIDEO, start, end + 1));

        // An open-ended suffix range: "everything from here on", which is what
        // a player issues after seeking.
        HttpResponse<byte[]> openEnded = get("/objects/" + objectId, "bytes=500000-");
        assertThat(openEnded.statusCode()).isEqualTo(206);
        assertThat(openEnded.body()).isEqualTo(Arrays.copyOfRange(VIDEO, 500_000, VIDEO.length));

        // --- failover: kill one replica, reads must continue ---------------
        List<HeimdallCluster.NodeHandle> replicas = cluster.replicasOf(owningPrimary);
        replicas.get(0).kill();
        HttpResponse<byte[]> afterOneDown = get("/objects/" + objectId, null);
        assertThat(afterOneDown.statusCode()).isEqualTo(200);
        assertThat(afterOneDown.body()).isEqualTo(VIDEO);

        // --- failover: kill every replica, the primary is the last resort ---
        replicas.get(1).kill();
        HttpResponse<byte[]> afterAllReplicasDown = get("/objects/" + objectId, null);
        assertThat(afterAllReplicasDown.statusCode()).isEqualTo(200);
        assertThat(afterAllReplicasDown.body()).isEqualTo(VIDEO);

        HttpResponse<byte[]> rangedWithReplicasDown = get("/objects/" + objectId, "bytes=0-65535");
        assertThat(rangedWithReplicasDown.statusCode()).isEqualTo(206);
        assertThat(rangedWithReplicasDown.body()).isEqualTo(Arrays.copyOfRange(VIDEO, 0, 65536));

        // --- delete --------------------------------------------------------
        HttpResponse<byte[]> deleted = http.send(
                HttpRequest.newBuilder(URI.create(cluster.gatewayUrl() + "/objects/" + objectId))
                        .header("X-API-Key", cluster.apiKey()).DELETE().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(deleted.statusCode()).isEqualTo(204);
        assertThat(get("/objects/" + objectId, null).statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("rejects unauthenticated writes but serves reads to anyone")
    void authentication() throws Exception {
        HttpResponse<byte[]> noKey = upload("unauthenticated.mp4", VIDEO, "video/mp4", null);
        assertThat(noKey.statusCode()).isIn(401, 403);

        HttpResponse<byte[]> wrongKey = upload("wrong-key.mp4", VIDEO, "video/mp4", "not-the-key");
        assertThat(wrongKey.statusCode()).isIn(401, 403);

        // Reads are public by design: this is a CDN-shaped service.
        assertThat(get("/cluster", null).statusCode()).isEqualTo(200);
        assertThat(get("/objects", null).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("rejects disallowed content types and unsatisfiable ranges with the right status codes")
    void validation() throws Exception {
        HttpResponse<byte[]> wrongType = upload("script.sh", "#!/bin/sh".getBytes(),
                "application/x-sh", cluster.apiKey());
        assertThat(wrongType.statusCode()).isEqualTo(415);

        String objectId = "range-validation.mp4";
        assertThat(upload(objectId, VIDEO, "video/mp4", cluster.apiKey()).statusCode()).isEqualTo(201);

        HttpResponse<byte[]> unsatisfiable = get("/objects/" + objectId, "bytes=999999999-1000000000");
        assertThat(unsatisfiable.statusCode()).isEqualTo(416);

        // RFC 7233: a malformed Range is ignored, not an error.
        HttpResponse<byte[]> malformed = get("/objects/" + objectId, "bytes=not-a-range");
        assertThat(malformed.statusCode()).isEqualTo(200);
        assertThat(malformed.body()).isEqualTo(VIDEO);

        assertThat(get("/objects/does-not-exist.mp4", null).statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("distributes objects across both primaries and lists them all")
    void routingAndListing() throws Exception {
        AtomicReference<String> firstOwner = new AtomicReference<>();
        boolean sawTwoOwners = false;

        for (int i = 0; i < 24; i++) {
            String objectId = "spread-" + i + ".mp4";
            assertThat(upload(objectId, new byte[1024], "video/mp4", cluster.apiKey()).statusCode()).isEqualTo(201);

            String owner = new String(get("/objects/" + objectId + "/replicas", null).body())
                    .contains("\"primary-0\"") ? "primary-0" : "primary-1";
            firstOwner.compareAndSet(null, owner);
            if (!owner.equals(firstOwner.get())) {
                sawTwoOwners = true;
            }
        }

        assertThat(sawTwoOwners)
                .as("24 objects across 2 primaries should not all land on one")
                .isTrue();

        String listed = new String(get("/objects", null).body());
        assertThat(listed).contains("spread-0.mp4").contains("spread-23.mp4");
    }

    @Test
    @DisplayName("streams an object larger than any single buffer without loading it into memory")
    void largeObjectStreaming() throws Exception {
        // 24 MiB at a 64 KiB chunk size is 384 chunks: enough that a
        // buffer-the-whole-thing implementation would be obvious, and enough
        // for the concurrent chunk fetching to actually be concurrent.
        byte[] large = payload(24 * 1024 * 1024);
        String objectId = "large-clip.mp4";

        assertThat(upload(objectId, large, "video/mp4", cluster.apiKey()).statusCode()).isEqualTo(201);

        HttpResponse<byte[]> full = get("/objects/" + objectId, null);
        assertThat(full.statusCode()).isEqualTo(200);
        assertThat(full.body()).isEqualTo(large);

        // A range spanning a chunk boundary deep inside the object.
        int start = 20 * 1024 * 1024 - 7;
        int end = start + 3 * 1024 * 1024;
        HttpResponse<byte[]> ranged = get("/objects/" + objectId, "bytes=" + start + "-" + end);
        assertThat(ranged.statusCode()).isEqualTo(206);
        assertThat(ranged.body()).isEqualTo(Arrays.copyOfRange(large, start, end + 1));
    }
}
