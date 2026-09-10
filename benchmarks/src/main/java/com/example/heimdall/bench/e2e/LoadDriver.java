package com.example.heimdall.bench.e2e;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Drives closed-loop load against a {@link Target} and reports what came back.
 *
 * <p>Two decisions here are worth stating, because they are the ones that
 * usually make a homegrown benchmark misleading.
 *
 * <p><b>Every response body is verified against the source bytes.</b> A load
 * generator that discards response bodies will happily report enormous
 * throughput from a server returning truncated data, empty 206s, or the wrong
 * range - and a chunked, failing-over, multi-source read path is precisely
 * where that could happen. Verification costs a memory comparison per request
 * and turns the benchmark into a correctness test that also produces numbers.
 *
 * <p><b>Time to first byte is measured separately from transfer time.</b> They
 * answer different questions: TTFB is what a viewer experiences as "how long
 * until the video starts", and it is where a gateway's extra hop and its
 * metadata lookup show up. Total time is dominated by raw bandwidth and would
 * bury that effect entirely.
 */
public final class LoadDriver {

    /** One (target, workload, concurrency) cell of the results matrix. */
    public record Measurement(String target, Workload workload, int concurrency,
                              long operations, long bytesTransferred, double seconds,
                              long[] ttfbSorted, long[] totalSorted,
                              long errors, long integrityFailures) {

        public double throughputMiBPerSecond() {
            return bytesTransferred / (1024.0 * 1024.0) / seconds;
        }

        public double operationsPerSecond() {
            return operations / seconds;
        }
    }

    private final HttpClient httpClient;
    private final byte[] sourceObject;
    private final String objectId;
    private final Duration requestTimeout;

    public LoadDriver(HttpClient httpClient, byte[] sourceObject, String objectId, Duration requestTimeout) {
        this.httpClient = httpClient;
        this.sourceObject = sourceObject;
        this.objectId = objectId;
        this.requestTimeout = requestTimeout;
    }

    public Measurement measure(Target target, Workload workload, int concurrency,
                               Duration warmup, Duration measurement) throws InterruptedException {
        runPhase(target, workload, concurrency, warmup, null, null, null, null, null);

        Latencies ttfb = new Latencies(2_000_000);
        Latencies total = new Latencies(2_000_000);
        AtomicLong operations = new AtomicLong();
        AtomicLong bytes = new AtomicLong();
        AtomicLong errors = new AtomicLong();
        AtomicLong integrityFailures = new AtomicLong();

        long startNanos = System.nanoTime();
        runPhase(target, workload, concurrency, measurement, ttfb, total, operations, bytes, errors,
                integrityFailures);
        double seconds = (System.nanoTime() - startNanos) / 1_000_000_000.0;

        return new Measurement(target.name(), workload, concurrency, operations.get(), bytes.get(),
                seconds, ttfb.sorted(), total.sorted(), errors.get(), integrityFailures.get());
    }

    private void runPhase(Target target, Workload workload, int concurrency, Duration duration,
                          Latencies ttfb, Latencies total, AtomicLong operations, AtomicLong bytes,
                          AtomicLong errors) throws InterruptedException {
        runPhase(target, workload, concurrency, duration, ttfb, total, operations, bytes, errors, null);
    }

    private void runPhase(Target target, Workload workload, int concurrency, Duration duration,
                          Latencies ttfb, Latencies total, AtomicLong operations, AtomicLong bytes,
                          AtomicLong errors, AtomicLong integrityFailures) throws InterruptedException {

        long deadline = System.nanoTime() + duration.toNanos();
        CountDownLatch done = new CountDownLatch(concurrency);

        try (ExecutorService pool = Executors.newFixedThreadPool(concurrency)) {
            for (int worker = 0; worker < concurrency; worker++) {
                final long seed = worker * 104729L + workload.ordinal();
                pool.submit(() -> {
                    Random random = new Random(seed);
                    try {
                        while (System.nanoTime() < deadline) {
                            issueOne(target, workload, random, ttfb, total, operations, bytes, errors,
                                    integrityFailures);
                        }
                    } finally {
                        done.countDown();
                    }
                });
            }
            done.await();
        }
    }

    private void issueOne(Target target, Workload workload, Random random,
                          Latencies ttfb, Latencies total, AtomicLong operations, AtomicLong bytes,
                          AtomicLong errors, AtomicLong integrityFailures) {

        long start;
        long end;
        HttpRequest request;
        if (workload.isRanged()) {
            long span = Math.min(workload.bytes(), sourceObject.length);
            start = sourceObject.length == span ? 0 : (long) (random.nextDouble() * (sourceObject.length - span));
            end = start + span - 1;
            request = target.rangeGet(objectId, start, end, requestTimeout);
        } else {
            start = 0;
            end = sourceObject.length - 1L;
            request = target.fullGet(objectId, requestTimeout);
        }

        long began = System.nanoTime();
        try {
            // ofInputStream returns as soon as the response headers arrive, so
            // the split between "server started answering" and "bytes finished
            // arriving" is real rather than inferred.
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            long headersAt = System.nanoTime();

            int status = response.statusCode();

            // Compared incrementally against the source rather than buffered
            // and compared at the end. Buffering would mean holding the whole
            // response plus a copy of the expected slice per in-flight request
            // - at 32 concurrent full downloads of a 64 MiB object that is
            // several GiB of garbage, and the benchmark would be measuring its
            // own allocator and GC as much as the server.
            long received = 0;
            boolean matches = true;
            byte[] buffer = new byte[64 * 1024];
            try (InputStream in = response.body()) {
                int read;
                while ((read = in.read(buffer)) != -1) {
                    if (matches && !regionMatches(buffer, read, start + received)) {
                        matches = false;
                    }
                    received += read;
                }
            }
            long finishedAt = System.nanoTime();

            if (status != 200 && status != 206) {
                if (errors != null) {
                    errors.incrementAndGet();
                }
                return;
            }

            if (integrityFailures != null && (!matches || received != end - start + 1)) {
                integrityFailures.incrementAndGet();
                return;
            }

            if (ttfb != null) {
                ttfb.record(headersAt - began);
                total.record(finishedAt - began);
                operations.incrementAndGet();
                bytes.addAndGet(received);
            }
        } catch (IOException | InterruptedException e) {
            if (errors != null) {
                errors.incrementAndGet();
            }
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** True if {@code length} bytes of {@code buffer} equal the source object starting at {@code sourceOffset}. */
    private boolean regionMatches(byte[] buffer, int length, long sourceOffset) {
        if (sourceOffset + length > sourceObject.length) {
            return false;
        }
        return Arrays.equals(buffer, 0, length,
                sourceObject, (int) sourceOffset, (int) sourceOffset + length);
    }

    /** Confirms a target serves correct bytes at all before any timing is attempted. */
    public List<String> verify(Target target) {
        List<String> problems = new ArrayList<>();
        try {
            HttpResponse<byte[]> full = httpClient.send(
                    target.fullGet(objectId, requestTimeout), HttpResponse.BodyHandlers.ofByteArray());
            if (full.statusCode() != 200) {
                problems.add("full GET returned HTTP " + full.statusCode());
            } else if (!Arrays.equals(full.body(), sourceObject)) {
                problems.add("full GET returned " + full.body().length + " bytes, expected " + sourceObject.length);
            }

            long start = sourceObject.length / 3;
            long end = start + 65535;
            HttpResponse<byte[]> ranged = httpClient.send(
                    target.rangeGet(objectId, start, end, requestTimeout), HttpResponse.BodyHandlers.ofByteArray());
            if (ranged.statusCode() != 206) {
                problems.add("range GET returned HTTP " + ranged.statusCode() + ", expected 206");
            } else if (!Arrays.equals(ranged.body(), Arrays.copyOfRange(sourceObject, (int) start, (int) end + 1))) {
                problems.add("range GET returned the wrong bytes");
            }
        } catch (IOException | InterruptedException e) {
            problems.add("unreachable: " + e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
        return problems;
    }
}
