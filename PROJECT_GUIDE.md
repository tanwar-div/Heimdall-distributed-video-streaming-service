# Heimdall — Complete Project Guide

This document explains the entire project end to end: what it does, why it's
built the way it is, and what every file is for. It's written to be read
top-to-bottom before an interview, or jumped into by section when you need to
refresh one part.

---

## 1. The 30-second pitch

Heimdall is a **video-streaming load-balancing API**. A client uploads a
video once; the system picks a "primary" node to own it (via consistent
hashing), splits it into fixed-size chunks, stores them in object storage,
and asynchronously copies them to that primary's read replicas. When a
client requests the video back, a **gateway** service picks a subset of the
owning primary's replicas, fetches the object's chunks **concurrently across
them**, and streams the result back — supporting HTTP `Range` requests so a
video player can seek without downloading the whole file. If a replica is
down or missing a chunk, the gateway automatically retries against another
node.

It's three separately-deployable Spring Boot services (`gateway`,
`storage-node` used as either a primary or a replica, and a shared `common`
library), backed by MinIO (S3-compatible object storage), all wired together
with real HTTP calls and Docker Compose.

**Why this is interesting to talk about in an interview**: it touches
distributed systems fundamentals (consistent hashing, replication,
eventual consistency, failover) but at a small enough scale that you can
explain every line of it, not just wave at a diagram.

---

## 2. The problem, restated

A single server can't serve every viewer of a popular video — you need to
fan reads out across multiple copies of the data. But you also don't want
every server storing a copy of every video (unbounded storage growth) or a
central registry that's a bottleneck for "which server has this video."
**Consistent hashing** solves the second problem (deterministically compute
which node owns a key, no lookup table needed); **replication +
load-balanced reads** solve the first (spread read traffic across several
copies of the data that node owns).

That's the whole system in one sentence: *consistent hashing decides who
owns the write; replica selection + concurrent fetch decides who serves the
read.*

---

## 3. Architecture

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

Three Maven modules, one parent reactor build:

- **`common`** — a small, framework-free library shared by the other two:
  the consistent-hash ring itself, and the JSON DTOs that go over the wire
  between the gateway and a node.
- **`storage-node`** — one Spring Boot jar, deployed **six times** in the
  default topology (2 primaries × 2 replicas each), differing only by
  environment/profile. It owns chunking, MinIO I/O, and (if it's a primary)
  pushing replicated copies to its replicas.
- **`gateway`** — the public API. Owns the ring, the replica-selection
  algorithm, the concurrent/failover chunk-fetch logic, HTTP Range parsing,
  and API-key auth.

Why **three modules and not one**? `common` has zero Spring dependency and
could be reused outside this project; `storage-node` and `gateway` are
independently deployable and scale independently (you'd run many more
storage nodes than gateways in a real deployment). Splitting them is what
makes this "real" microservices rather than a simulation — every call
between them is a genuine HTTP request to a separate JVM process.

---

## 4. Core concepts (be ready to explain these fluently)

### 4.1 Consistent hashing, and *why* (not just what)

**The naive approach** to "which of N servers owns this key" is
`hash(key) % N`. The problem: if N changes (a server joins or leaves),
`% N` changes for *almost every key*, meaning almost all data has to move.
That's catastrophic at scale.

**Consistent hashing** fixes this by hashing both the *keys* and the
*servers* onto the same circular numeric space (a "ring"), then defining
ownership as "walk clockwise from the key's position to the first server
you find." Adding or removing one server now only reassigns the keys that
were between it and its neighbor on the ring — a small fraction, not
everything.

**Virtual nodes** (this project uses 100 per primary by default) are the
standard refinement: instead of placing each server at one point on the
ring, place it at many (hashed) points. With few real servers, a single
point per server distributes keys very unevenly (some servers would own
much bigger arcs than others by chance); many points per server averages
that out.

**Implementation** (`common/.../ConsistentHashRing.java`): a `TreeMap<Long, T>`
keyed by hash value. `TreeMap` is a `NavigableMap`, which gives
`ceilingEntry(key)` — "the smallest map entry whose key is ≥ this value" —
in O(log n). That one call *is* the "walk clockwise" step; if it returns
`null` (the key hashed past every server's highest point), wrap around to
`firstEntry()`. The hash function is `java.util.zip.CRC32` — not
cryptographically strong, but it doesn't need to be; the ring only needs
*even distribution*, not collision resistance against an adversary. It's
also dependency-free (ships with the JDK), which is why it was picked over
MurmurHash/MD5 for a project this size.

### 4.2 Primary/replica model and replication

Every object has exactly one **primary** (whoever it hashes to) and that
primary's fixed set of **replicas**. Writes always go to the primary; reads
are served by replicas (with the primary as a last-resort fallback). This
read/write split is *why* you can scale reads independently of writes —
replicas can be read-heavy and disposable, while the primary is the single
source of truth for that key.

**Replication is asynchronous.** The primary acknowledges an upload as soon
as *it* has durably stored the object (in MinIO); it then pushes copies to
its replicas over real HTTP calls, in the background, with retries. This is
literally what "eventually consistent" means in a distributed system: for a
brief window after upload, a replica might not have the data yet if you
happen to read from it. The gateway's failover logic (see 4.4) is what
makes this safe to expose to clients — if a replica doesn't have it yet, the
gateway just tries the next candidate, ending at the primary, which always
has it.

### 4.3 Chunking

Every object is split into fixed-size chunks (1 MiB by default) on ingest,
and each chunk is stored as its own object in MinIO. Two reasons this
matters:

1. **Memory bounds.** Reading/writing one chunk at a time means a
   multi-gigabyte video never has to be held whole in memory — the process's
   memory footprint per request is roughly one chunk, not the whole file.
2. **Parallel multi-source fetch.** This is the actual point of the
   "load-balancing" in the name: when the gateway reads an object back, it
   assigns different chunks to different replicas round-robin
   (`chunkIndex % replicaCount`) and fetches them **concurrently**. N chunks
   spread across M replicas take roughly `(N/M)` sequential fetches' worth of
   time instead of `N`, the same way a download manager splits a file across
   connections.

### 4.4 Failover

Every read (metadata lookup *and* each individual chunk fetch) has a
**candidate list**: the selected replicas (in their randomly shuffled
order), then this primary's other, unselected replicas, then the primary
itself, as an ordered fallback chain. If a candidate throws a
"not found" (`NoSuchElementException` — the node doesn't have that object)
or "unavailable" (`NodeUnavailableException` — network error, timeout, 5xx),
the code just moves to the next candidate, up to a configurable
`max-fetch-attempts`. Only if every candidate fails does the caller finally
see a `404` or `503`. This is what let the project survive killing a
primary outright during testing and still serve correct data from replicas
alone (see §9 for that exact test).

### 4.5 HTTP Range requests (why video needs this specifically)

A browser's `<video>` tag (or any real video player) doesn't download a
whole file before playing — it requests small byte ranges as needed, and
critically, it needs to be able to **seek**: jump to the 10-minute mark
without downloading the first 10 minutes. That's the `Range: bytes=...`
request header and the `206 Partial Content` / `Content-Range` response
mechanics defined in RFC 7233. Without this, "streaming" would really just
mean "progressive download from the start," which breaks seeking on
anything but a fully-buffered file.

The gateway converts a byte range into a **chunk range** (`start/chunkSize`
to `end/chunkSize`), fetches only those chunks (not the whole object), and
trims the first/last chunk to the exact requested bytes before writing them
to the response.

---

## 5. File-by-file walkthrough

### 5.1 `common` module — shared, framework-free code

**`common/pom.xml`** — a plain `jar` module (not a Spring Boot app). Its only
runtime dependency is `jackson-databind`, because the DTOs it defines need
to be (de)serializable — both the gateway and storage-node exchange these
as JSON over HTTP.

**`ring/ConsistentHashRing.java`** — covered in depth in §4.1. Worth noting
in an interview: it's generic (`ConsistentHashRing<T>`), so the *same* class
is reused as `ConsistentHashRing<PrimaryNode>` in the gateway — nothing
ring-specific knows or cares that `T` happens to be a node record. It's also
thread-safe via a `ReadWriteLock`: routing lookups (`getMemberFor`) can run
fully concurrently with each other (they only take the read lock), and only
block if a membership change (`addMember`/`removeMember`) is happening at
the same instant — appropriate because lookups happen on every request while
membership changes are rare (only at startup, in this project).

**`dto/ObjectMetadataDto.java`** — a Java `record` carrying everything the
gateway needs to plan a read *without* touching chunk bytes: `objectId`,
`contentType`, `originalFilename`, `totalSize`, `chunkSize`, `chunkCount`,
`createdAt`. This is what a node's `GET /internal/objects/{id}/meta`
endpoint returns, and it's the thing that makes byte-range math possible —
the gateway needs `totalSize` and `chunkSize` to convert a `Range` header
into a set of chunk indices before it fetches anything.

**`dto/ErrorResponseDto.java`** — the standard JSON error body
(`timestamp`, `status`, `error`, `message`, `path`) returned by *both*
services on failure, so a client sees a consistent shape whether the error
came from the gateway directly or was translated from a node's response.

**`ring/ConsistentHashRingTest.java`** — five tests worth knowing by name if
asked "what did you test and why":
- *throws when empty* — calling `getMemberFor` before any member joined
  should fail loudly (`IllegalStateException`), not NPE.
- *routing is deterministic for the same key* — the whole system depends on
  this; a key must always resolve to the same primary or reads/writes would
  disagree with each other.
- *distributes keys reasonably evenly* — with enough virtual nodes, 3000
  keys across 3 members shouldn't wildly favor one member; this is the test
  that actually validates the virtual-node design decision, not just the
  algorithm's correctness.
- *removing a member reassigns its keys but leaves others mostly stable* —
  this is the property that makes consistent hashing worth using over
  `hash % N` in the first place.
- *rejects a non-positive virtual-node count* — a basic input-validation
  guard.

### 5.2 `storage-node` module — one storage engine, two roles

**`storage-node/pom.xml`** — depends on `spring-boot-starter-web` (REST),
`-validation` (bean validation on config), `-actuator` (health endpoint),
and the MinIO SDK (`io.minio:minio`). One subtlety worth mentioning if
asked about build problems you solved: **`io.minio:minio` transitively
depends on `okhttp` 5.x, whose plain `okhttp` Maven artifact is now an empty
Kotlin-Multiplatform shell** — the real JVM classes live in a separate
`okhttp-jvm` artifact that Gradle resolves automatically via module metadata
but plain Maven does not. The fix (in the root `pom.xml`'s
`dependencyManagement` and this module's dependency list) is pinning
`com.squareup.okhttp3:okhttp-jvm` explicitly. This is a good "debugging a
real build problem" story if an interviewer asks about something you had to
troubleshoot.

**`config/NodeProperties.java`** — typed, validated binding of the
`heimdall.node.*` YAML block. Key fields: `id` (also doubles as the node's
MinIO bucket name — see below), `role` (`PRIMARY`/`REPLICA` enum),
`chunkSizeBytes`, `maxObjectSizeBytes`, `allowedContentTypes` (an allow-list
— uploads with any other `Content-Type` are rejected with `415`),
`replicas` (a primary's list of `{id, baseUrl}` targets — empty and unused
for a `REPLICA` node), and replication tuning (`synchronousReplication`,
timeout, retry count). `@Validated` + `jakarta.validation` annotations mean
a misconfigured node (e.g. blank `id`) fails fast at startup instead of
misbehaving at request time.

**`config/MinioProperties.java`** — the three MinIO connection settings
(`endpoint`, `accessKey`, `secretKey`), same pattern.

**`config/MinioConfig.java`** — two things: a `@Bean` that builds the
`MinioClient`, and a nested `BucketInitializer` whose `@PostConstruct`
method checks whether *this node's own bucket* (named after its node id)
exists, creating it if not. **Design decision worth defending**: rather than
running a separate MinIO instance per node (operationally heavy for what
this needs to demonstrate), every node shares one MinIO deployment but gets
its own bucket. That gives genuinely partitioned data — a replica literally
cannot see a primary's bucket — with one moving part instead of seven.

**`config/HttpClientConfig.java`** — builds the `RestClient` a primary uses
to push replicated copies to its replicas, and the bounded `ExecutorService`
(4 threads) that replication tasks run on. The one line that matters most
here: `factory.setBufferRequestBody(false)` on the underlying
`SimpleClientHttpRequestFactory`. Spring's default HTTP client behavior
buffers a request body fully before sending it; turning that off makes it
stream the body straight through via `HttpURLConnection`'s
chunked/fixed-length streaming mode — which is *why* replicating a
multi-gigabyte video to a replica doesn't require holding it whole in
memory on the primary.

**`service/ChunkStorageService.java`** — the core of the node. Walk through
`store()` if asked to explain one method in depth:
1. Validate the content type against the allow-list; default a missing one
   to `application/octet-stream`.
2. Loop: read up to `chunkSizeBytes` bytes from the incoming stream via a
   `readFully` helper (`InputStream.read()` can return *fewer* bytes than
   requested even mid-stream — this loops until the buffer is full or the
   stream ends, which is what guarantees chunk boundaries land exactly on
   multiples of `chunkSize`, except for the final short chunk).
2. Track `totalSize` as it accumulates and abort with `ObjectTooLargeException`
   the moment it exceeds `maxObjectSizeBytes` — checked incrementally, so an
   oversized upload is rejected as soon as it's detected, not after fully
   receiving it.
3. Write each chunk straight to MinIO as its own object
   (`{objectId}/chunks/{index}`) via `PutObjectArgs`, with the exact known
   length (no buffering, no multipart-upload complexity needed since each
   chunk's size is known upfront).
4. Handle the empty-upload edge case: if the loop never wrote a chunk (zero
   bytes received), write exactly one empty chunk so the object still
   "exists" and downloads cleanly as zero bytes instead of having zero
   chunks (which would break the gateway's chunk-index math).
5. Serialize an `ObjectMetadataDto` and write it as `{objectId}/meta.json`.

Also on this class: `getChunk`/`getMetadata` (translate MinIO's
`ErrorResponseException` with code `NoSuchKey` into `NoSuchElementException`
so the exception handler can map it to a clean `404`), `delete` (confirms
the object exists first via `getMetadata`, so deleting something that never
existed also 404s instead of silently no-op'ing, then lists and bulk-removes
every key under that object's prefix), and `listObjectIds` (lists the
bucket non-recursively, which — because MinIO simulates a hierarchy via `/`
delimiters — returns each object id as a "directory" without needing to
enumerate every chunk).

**`service/ChunkedObjectInputStream.java`** — a small, package-private
`InputStream` that lazily concatenates an object's chunks, fetching each one
fresh from MinIO only when the previous one is exhausted. Its whole purpose:
let `ReplicationService` re-stream an already-stored object's bytes to a
replica **without ever holding more than one chunk in memory at a time**,
even though from the replica's point of view it just looks like one
continuous HTTP request body.

**`service/ReplicationService.java`** — for each of a primary's configured
replica targets, submits an async task (on the bounded executor from
`HttpClientConfig`) that opens a `ChunkedObjectInputStream` for the object
and `PUT`s it to that replica's own ingest endpoint, with up to
`replicationMaxRetries + 1` attempts. By default this is **fire-and-forget**
(`synchronousReplication: false`) — the method returns immediately after
scheduling the tasks; set the flag to `true` to instead block until every
replica has acknowledged the copy (trading upload latency for a stronger
consistency guarantee). Also has `delete()`, which best-effort cascades a
delete to every replica — one replica being unreachable logs a warning but
doesn't fail the others.

**`service/{NodeStorageException, ObjectTooLargeException,
UnsupportedContentTypeException}.java`** — three small unchecked exception
types. `NodeStorageException` wraps the zoo of checked exceptions the MinIO
SDK throws (as of the version used here, actually just one unified
`MinioException` — but the wrapper still exists so callers deal in
unchecked exceptions). The other two carry semantic meaning the exception
handler maps to specific HTTP statuses (`413`, `415`).

**`controller/NodeStorageController.java`** — the node's *internal* API
(`/internal/objects/**` — "internal" by network topology, not by any auth
mechanism: in Docker Compose, only the gateway's port is published to the
host, so nodes are simply unreachable from outside the compose network).
The one method worth explaining in detail: `store()` takes an injected raw
`HttpServletRequest` and calls `request.getInputStream()` directly, rather
than declaring `@RequestBody byte[] data`. That's the deliberate choice that
makes ingest truly streaming on the node side too — `@RequestBody` would
make Spring's message converters buffer the whole body into memory first;
reading the servlet's raw input stream doesn't. `chunk()` similarly returns
an `InputStreamResource` wrapping MinIO's own response stream, and also
forwards MinIO's `Content-Length` header through so the gateway knows
exactly how many bytes to expect per chunk.

**`controller/NodeExceptionHandler.java`** — `@RestControllerAdvice`
mapping `NoSuchElementException → 404`, `ObjectTooLargeException → 413`,
`UnsupportedContentTypeException → 415`, and any other
`NodeStorageException → 500` (logged with a full stack trace server-side,
but a generic message returned to the caller — don't leak internals).

**`application.yml`** — defaults for a single standalone node (chunk size 1
MiB, max object size 5 GiB, MinIO connection pointing at `localhost:9000`).
**`application-primary-0.yml`, `application-primary-1.yml`,
`application-primary-0-replica-0.yml`, ...** (six files total) — one Spring
profile per node in the default topology. Each just overrides `node.id`,
`node.role`, and (for primaries) `node.replicas`. **Why profiles instead of
environment variables for this**: Spring's relaxed env-var binding *can*
express indexed lists (`HEIMDALL_NODE_REPLICAS_0_BASE_URL=...`) but it's
fragile and easy to get subtly wrong; a YAML profile file expresses the same
nested list structure directly and unambiguously. Docker Compose just sets
`SPRING_PROFILES_ACTIVE=primary-0` (etc.) per container — same jar, six
different identities.

**`Dockerfile`** — multi-stage: a `maven:3.9-eclipse-temurin-21` stage
builds the *whole reactor* (`-pl storage-node -am`, i.e. "this module and
everything it depends on" — needed because `storage-node` depends on
`common`, which isn't published anywhere else to pull from), then only the
resulting jar is copied into a slim `eclipse-temurin:21-jre-jammy` runtime
image, run as a non-root user.

### 5.3 `gateway` module — the public API

**`gateway/pom.xml`** — adds `springdoc-openapi-starter-webmvc-ui` (Swagger
UI) on top of the same web/validation/actuator stack as the node.

**`config/ClusterProperties.java`** — the gateway's view of the cluster
topology: `virtualNodes` plus a list of `PrimaryConfig { id, baseUrl,
replicas: [ReplicaConfig { id, baseUrl }] }`. **This is the gateway's
service registry**, and it's intentionally *static config*, not dynamic
discovery (Eureka/Consul). That's a deliberate scope trade-off worth being
upfront about in an interview: static config matched to Docker Compose's
built-in DNS gets you real, separate processes making real HTTP calls
without needing to stand up and operate a discovery service too. The
honest cost: adding or removing a node means a gateway config change and
restart, not a live join. If asked "how would you make this production
grade," this is the first thing to name.

**`config/GatewayProperties.java`** — the gateway's own tunables: `apiKey`,
`defaultReadPercent`, `maxFetchAttempts` (how many candidates a chunk fetch
will try before giving up), `fetchPoolSize` (size of the concurrent-fetch
thread pool), `nodeRequestTimeoutMs`.

**`config/RingConfig.java`** — builds two beans from `ClusterProperties` at
startup: a flat `List<PrimaryNode>` (used anywhere the gateway needs to
*enumerate* every primary, like the cluster-wide object listing) and the
`ConsistentHashRing<PrimaryNode>` built from that same list (used for
*routing* a key to its owner). Splitting these into two beans instead of
one avoids constructing the primary list twice.

**`config/HttpClientConfig.java`** — the gateway's equivalent of the node's
same-named class: a streaming-capable `RestClient` (again,
`setBufferRequestBody(false)`, because the gateway also forwards upload
bodies straight through to a primary without buffering) and a bounded
`ExecutorService` sized by `fetchPoolSize`, used to fetch multiple chunks
from multiple replicas concurrently.

**`config/OpenApiConfig.java`** — a small `OpenAPI` bean giving Swagger UI a
title/description and registering the `X-API-Key` header as a documented
security scheme.

**`model/PrimaryNode.java` / `ReplicaNode.java`** — plain records
(`id`, `baseUrl`, and — on `PrimaryNode` — its `replicas` list). These are
what actually populate the ring (`T` = `PrimaryNode`); unlike the original
single-JVM demo's `PrimaryServer`/`ReplicaServer` classes (which *held* data
directly), these just carry *where to call*, since the data lives in a
separate process now.

**`service/LoadBalancerService.java`** — the replica-selection algorithm,
essentially unchanged from the original demo's logic (proof that the
*algorithm* was always the interesting part, not the plumbing around it):
copy the primary's replica list (so shuffling never mutates the primary's
real list), shuffle it, clamp the requested percentage into `[1, 100]`,
keep `ceil(count × percent / 100)` of them (floored at 1, so a very low
percentage never selects zero replicas). Shuffling means repeated requests
for the same object spread across different replicas over time rather than
always hammering the same subset — a simple stand-in for real
load-balancing.

**`service/NodeClient.java`** — the only class that actually issues HTTP
calls to a node, and the place all the checked-to-unchecked exception
translation happens: a `404` becomes `NoSuchElementException`, any other
`RestClientException` (timeout, connection refused, 5xx) becomes
`NodeUnavailableException`, and a `4xx` from a node during upload (say, a
`413`/`415`) gets its message parsed out of the JSON body and re-thrown as
`UpstreamRejectedException` carrying the original status — so a rejected
upload surfaces the *node's actual reason* to the gateway's caller instead
of a generic error.

**`service/ObjectGatewayService.java`** — the write path: resolve the
owning primary via `LoadBalancerService`, forward the upload straight to it
via `NodeClient.store(...)`. Also `delete()` (same resolve-then-forward
pattern) and `listAll()` (calls every primary's list endpoint and unions
the results — a primary that's unreachable is skipped with a warning
logged, not treated as fatal for the whole listing).

**`service/StreamingOrchestratorService.java`** — the most involved class in
the project; walk through this one carefully if asked to pick one file to
explain end-to-end:
- `prepare(objectId, readPercent)` resolves the primary, asks
  `LoadBalancerService` for the selected replicas, builds the **candidate
  order** described in §4.4 (`selected replicas → primary's other replicas
  → primary itself`), and fetches metadata by trying candidates in that
  order until one succeeds (`fetchMetaWithFailover`) — this is the first
  place failover actually happens, before any chunk is even considered.
- `stream(prepared, start, end, out)` converts the requested byte range
  into a chunk range (`start/chunkSize` to `end/chunkSize`), and for *each*
  chunk index, submits a `CompletableFuture.supplyAsync` on the bounded pool
  that calls `fetchChunkWithFailover` — same candidate-list-with-failover
  idea, but per chunk, starting from a round-robin offset
  (`chunkIndex % selectedReplicaCount`) so different chunks default to
  different replicas.
- The reassembly trick: futures are created and stored **in chunk-index
  order**, but they *complete* in whatever order the network returns them.
  Iterating the futures list in order and calling `.join()` on each
  (which blocks only until *that* future is done, not the others) means
  bytes are written to the output stream in the correct order regardless of
  which replica actually answered first — concurrency in execution,
  determinism in assembly.
- Byte-range trimming: for each fetched chunk, compute
  `chunkStartOffset = chunkIndex * chunkSize`, then `from = max(0, start -
  chunkStartOffset)` and `to = min(chunk.length, end - chunkStartOffset +
  1)` — this is what turns "the two chunks that overlap this range" into
  "exactly the bytes that were requested," trimming the first and last
  chunk down as needed.
- `CompletionException` unwrapping: `CompletableFuture.join()` always wraps
  an exceptionally-completed future's cause in `CompletionException`, even
  for unchecked exceptions — `stream()` catches that and rethrows the real
  cause (`NoSuchElementException`/`NodeUnavailableException`) so the
  exception handler still sees the type it expects.

**`service/{NodeUnavailableException, UpstreamRejectedException,
RangeNotSatisfiableException}.java`** — three small typed exceptions the
`GatewayExceptionHandler` maps to specific statuses (`503`, the node's
original status, and `416` respectively).

**`dto/UploadResponseDto.java`** — the JSON shape returned from a successful
upload (`objectId`, `primaryId`, `contentType`, `totalSize`, `chunkCount`) —
includes `primaryId` specifically so a caller/operator can see *which* node
took the write, useful for debugging routing.

**`controller/ObjectController.java`** — the public surface. Two upload
methods dispatched by Spring's `consumes` matching: `uploadMultipart`
(`consumes = MULTIPART_FORM_DATA`, for browser `<form>` uploads) and
`uploadRaw` (no `consumes` restriction, matches everything else — a plain
`curl --data-binary` PUT-style body, which is also the more efficient path
since it skips the servlet container's multipart spooling-to-disk step
entirely). `download()` is where HTTP Range parsing happens, using Spring's
own `HttpRange.parseRanges(...)` (rather than hand-rolling RFC 7233 parsing)
— only the *first* range of a request is honored (documented limitation:
multi-range responses need a `multipart/byteranges` body, which wasn't
worth the complexity here), a malformed range header falls back to a full
`200` response (per spec), and an unsatisfiable one throws
`RangeNotSatisfiableException` for a clean `416`. The response itself is a
`StreamingResponseBody` — the actual byte-writing happens on a separate
async-dispatch thread managed by Spring, not the request-handling thread, so
a slow multi-chunk fetch doesn't tie up a Tomcat worker for its whole
duration.

**`controller/ClusterController.java`** — two read-only debug endpoints:
`/objects/{id}/replicas` (which primary owns this key, and which replicas
would be selected at a given percentage — works even for an object that was
never uploaded, since it's pure ring/selection logic) and `/cluster` (the
full topology the gateway booted with).

**`controller/GatewayExceptionHandler.java`** — maps every exception type
the services can throw to a status: `NoSuchElementException → 404`,
`IllegalStateException → 409` (an empty ring, shouldn't normally happen),
`UpstreamRejectedException →` whatever status the node originally returned,
`NodeUnavailableException → 503` (logged with the underlying cause — a
detail added after live-testing surfaced that the log line wasn't showing
*why* a node was unreachable), `RangeNotSatisfiableException → 416` (with
the required `Content-Range: bytes */totalSize` header), Spring's own
`MaxUploadSizeExceededException → 413`, and a catch-all `Exception → 500`
(full details logged server-side, generic message to the client). **One
important caveat to be able to state clearly**: none of this applies once a
streaming download's headers are already sent — a failure mid-stream just
truncates the connection, because you can't retroactively change an
HTTP status after committing it. This is inherent to any true streaming
API, not a gap in this exception handler specifically.

**`security/ApiKeyFilter.java`** — a plain `OncePerRequestFilter` (not full
Spring Security — deliberately, to avoid pulling in a login-page/session
framework for what's really "check one header on two HTTP methods").
Requires a valid `X-API-Key` header only on `POST`/`DELETE` to `/objects/**`
— reads stay open, the same way a CDN or public streaming endpoint
typically is: anyone can watch, only authorized callers can publish or
remove content. The comparison uses a constant-time equality check (XOR-and
-accumulate over every character rather than short-circuiting
`String.equals`) specifically to avoid a timing side-channel that could let
an attacker infer the correct key one character at a time from response
latency.

**`application.yml`** — local/dev defaults: an embedded cluster topology
pointing at `localhost:8081`–`8086` (matching the multi-JVM local run
documented in the README), multipart upload limits (5 GB), Swagger UI path.
**`application-docker.yml`** — activated via the `docker` Spring profile;
overrides just the cluster topology's `baseUrl`s to Docker Compose service
hostnames (`http://primary-0:8081`, etc.) instead of `localhost:<port>`,
since each node is its own container listening on the same internal port.

**`Dockerfile`** — same multi-stage pattern as the node's.

**`service/LoadBalancerServiceTest.java`** — pure-logic unit tests: routing
determinism, percentage-based selection count (`ceil` math), clamping
out-of-range percentages, and the empty-replica-list edge case.

**`service/StreamingOrchestratorServiceTest.java`** — uses Mockito to mock
`NodeClient` so it can test the orchestration logic in isolation from real
HTTP. Deliberately sets up the test fixture with **exactly one replica**
(not two) — worth explaining if asked about test design: with two replicas,
`selectReadReplicas`'s internal shuffle makes the candidate order
non-deterministic, which under Mockito's *strict* stubbing mode
(`UnnecessaryStubbingException`) can make a test intermittently fail
depending on which replica the shuffle happens to try first and therefore
which of two configured stubs never gets called. Reducing to one replica +
the primary as fallback makes the candidate order deterministic (`[replica,
primary]`, always), which is what makes tests like "fails over from the
replica to the primary when the replica is down" reliably reproducible
instead of flaky. This was found and fixed by actually running the suite
multiple times in a row during development, not by inspection.

### 5.4 Root-level files

**`pom.xml`** — the multi-module parent. Doesn't extend
`spring-boot-starter-parent` directly (a project can only have one parent,
and this one needs to *be* the parent of three children); instead it
imports `spring-boot-dependencies` as a BOM inside `dependencyManagement`,
which achieves the same "all Spring Boot artifact versions agree with each
other" effect. Also centralizes the `okhttp-jvm` override mentioned above
and pins `maven-surefire-plugin` to a version with solid JUnit 5 support.

**`docker-compose.yml`** — one MinIO service, six storage-node services
(built from a shared YAML anchor `&node-build` so each only needs to
override its `SPRING_PROFILES_ACTIVE`), and the gateway. `docker compose
config` (which validates/renders the final merged config without needing a
running daemon) was used during development to confirm the anchors, env var
merging, and service dependency graph all resolve correctly before ever
running the daemon.

**`.gitignore` / `.dockerignore`** — standard hygiene: build output and IDE
files don't get committed or shipped into Docker build contexts.

**`README.md`** — the project's own front door: quickstart (Docker Compose,
and the exact multi-JVM local commands used to verify the project by hand),
an API reference table, a condensed version of the design-decisions
discussion in §7 below, and a "what changed from the demo" comparison table.

---

## 6. End-to-end request traces

### Upload: `POST /objects/my-video`
```
ObjectController.uploadRaw(objectId, request)
  -> ObjectGatewayService.upload(objectId, inputStream, contentLength, contentType, filename)
       -> LoadBalancerService.resolvePrimary(objectId)
            -> ConsistentHashRing.getMemberFor(objectId)   [CRC32 hash + ceilingEntry lookup]
       -> NodeClient.store(primary.baseUrl(), ...)          [real HTTP PUT, streamed body]
            ↓ (separate process)
            NodeStorageController.store(objectId, request)
              -> ChunkStorageService.store(...)
                   -> loop: read chunkSize bytes -> MinIO putObject   [repeats per chunk]
                   -> MinIO putObject (meta.json)
              -> ReplicationService.replicate(meta)          [async, fire-and-forget]
                   -> for each replica: ChunkedObjectInputStream + HTTP PUT
                        ↓ (separate process, separate call)
                        NodeStorageController.store(...) on the replica
                          -> ChunkStorageService.store(...)   [same code path, role=REPLICA so no further fan-out]
```

### Download: `GET /objects/my-video` with `Range: bytes=1000000-1999999`
```
ObjectController.download(objectId, readPercent, rangeHeader)
  -> StreamingOrchestratorService.prepare(objectId, readPercent)
       -> LoadBalancerService.resolvePrimary + selectReadReplicas
       -> fetchMetaWithFailover(candidates, objectId)         [tries replicas, then primary]
  -> HttpRange.parseRanges(rangeHeader) -> start=1000000, end=1999999
  -> StreamingResponseBody (runs on Spring's async dispatch thread):
       StreamingOrchestratorService.stream(prepared, start, end, out)
         -> compute firstChunk..lastChunk from start/end and chunkSize
         -> for each chunk index: CompletableFuture.supplyAsync(fetchChunkWithFailover, pool)
              -> NodeClient.fetchChunk(candidateBaseUrl, objectId, index)   [real HTTP GET]
         -> join() futures in order, trim first/last chunk to exact byte range, write to out
```

### Startup (once, automatically, per service)
```
Gateway:
  RingConfig.primaryNodes(properties)   [build List<PrimaryNode> from static YAML config]
  RingConfig.primaryRing(properties, primaryNodes)  [register each on the ConsistentHashRing]

Storage node:
  MinioConfig.BucketInitializer.ensureBucketExists()  [@PostConstruct: create this node's bucket if missing]
  NodeApplication.startupBanner   [CommandLineRunner: logs role/replicas for operator visibility]
```

---

## 7. Design decisions and trade-offs (be ready to defend each one)

| Decision | Why | The honest cost |
|---|---|---|
| Static cluster config, not Eureka/Consul | One compose file, no extra discovery infra to run/operate | Adding/removing a node needs a gateway config change + restart, not a live join |
| One MinIO deployment, per-node buckets | Genuinely partitioned data with one moving part instead of many MinIO instances | All nodes share one storage backend's blast radius (if MinIO itself is down, nothing works) |
| Async replication by default | Fast upload acks; matches how real distributed systems define "eventually consistent" | A replica can briefly lack data right after upload — mitigated by, not eliminated by, gateway failover |
| Fixed-size chunking (not content-aware) | Simple, and the point being demonstrated is parallel multi-source *fetching*, not smart segmentation | Chunk boundaries aren't aligned to anything meaningful in the video itself (e.g. keyframes) |
| Single-range HTTP Range support | Covers real player/browser behavior; multi-range needs a `multipart/byteranges` response body | A client explicitly requesting multiple ranges in one call only gets the first honored |
| API key on writes, open reads | Matches a CDN/streaming-endpoint security model; simple to explain and implement | Not real user-level auth/authorization — anyone with the key can write/delete anything |
| No durable replication outbox | Keeps the replication path simple to read/test | If a primary crashes *during* a replication push, that replica can be left behind with no automatic re-drive |

---

## 8. Likely interview questions, and how to answer them

**"Walk me through what happens when I upload a video."**
Use the upload trace in §6. Emphasize: hash the key once to find the owner,
stream (don't buffer) the body into fixed-size chunks straight to object
storage, then asynchronously fan out to replicas over real HTTP.

**"What happens if a replica is down when someone tries to watch a video?"**
Explain the candidate-list failover from §4.4 concretely: it's tried in
order (other selected replicas, then the primary), and only if literally
every candidate fails does the client see an error (`503`). Mention that you
verified this by hand — killing a primary process outright and confirming
downloads still succeeded, served entirely from replicas.

**"Why consistent hashing instead of just `hash(id) % numServers`?"**
Give the "adding/removing a server shouldn't reshuffle everything" argument
from §4.1, and mention virtual nodes as the fix for uneven distribution at
small server counts.

**"How would you scale this to handle way more traffic?"**
More primaries and replicas is nearly free to add (the ring already
supports adding members without disrupting most existing keys); the
current bottleneck to name honestly is the gateway itself, which is a
single point of routing — you'd run several gateway instances behind a
plain load balancer (they're stateless — the ring is rebuilt from config on
each one, no shared mutable state between them) and, longer-term, replace
static cluster config with real service discovery so nodes could scale
without a gateway redeploy.

**"What would you change if you had another week?"**
Reference §9 directly: dynamic service discovery, adaptive-bitrate
streaming (HLS/DASH instead of Range-based progressive download), and a
durable replication outbox/retry queue instead of best-effort async pushes.

**"What's the hardest bug you hit building this?"**
The `okhttp`/MinIO Maven resolution issue (§5.2) is a good, concrete,
technically specific story — not a logic bug, but a real dependency
resolution problem you diagnosed by inspecting jar contents directly
(`unzip -l`) rather than guessing.

**"How did you verify this actually works, not just compiles?"**
You couldn't get Docker daemon access in the dev sandbox, so you ran all
six nodes plus MinIO plus the gateway as plain local JVM processes on
different ports (matching the exact config the README documents as the
no-Docker path), then exercised the real API with `curl`: uploaded a file,
confirmed byte-for-byte identical full and partial (`Range`) downloads,
confirmed chunks actually replicated to replica nodes (checked their
internal API directly), and confirmed downloads still succeeded after
killing the primary process entirely — proving failover, not just the
happy path.

---

## 9. Known limitations (good to state proactively — shows maturity)

- Static topology, not dynamic discovery (§7).
- Best-effort async replication with retries, not a durable outbox — a
  primary crash mid-replication can leave a replica behind with nothing to
  re-drive it later.
- Only the first HTTP `Range` in a request is honored; true multi-range
  responses aren't implemented.
- A streaming response's HTTP status can't be changed after it's committed,
  so a failure partway through a large download just truncates the
  connection rather than surfacing a clean error.
- API-key auth is a single shared secret for all writes — no per-user
  identity, scoping, or rotation story.
- Chunk boundaries are size-based, not content-aware (not aligned to video
  keyframes), so this is progressive-download streaming, not true
  adaptive-bitrate streaming (HLS/DASH).
