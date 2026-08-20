package com.example.heimdall.node.service;

import java.io.IOException;
import java.io.InputStream;

/**
 * Lazily concatenates an object's chunks - each fetched fresh from MinIO,
 * one at a time - into a single readable stream, without ever holding more
 * than one chunk in memory. Used to re-stream an already-stored object's raw
 * bytes to a replica during replication, straight from durable storage.
 */
class ChunkedObjectInputStream extends InputStream {

    private final ChunkStorageService chunkStorageService;
    private final String objectId;
    private final int chunkCount;
    private int nextChunkIndex = 0;
    private InputStream current;

    ChunkedObjectInputStream(ChunkStorageService chunkStorageService, String objectId, int chunkCount) {
        this.chunkStorageService = chunkStorageService;
        this.objectId = objectId;
        this.chunkCount = chunkCount;
    }

    @Override
    public int read() throws IOException {
        byte[] single = new byte[1];
        int n = read(single, 0, 1);
        return n == -1 ? -1 : (single[0] & 0xFF);
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        while (true) {
            if (current == null) {
                if (nextChunkIndex >= chunkCount) {
                    return -1;
                }
                current = chunkStorageService.getChunk(objectId, nextChunkIndex);
                nextChunkIndex++;
            }
            int n = current.read(b, off, len);
            if (n != -1) {
                return n;
            }
            current.close();
            current = null; // exhausted, advance to the next chunk on the following call
        }
    }

    @Override
    public void close() throws IOException {
        if (current != null) {
            current.close();
            current = null;
        }
    }
}
