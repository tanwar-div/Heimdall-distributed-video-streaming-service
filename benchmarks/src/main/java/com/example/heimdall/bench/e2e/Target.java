package com.example.heimdall.bench.e2e;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;

/**
 * One system under test, expressed as the URL its objects are readable at.
 *
 * <p>Every target is read through the same {@link java.net.http.HttpClient}
 * with the same request shape, which is the only way the comparison means
 * anything: routing all four through their respective native SDKs would
 * measure the SDKs as much as the servers. That is why the MinIO and SeaweedFS
 * buckets are made anonymously readable during setup - so a plain
 * {@code GET /bucket/key} with a {@code Range} header is a legitimate request
 * against all of them, exactly as it is against Heimdall and nginx.
 */
public record Target(String name, String description, URI readBase, boolean available) {

    public HttpRequest fullGet(String objectId, Duration timeout) {
        return HttpRequest.newBuilder(readUri(objectId)).timeout(timeout).GET().build();
    }

    public HttpRequest rangeGet(String objectId, long start, long endInclusive, Duration timeout) {
        return HttpRequest.newBuilder(readUri(objectId))
                .timeout(timeout)
                .header("Range", "bytes=" + start + "-" + endInclusive)
                .GET()
                .build();
    }

    public URI readUri(String objectId) {
        String base = readBase.toString();
        return URI.create(base.endsWith("/") ? base + objectId : base + "/" + objectId);
    }
}
