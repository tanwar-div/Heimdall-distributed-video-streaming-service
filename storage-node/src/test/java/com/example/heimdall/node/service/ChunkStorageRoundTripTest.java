package com.example.heimdall.node.service;

import com.example.heimdall.common.dto.ObjectMetadataDto;
import com.example.heimdall.common.stream.ChunkPlan;
import com.example.heimdall.node.config.NodeProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The property that makes the storage node a storage node: whatever bytes go
 * in must be exactly the bytes that come back out, for any object size and any
 * chunk size, including all the awkward relationships between the two.
 *
 * <p>The existing {@code ChunkStorageServiceTest} verifies that {@code store}
 * calls {@code putObject} the right number of times with the right names.
 * That is a test of the plumbing, and it would pass unchanged if the chunking
 * loop dropped a byte at every boundary or wrote overlapping slices. This test
 * captures the bytes actually handed to MinIO, reassembles them the way a read
 * would, and compares against the input - which is the only claim a user of
 * this service cares about.
 *
 * <p>The cases that matter and that a hand-picked example set tends to miss:
 * an object that is an exact multiple of the chunk size (is there a spurious
 * empty trailing chunk?), an object smaller than one chunk, and a zero-byte
 * object.
 */
class ChunkStorageRoundTripTest {

    /** An in-memory stand-in for MinIO that keeps the bytes of every object written to it. */
    private record Fixture(ChunkStorageService service, Map<String, byte[]> stored) {
    }

    private static Fixture fixture(int chunkSize) {
        Map<String, byte[]> stored = new HashMap<>();
        MinioClient minioClient = Mockito.mock(MinioClient.class, Mockito.withSettings().stubOnly());
        try {
            Mockito.when(minioClient.putObject(Mockito.any(PutObjectArgs.class)))
                    .thenAnswer(invocation -> {
                        PutObjectArgs args = invocation.getArgument(0);
                        ByteArrayOutputStream captured = new ByteArrayOutputStream();
                        args.stream().transferTo(captured);
                        stored.put(args.object(), captured.toByteArray());
                        return null;
                    });
        } catch (Exception e) {
            throw new AssertionError(e);
        }

        NodeProperties properties = new NodeProperties();
        properties.setId("primary-0");
        properties.setChunkSizeBytes(chunkSize);
        properties.setMaxObjectSizeBytes(Long.MAX_VALUE);

        return new Fixture(
                new ChunkStorageService(minioClient, properties,
                        new ObjectMapper().registerModule(new JavaTimeModule())),
                stored);
    }

    private static byte[] payload(int size, long seed) {
        byte[] data = new byte[size];
        new Random(seed).nextBytes(data);
        return data;
    }

    /** Concatenates the stored chunks back into the whole object, as a full download would. */
    private static byte[] reassemble(Map<String, byte[]> stored, ObjectMetadataDto meta) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < meta.chunkCount(); i++) {
            byte[] chunk = stored.get(meta.objectId() + "/chunks/" + i);
            assertThat(chunk).as("chunk %d of %s must exist", i, meta.objectId()).isNotNull();
            out.writeBytes(chunk);
        }
        return out.toByteArray();
    }

    @Property(tries = 500)
    void everyObjectComesBackByteForByteWhateverTheChunkSize(
            @ForAll @IntRange(min = 0, max = 20_000) int objectSize,
            @ForAll @IntRange(min = 1, max = 4096) int chunkSize) {

        Fixture fixture = fixture(chunkSize);
        byte[] original = payload(objectSize, objectSize * 31L + chunkSize);

        ObjectMetadataDto meta = fixture.service()
                .store("video-1", new ByteArrayInputStream(original), "video/mp4", "clip.mp4");

        assertThat(meta.totalSize()).isEqualTo(objectSize);
        assertThat(reassemble(fixture.stored(), meta)).isEqualTo(original);
    }

    @Property(tries = 300)
    void chunkCountIsTheMinimumNeededToHoldTheObject(
            @ForAll @IntRange(min = 1, max = 20_000) int objectSize,
            @ForAll @IntRange(min = 1, max = 4096) int chunkSize) {

        Fixture fixture = fixture(chunkSize);
        ObjectMetadataDto meta = fixture.service()
                .store("video-1", new ByteArrayInputStream(payload(objectSize, 7)), "video/mp4", "clip.mp4");

        int expected = (objectSize + chunkSize - 1) / chunkSize; // ceil
        assertThat(meta.chunkCount())
                .as("an extra chunk is an extra network round-trip on every full read")
                .isEqualTo(expected);
    }

    @Property(tries = 300)
    void anyByteRangeOfAStoredObjectReassemblesCorrectly(
            @ForAll @IntRange(min = 1, max = 8000) int objectSize,
            @ForAll @IntRange(min = 1, max = 1024) int chunkSize,
            @ForAll @IntRange(min = 0, max = 7999) int rawStart,
            @ForAll @IntRange(min = 0, max = 7999) int rawLength) {

        Fixture fixture = fixture(chunkSize);
        byte[] original = payload(objectSize, 99);
        ObjectMetadataDto meta = fixture.service()
                .store("video-1", new ByteArrayInputStream(original), "video/mp4", "clip.mp4");

        long start = Math.min(rawStart, objectSize - 1);
        long end = Math.min(start + rawLength, objectSize - 1);

        // End-to-end: the plan the gateway builds, applied to the chunks the
        // node actually wrote. This is the join between the two halves of the
        // read path, and it is where a chunk-size disagreement would surface.
        ChunkPlan plan = ChunkPlan.forRange(start, end, meta.chunkSize());
        ByteArrayOutputStream received = new ByteArrayOutputStream();
        for (int i = plan.firstChunk(); i <= plan.lastChunk(); i++) {
            byte[] chunk = fixture.stored().get("video-1/chunks/" + i);
            int from = plan.offsetWithin(i);
            int to = plan.endWithin(i, chunk.length);
            if (from < to) {
                received.write(chunk, from, to - from);
            }
        }

        assertThat(received.toByteArray())
                .isEqualTo(java.util.Arrays.copyOfRange(original, (int) start, (int) end + 1));
    }

    @Test
    void anObjectThatIsAnExactMultipleOfTheChunkSizeHasNoEmptyTrailingChunk() {
        Fixture fixture = fixture(100);
        ObjectMetadataDto meta = fixture.service()
                .store("video-1", new ByteArrayInputStream(payload(300, 1)), "video/mp4", "clip.mp4");

        assertThat(meta.chunkCount()).isEqualTo(3);
        assertThat(fixture.stored()).doesNotContainKey("video-1/chunks/3");
    }

    @Test
    void aZeroByteObjectStoresExactlyOneEmptyChunkSoItStaysReadable() {
        Fixture fixture = fixture(100);
        ObjectMetadataDto meta = fixture.service()
                .store("empty", new ByteArrayInputStream(new byte[0]), "video/mp4", "empty.mp4");

        assertThat(meta.totalSize()).isZero();
        assertThat(meta.chunkCount()).isEqualTo(1);
        assertThat(fixture.stored().get("empty/chunks/0")).isEmpty();
        assertThat(reassemble(fixture.stored(), meta)).isEmpty();
    }

    @Test
    void survivesASourceStreamThatDribblesBytesOutAFewAtATime() {
        // A real socket returns short reads constantly; the chunking loop must
        // keep pulling until the buffer is full rather than treating a short
        // read as end-of-stream and emitting a runt chunk.
        Fixture fixture = fixture(64);
        byte[] original = payload(1000, 5);
        ByteArrayInputStream slow = new ByteArrayInputStream(original) {
            @Override
            public synchronized int read(byte[] b, int off, int len) {
                return super.read(b, off, Math.min(len, 7)); // never more than 7 bytes at a time
            }
        };

        ObjectMetadataDto meta = fixture.service().store("dribble", slow, "video/mp4", "clip.mp4");

        assertThat(meta.totalSize()).isEqualTo(1000);
        assertThat(meta.chunkCount()).isEqualTo(16); // ceil(1000/64), not 143 runt chunks
        assertThat(reassemble(fixture.stored(), meta)).isEqualTo(original);
    }
}
