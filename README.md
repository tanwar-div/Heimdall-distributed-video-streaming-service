# Heimdall

A working video-streaming **load-balancing API**: a consistent-hash-routed
gateway sitting in front of a cluster of storage nodes (primaries + read
replicas), backed by real object storage (MinIO/S3-compatible), with genuine
HTTP calls between every service. Uploads are chunked and replicated
asynchronously; reads are fetched concurrently from multiple replicas with
automatic failover, and support HTTP `Range` requests so an HTML5 `<video>`
player (or `curl -r`) can seek without downloading the whole file.

This started as a single-JVM simulation (see the "What changed" section
below) and has been rebuilt into three separately deployable Spring Boot
services that talk to each other over the network, the same shape a real
deployment would take.

## Architecture

```
                     ┌─────────────┐
   client  ────────▶ │   gateway   │  consistent-hash ring, replica
  (upload/watch)     │  (:8080)    │  selection, Range-aware multi-source
                     └──────┬──────┘  streaming, API-key auth on writes
                            │ real HTTP calls
             ┌──────────────┼──────────────┐
             ▼              ▼              ▼
       ┌───────────┐  ┌───────────┐  ┌───────────┐
       │ primary-0 │  │ primary-1 │  │    ...    │   storage-node
       └─────┬─────┘  └─────┬─────┘  └───────────┘   (:8081 each)
             │ async replication (HTTP PUT)
       ┌─────┴──────┐
       ▼            ▼
┌─────────────┐┌─────────────┐
│  replica-0  ││  replica-1  │                        storage-node
└─────────────┘└─────────────┘                        (:8081 each)
             all nodes  │
                        ▼
                  ┌───────────┐
                  │   MinIO   │   one bucket per node
                  └───────────┘
```

- **gateway** — the public API. Owns the consistent-hash ring (built from
  static cluster config, not the in-process objects the demo used), routes
  each object key to its owning primary, randomly selects a configurable
  percentage of that primary's replicas per read, and fetches an object's
  chunks concurrently across them - with automatic failover to other replicas
  (and finally the primary) if a node is down or missing a chunk.
- **storage-node** — one jar, deployed twice as a role: `PRIMARY` (accepts
  writes, fans them out to its replicas) or `REPLICA` (read-only). Every
  object is split into fixed-size chunks as it streams in and each chunk is
  written straight to MinIO - nothing is buffered whole in memory, so
  multi-gigabyte video uploads/downloads stay bounded to roughly one chunk's
  worth of memory.
- **MinIO** — S3-compatible object storage. Each node gets its own bucket
  (named after its node id) inside one MinIO deployment, which keeps every
  node's data genuinely partitioned the way separate disks would be, without
  needing a separate MinIO instance per node.

## What changed from the original demo

| Before (`heimdall-simple` demo) | Now |
|---|---|
| One JVM; primaries/replicas were plain Java objects | Three separately deployable services making real HTTP calls |
| In-memory `Map`, data lost on restart | Persisted in MinIO/S3-compatible object storage |
| Whole object buffered as `byte[]` in memory | True streaming I/O end-to-end (upload and download) |
| No seeking - always downloaded the whole file | HTTP `Range` / `206 Partial Content` support |
| `Thread.sleep(15)` faking network latency | Real network calls - the latency is real |
| Synchronous, blocking replication | Asynchronous replication with retries, matching real eventual consistency |
| No failure handling - one bad replica broke the whole read | Automatic failover across replicas, then the primary |
| No auth | API-key auth on uploads/deletes |
| No tests | Unit tests for the ring, load balancer, streaming/range math, and chunked storage |
| No packaging story | Dockerfiles + docker-compose for the whole cluster |

## Design decisions worth knowing about

- **Static cluster topology instead of a service registry.** The gateway
  doesn't use Eureka/Consul for discovery; the primary/replica topology is
  fixed config (`heimdall.cluster.*`), matched to Docker Compose's built-in
  DNS. This keeps the deployment to one compose file with no extra
  infrastructure, while every call is still real HTTP to a separate process.
  Swapping in dynamic discovery later would only mean changing how
  `ClusterProperties` gets populated - nothing else.
- **One MinIO deployment, one bucket per node.** Running six separate MinIO
  instances for six storage nodes wasn't worth the operational weight for
  what this project needs to demonstrate; a shared MinIO with per-node
  buckets gives genuinely partitioned data with one moving part instead of
  seven.
- **Replication is async by default.** A primary acknowledges an upload as
  soon as it's durable on itself; replicas catch up moments later over a real
  HTTP call, with retries. This is what "eventually consistent" means in
  practice, and it's why reads fail over through the primary as a last
  resort - a replica genuinely might not have the object yet. Set
  `heimdall.node.synchronous-replication: true` on a primary to trade upload
  latency for the stronger guarantee instead.
- **Only the first `Range` is honored.** Multi-range requests
  (`bytes=0-99,200-299`) are rare in practice (browsers/video players issue
  single ranges); supporting them properly means a `multipart/byteranges`
  response, which wasn't worth the complexity here. A malformed or
  unsatisfiable range falls back to RFC 7233's documented behavior (ignore it
  and serve the whole file, or `416`, respectively).
- **A streaming response can't downgrade to a JSON error mid-stream.** Once
  the gateway sends a `200`/`206` and starts writing chunks, the HTTP status
  is already committed - if a later chunk fetch fails after that point, the
  connection is simply cut short rather than turning into a clean error body.
  This is inherent to true streaming responses (real CDNs behave the same
  way), not something the exception handler can special-case around.

## Run it

### Docker Compose (recommended)

Brings up MinIO, two primaries with two replicas each, and the gateway:

```bash
docker compose up --build
```

Then:

```bash
export API_KEY=changeme   # heimdall.api-key default; override via HEIMDALL_API_KEY

curl -X POST http://localhost:8080/objects/my-video \
  -H "X-API-Key: $API_KEY" -H "Content-Type: video/mp4" \
  --data-binary @some-file.mp4

curl http://localhost:8080/objects/my-video --output downloaded.mp4

curl http://localhost:8080/objects/my-video -H "Range: bytes=0-1048575" --output first-chunk.mp4
```

Swagger UI: http://localhost:8080/swagger-ui.html
MinIO console: http://localhost:9001 (user/pass default to `heimdall` / `heimdall-secret`)

### Dashboard (optional)

A plain HTML/JS dashboard lives in `Frontend/` — upload/play videos, inspect
routing decisions, and watch live traffic metrics and cluster health without
touching `curl`. See `Frontend/README.md`; short version:

```bash
cd Frontend && python3 -m http.server 5500
# open http://localhost:5500/login.html (admin / heimdall123 by default)
```

### Locally, without Docker

Every node is the same jar; only `--spring.profiles.active` differs. This was
the exact setup used to verify the project end-to-end during development:

```bash
mvn -DskipTests package

# a standalone MinIO binary works fine, or run it via `docker run minio/minio`
MINIO_ROOT_USER=heimdall MINIO_ROOT_PASSWORD=heimdall-secret ./minio server ./data --console-address ":9001" &

java -jar storage-node/target/heimdall-storage-node-boot.jar --spring.profiles.active=primary-0 --server.port=8081 &
java -jar storage-node/target/heimdall-storage-node-boot.jar --spring.profiles.active=primary-0-replica-0 --server.port=8082 &
java -jar storage-node/target/heimdall-storage-node-boot.jar --spring.profiles.active=primary-0-replica-1 --server.port=8083 &
java -jar storage-node/target/heimdall-storage-node-boot.jar --spring.profiles.active=primary-1 --server.port=8084 &
java -jar storage-node/target/heimdall-storage-node-boot.jar --spring.profiles.active=primary-1-replica-0 --server.port=8085 &
java -jar storage-node/target/heimdall-storage-node-boot.jar --spring.profiles.active=primary-1-replica-1 --server.port=8086 &

java -jar gateway/target/heimdall-gateway-boot.jar --heimdall.api-key=changeme
```

The default `application.yml` in each module already points at these exact
`localhost` ports, so no further config is needed.

## API reference

All endpoints are on the gateway (`:8080`).

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/objects/{id}` | `X-API-Key` | Upload. Multipart (`file` field) or raw body with any `Content-Type`. |
| `GET` | `/objects/{id}` | - | Download. Supports `Range: bytes=...` for seeking. |
| `DELETE` | `/objects/{id}` | `X-API-Key` | Delete from the primary and all its replicas. |
| `GET` | `/objects` | - | Best-effort list of every object id across the cluster. |
| `GET` | `/objects/{id}/replicas?readPercent=NN` | - | Debug: which primary owns this key, and which replicas a read would use. |
| `GET` | `/cluster` | - | The full topology the gateway was configured with. |
| `GET` | `/cluster/health` | - | Live UP/DOWN status of every primary and replica (used by the dashboard). |
| `GET` | `/metrics/summary` | - | Aggregated upload/download/failover counters and average chunk-fetch latency. |

Upload validation: rejects content types not on the node's allow-list (415)
and objects over the configured max size (413) - see `heimdall.node.*` in
`storage-node/src/main/resources/application.yml`.

## Configuration

Key `heimdall.*` properties (env-overridable, see each module's
`application.yml`):

- **gateway**: `api-key`, `default-read-percent`, `max-fetch-attempts`,
  `fetch-pool-size`, `cluster.router` (`RENDEZVOUS`/`RING`/`KETAMA`),
  `cluster.hash-function`, `cluster.virtual-nodes` (`RING` only),
  `cluster.primaries[]`
- **storage-node**: `node.id`, `node.role` (`PRIMARY`/`REPLICA`),
  `node.chunk-size-bytes` (default 1 MiB), `node.max-object-size-bytes`
  (default 5 GiB), `node.synchronous-replication`, `node.allowed-content-types`,
  `minio.endpoint` / `minio.access-key` / `minio.secret-key`

## Testing

```bash
mvn test                      # unit + property-based tests, no infrastructure
mvn verify -pl integration-tests   # the real cluster, needs Docker
```

Two layers, testing different things.

**Property-based tests** (jqwik) prove the algorithms rather than sampling
them. Instead of asserting that a few hand-picked byte ranges come back
correctly, `ChunkPlanTest` checks *every* range of every small object
exhaustively, and thousands of generated ranges over larger ones, against a
trivially-correct reference. `KeyRouterPropertiesTest` checks the guarantees
that make consistent hashing worth using - that losing a node moves only that
node's keys, and that adding then removing one restores the exact original
mapping - across every routing algorithm at once. `StreamingFailoverPropertiesTest`
generates node-failure combinations (unreachable, reachable-but-not-replicated-yet,
healthy) and asserts a read succeeds whenever any node still holds the data, and
that the bytes are exactly right when it does. `Murmur3Test` verifies the
hand-written hash against Guava's reference implementation, because every
benchmark number downstream depends on it genuinely being murmur3.

**Integration tests** (`integration-tests/`) run the real thing: a real MinIO
container, each storage node as a separate Spring Boot application on its own
port, and the gateway calling them over real HTTP. Nothing is mocked - "a node
is down" means the process is actually gone. They cover the full lifecycle
(upload, replication, ranged and full reads, failover through every replica to
the primary, delete), auth, validation status codes, routing spread across
primaries, and a 24 MiB object streamed across 384 chunks. They skip
automatically when no Docker daemon is reachable.

## Project layout

```
common/             routing algorithms + hashes, byte-range planning, wire DTOs
storage-node/       one node's storage engine (chunking, MinIO, replication)
gateway/            the public API: routing, load balancing, streaming, auth, metrics
benchmarks/         JMH suites + the routing quality analysis and the end-to-end load driver
integration-tests/  the real cluster against real MinIO, over real HTTP
Frontend/           plain HTML/JS dashboard - upload, playback, routing/health visibility
bench/              scripts and compose config for the benchmark runs
docker-compose.yml
docker-compose.bench.yml
```

## Benchmarks

Full results and methodology in [BENCHMARKS.md](BENCHMARKS.md).

```bash
./bench/run-algorithms.sh full   # algorithms only, no infrastructure
./bench/run-e2e.sh 64 15         # vs MinIO, nginx and SeaweedFS
```

The headline finding changed the code. Heimdall routed keys with a
consistent-hash ring hashed by CRC-32, which passes every correctness test and
is measurably the worst of the nine routing configurations benchmarked: on the
deployed two-primary topology it gave one primary **63.7% of all objects**.
Raising the virtual-node count - the standard remedy - does not fix it, because
CRC-32 is a checksum whose outputs cluster for the structurally-similar inputs a
ring feeds it; at 32 primaries, 500 virtual nodes is *worse* than 100 while
costing five times the memory.

The default is now rendezvous (highest-random-weight) hashing, which holds every
primary within 2.2% of an even share at every cluster size, moves the
theoretically minimal number of keys when a node joins or leaves, and does it
with 96 bytes of routing state instead of 10.9 KiB.

It is also **faster** at this cluster's size - 20 ns per lookup against the
CRC-32 ring's 39 ns at two primaries - which is the opposite of what its O(N)
scan predicts. A ring lookup is a pointer chase down a red-black tree of boxed
`Long`s and misses cache on the way; a rendezvous scan over eight members is a
contiguous array that fits in L1. The crossover where the ring wins is around 32
primaries, which is why the algorithm is `heimdall.cluster.router` configuration
rather than a hard-coded choice. The ring and libketama remain selectable,
CRC-32 included, so the comparison stays reproducible rather than becoming a
claim about the past.

Worth stating plainly: the gateway's entire per-read routing CPU cost is about
**84 ns**, against ~100,000 ns for one network round-trip to a storage node. The
ring was never slow enough to matter - it was unbalanced enough to matter, and
those are different problems with different fixes.

Google's jump consistent hash scores as well as rendezvous on distribution and
is faster still, but is disqualified by its contract rather than its
performance: it addresses bucket indices, so when `primary-0` fails out of
eight, **98.2%** of the keyspace remaps instead of the necessary 12.5%. That is
asserted as a test rather than quietly avoided.

## Possible next steps

- Dynamic service discovery (Eureka/Consul) instead of static cluster config,
  so nodes can join/leave without a gateway config change.
- Adaptive-bitrate streaming (transcode on upload, serve HLS/DASH playlists)
  instead of serving the original file with Range support.
- A durable replication outbox (instead of best-effort async + retries) so a
  primary that crashes mid-replication doesn't leave a replica permanently
  behind.
