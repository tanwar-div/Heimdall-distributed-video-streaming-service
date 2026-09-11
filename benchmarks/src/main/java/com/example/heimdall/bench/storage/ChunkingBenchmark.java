package com.example.heimdall.bench.storage;

import com.example.heimdall.node.config.NodeProperties;
import com.example.heimdall.node.service.ChunkStorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.mockito.Mockito;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.io.ByteArrayInputStream;
import java.util.concurrent.TimeUnit;

/**
 * The storage node's chunking loop with MinIO stubbed out, so the measurement
 * is the CPU cost of splitting a stream rather than the speed of the network
 * behind it.
 *
 * <p>This is the number that says whether chunk size is a throughput knob or
 * just a memory knob. If the loop itself costs nothing at 64 KiB and at 4 MiB,
 * then chunk size can be chosen purely for its effect on seek granularity and
 * per-chunk request overhead, which is a much easier decision to reason about.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2, jvmArgs = {"-Xms2g", "-Xmx2g"})
public class ChunkingBenchmark {

    @Param({"65536", "1048576", "4194304"})
    public int chunkSizeBytes;

    /** 32 MiB of payload: large enough that the per-object metadata write is not what is being timed. */
    private static final int OBJECT_BYTES = 32 * 1024 * 1024;

    private ChunkStorageService service;
    private byte[] payload;

    @Setup
    public void setUp() throws Exception {
        // stubOnly() is load-bearing, not a style choice: an ordinary Mockito
        // mock retains every invocation (and therefore every PutObjectArgs and
        // the chunk buffer behind it) so it can verify them later. Across the
        // millions of calls a throughput benchmark makes, that is an unbounded
        // leak, and this benchmark reliably died with OutOfMemoryError until
        // recording was switched off.
        MinioClient minioClient = Mockito.mock(MinioClient.class, Mockito.withSettings().stubOnly());
        // Consume the stream the way a real put would, so the benchmark still
        // pays for actually reading the bytes rather than optimising them away.
        Mockito.when(minioClient.putObject(Mockito.any(PutObjectArgs.class)))
                .thenAnswer(invocation -> {
                    PutObjectArgs args = invocation.getArgument(0);
                    args.stream().transferTo(java.io.OutputStream.nullOutputStream());
                    return null;
                });

        NodeProperties properties = new NodeProperties();
        properties.setId("primary-0");
        properties.setChunkSizeBytes(chunkSizeBytes);
        properties.setMaxObjectSizeBytes(Long.MAX_VALUE);

        service = new ChunkStorageService(minioClient, properties,
                new ObjectMapper().registerModule(new JavaTimeModule()));
        payload = new byte[OBJECT_BYTES];
    }

    @Benchmark
    public Object chunkA32MiBObject() {
        return service.store("bench-object", new ByteArrayInputStream(payload), "video/mp4", "bench.mp4");
    }
}
