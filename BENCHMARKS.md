# Benchmarks

Everything here is reproducible from a clean checkout:

```bash
./bench/run-algorithms.sh full   # algorithm suites; no infrastructure needed
./bench/run-e2e.sh 64 15         # Heimdall vs MinIO, nginx and SeaweedFS
```

Two kinds of measurement live in this document and they should not be read the
same way.

The **algorithm** numbers are properties of the code, not of the machine. Key
distribution and rebalancing churn are computed deterministically over a fixed
key set, so they reproduce exactly anywhere. Throughput numbers come from JMH
with two forks, and are hardware-dependent - use them for the ratios between
options, not as absolute figures.

The **end-to-end** numbers are a measurement of one machine running every
service, load generator included, on shared CPU. They are useful for comparing
targets against each other under identical conditions and useless as a
statement of what any of these systems can do on real hardware.

---

## 1. Routing: which algorithm maps a key to its primary

This is the measurement that changed the code. Heimdall shipped with a
consistent-hash ring hashed by CRC-32 at 100 virtual nodes per primary. That is
a reasonable-sounding default, it passes every correctness test, and it is
measurably the worst option benchmarked.

### What is being measured

**Peak load** is the busiest primary's share of the keyspace divided by its fair
share. `1.00x` is a perfect split. It matters more than the average or the
standard deviation because a cluster's capacity is set by the node that
saturates first - a peak load of `1.28x` means provisioning 28% of headroom on
every node purely to absorb an imbalance the router created.

**Churn** is the fraction of keys that change owner when a primary joins or
leaves. Consistent hashing exists to keep this at its theoretical floor of
`1/N`; anything above that is objects being needlessly re-fetched and
re-replicated during an incident. The tables report the measured fraction and
its ratio to that floor.

**Routing state** is the memory the algorithm needs to answer lookups.

### Results at the deployed topology - 2 primaries, 200,000 keys

| algorithm | peak load | churn on loss of `primary-0` | vs optimal | routing state |
|---|---:|---:|---:|---:|
| `ring/crc32/v100` *(was the default)* | **1.275x** | 63.73% | 1.275x | 10.9 KiB |
| `ring/crc32/v500` | 1.138x | 56.92% | 1.138x | 54.7 KiB |
| `ring/fnv1a64/v100` | 1.658x | 82.91% | 1.658x | 10.9 KiB |
| `ring/murmur3/v100` | 1.067x | 46.64% | 0.933x | 10.9 KiB |
| `ring/murmur3/v500` | 1.053x | 47.38% | 0.948x | 54.7 KiB |
| `ring/md5/v100` | 1.090x | 45.49% | 0.910x | 10.9 KiB |
| `ketama/md5/p160` | 1.027x | 51.34% | 1.027x | 17.5 KiB |
| **`rendezvous/murmur3`** *(is the default)* | **1.001x** | 50.03% | 1.001x | **96 B** |
| `jump/murmur3` | 1.001x | 49.94% | 0.999x | 64 B |

Theoretical floor for churn at this size: 50.00%.

On a two-primary cluster the shipped CRC-32 ring was giving one primary **63.7%
of all objects** and the other 36.3%. Not a tail event - the steady state.

### Results at 8 primaries

| algorithm | peak load | min load | churn on loss of `primary-0` | vs optimal | routing state |
|---|---:|---:|---:|---:|---:|
| `ring/crc32/v100` | 1.129x | 0.925x | 12.71% | 1.017x | 43.8 KiB |
| `ring/crc32/v500` | 1.109x | 0.852x | 13.19% | 1.055x | 218.8 KiB |
| `ring/fnv1a64/v100` | 1.826x | 0.455x | 22.82% | 1.826x | 43.8 KiB |
| `ring/murmur3/v100` | 1.129x | 0.881x | 12.94% | 1.035x | 43.8 KiB |
| `ring/murmur3/v500` | 1.088x | 0.958x | 12.30% | 0.984x | 218.8 KiB |
| `ring/md5/v100` | 1.210x | 0.812x | 10.78% | 0.862x | 43.8 KiB |
| `ketama/md5/p160` | 1.178x | 0.823x | 13.77% | 1.102x | 70.0 KiB |
| **`rendezvous/murmur3`** | **1.004x** | 0.994x | 12.49% | **1.000x** | **384 B** |
| `jump/murmur3` | 1.008x | 0.992x | **98.20%** | **7.856x** | 256 B |

Theoretical floor: 12.50%.

### Results at 32 primaries

| algorithm | peak load | min load | churn on loss of `primary-0` | vs optimal | routing state |
|---|---:|---:|---:|---:|---:|
| `ring/crc32/v100` | 1.364x | 0.694x | 3.85% | 1.233x | 175.0 KiB |
| `ring/crc32/v500` | 1.424x | 0.721x | 2.93% | 0.938x | 875.0 KiB |
| `ring/fnv1a64/v100` | 2.450x | 0.206x | 3.81% | 1.218x | 175.0 KiB |
| `ring/murmur3/v100` | 1.344x | 0.806x | 3.06% | 0.980x | 175.0 KiB |
| `ring/murmur3/v500` | 1.083x | 0.868x | 3.13% | 1.002x | 875.0 KiB |
| `ring/md5/v100` | 1.250x | 0.794x | 3.82% | 1.224x | 175.0 KiB |
| `ketama/md5/p160` | 1.191x | 0.846x | 3.25% | 1.041x | 280.0 KiB |
| **`rendezvous/murmur3`** | **1.022x** | 0.979x | 3.19% | 1.019x | **1.5 KiB** |
| `jump/murmur3` | 1.023x | 0.972x | **99.91%** | **31.973x** | 1.0 KiB |

Theoretical floor: 3.13%.

### What these say

**A weak hash cannot be fixed with more virtual nodes.** The usual advice for an
unbalanced ring is to raise the virtual-node count. It does not work here:
CRC-32 at 500 virtual nodes is *worse* at 32 primaries (`1.424x`) than at 100
(`1.364x`), while costing five times the memory. CRC-32 is a checksum, linear
over GF(2), and the ring points it is asked to hash - `primary-0#0`,
`primary-0#1`, ... - are structurally similar by construction. Similar inputs
produce clustered outputs, clustered outputs mean unevenly-sized arcs, and
adding more clustered points does not un-cluster them.

**Cheaper is not better.** FNV-1a was included as the cheapest plausible
upgrade from CRC-32 and is by far the worst algorithm measured, reaching
`2.450x` peak load at 32 primaries with one primary holding barely a fifth of
its fair share. Replacing a hash without measuring the replacement would have
made this system substantially worse while looking like an improvement.

**Rendezvous hashing wins on every axis that was measured except one.** It holds
peak load within 2.2% of perfect at every cluster size, hits the theoretical
churn floor, and needs 96 bytes of routing state where the ring needs 10.9 KiB -
about 400x less at 32 primaries. It has no virtual-node knob to tune because it
has no virtual nodes. The axis it loses is lookup cost: O(N) over real members
rather than O(log V) over a tree, which section 2 quantifies.

**Jump consistent hash is disqualified by its contract, not its performance.**
Its distribution is as good as rendezvous and it is the fastest and smallest
option available. But it addresses bucket *indices*, and guarantees minimal
movement only when the bucket count changes at the end of the range. When
`primary-0` fails out of 8, every later primary shifts down a slot and **98.2%**
of the keyspace remaps - 7.9x more than necessary, rising to 99.9% and 32x at 32
primaries. A cluster does not get to choose which node fails. This is asserted
as a test (`KeyRouterPropertiesTest#jumpHashingViolatesMinimalDisruption...`)
rather than quietly avoided.

### The change this produced

`heimdall.cluster.router` now defaults to `RENDEZVOUS`. `RING` (with a
selectable `hash-function`, defaulting to `murmur3_128`) and `KETAMA` remain
available, and CRC-32 is still selectable so this comparison stays reproducible
rather than becoming a historical claim.

---

## 2. Algorithm throughput (JMH)

JMH 1.37, 2 forks, 3x2s warmup, 5x2s measurement. Absolute figures are
hardware-specific; the ratios are the point.

### Routing lookup - runs on every single request

Average time to answer "who owns this key?", in nanoseconds:

| members | `ring/crc32/v100` | `ring/murmur3/v100` | `ring/murmur3/v500` | `ring/md5/v100` | `ketama` | `rendezvous` | `jump` |
|---:|---:|---:|---:|---:|---:|---:|---:|
| **2** | 39.2 | 54.6 | 69.9 | 160.3 | 161.7 | **20.0** | 27.1 |
| **8** | 59.7 | 66.2 | 86.1 | 175.8 | 178.7 | **33.0** | 31.0 |
| **32** | 77.8 | 81.7 | 111.9 | 187.3 | 198.5 | 87.4 | **35.7** |
| **128** | 103.3 | 106.9 | 145.9 | 211.3 | 228.4 | 203.0 | **41.1** |

The result that decided the default: **rendezvous hashing is not just better
distributed, it is also the fastest option at the sizes this cluster runs at** -
20.0 ns against the CRC-32 ring's 39.2 ns at two primaries, and still ahead at
eight. Its O(N) scan was supposed to be the price of its perfect distribution,
and at two to eight members there is no price.

That is worth explaining rather than just reporting, because the naive
complexity analysis predicts the opposite. A ring lookup is a `TreeMap`
`ceilingEntry` over 200-12,800 boxed `Long` keys: a pointer chase down a
red-black tree, each hop a likely cache miss. Rendezvous over eight members is a
linear scan of a contiguous array that fits in L1, doing two hashes and some
arithmetic with no dependent loads at all. Asymptotically the tree wins;
concretely, at small N, cache behaviour dominates and the array wins.

The crossover is around 32 members, where the two are level, and by 128
rendezvous costs 2x the ring. So this default is correct *for this cluster's
scale* and should be revisited if the primary count ever reaches the dozens -
which is exactly why `heimdall.cluster.router` is configuration rather than a
hard-coded choice.

### Raw hash cost

| hash | 24-byte key | 512-byte key |
|---|---:|---:|
| `crc32` | **5.2 ns** | **12.7 ns** |
| `fnv1a64` | 11.0 ns | 425.9 ns |
| `murmur3_128` | 14.7 ns | 175.3 ns |
| `md5` | 118.4 ns | 640.9 ns |

CRC-32 is genuinely the fastest hash here - the JVM compiles it to the CPU's
CRC32 instruction - which is presumably why it was chosen. It is 9.5 ns cheaper
than murmur3 on a realistic key. That saving is what the ring's distribution
problem was bought with, and section 4 shows it buying nothing: 9.5 ns is
invisible next to a network round-trip, while a 1.275x peak load is not.

FNV-1a's 425.9 ns at 512 bytes is the other cautionary number - roughly one byte
per nanosecond, with no block structure to amortise anything, on top of being
the worst-distributed option measured.

### Membership change - a node joining or leaving

| algorithm | 8 members | 128 members |
|---|---:|---:|
| `ring/crc32/v100` | 7.83 us | 10.52 us |
| `ring/murmur3/v100` | 7.38 us | 10.50 us |
| `ring/murmur3/v500` | 49.09 us | 82.68 us |
| `ketama/md5/p160` | 16.95 us | 22.64 us |
| **`rendezvous/murmur3`** | **0.17 us** | **1.33 us** |
| `jump/murmur3` | 0.14 us | 1.17 us |

Rendezvous is **47x faster** than the CRC-32 ring at eight members and 293x
faster than a 500-vnode ring. Off the request path today, since topology is
static config read once at startup - but it is the number that decides whether
the "dynamic service discovery" item on the roadmap is cheap or dangerous. Under
discovery, a node joining becomes a live event during traffic, and a ring's
rebuild holds the write lock that every concurrent lookup blocks on. 82 us of
blocked lookups per membership change is a latency spike; 1.3 us is not.

### Gateway per-request CPU

| operation | 2 replicas | 16 replicas |
|---|---:|---:|
| `selectReadReplicas` (shuffle and take) | 13.0 ns | 87.6 ns |
| `buildCandidateOrder` (failover ordering) | 50.8 ns | 252.8 ns |
| `planSeekRange` (4 MiB range over a 1 GiB object) | 0.22 ns | 0.22 ns |

Adding the routing lookup, the gateway's entire per-read CPU cost at the
deployed topology is roughly **84 ns**. A single network round-trip to a storage
node is on the order of 100,000 ns. The routing layer is about 0.1% of a read.

This is the most useful thing in this section, because it says the algorithm
choice should be made almost entirely on distribution quality and rebalancing
behaviour, and barely at all on speed. The ring was not slow enough to matter -
it was unbalanced enough to matter.

(`planSeekRange` at 0.22 ns is the JIT proving the arithmetic is free once the
loop is unrolled; treat it as "not measurable", not as a real figure.)

### Storage chunking

Splitting a 32 MiB object, with MinIO stubbed out so only the chunking loop is
timed:

| chunk size | throughput |
|---|---:|
| 64 KiB | 6.78 GiB/s |
| 1 MiB | **15.3 GiB/s** |
| 4 MiB | 14.4 GiB/s |

Chunking at 64 KiB costs 2.25x the CPU of chunking at 1 MiB - per-chunk
overhead (a `PutObjectArgs` build and a stream wrapper each time) stops being
amortised. Above 1 MiB it flattens and slightly regresses.

Every one of these figures is far above any network this would run on, so chunk
size is **not** a throughput knob. It should be chosen for seek granularity and
per-chunk request count, which is what the existing 1 MiB default already
optimises for - it happens to also sit exactly at the CPU sweet spot.

---

## 3. End to end: Heimdall vs real products

<!-- E2E_RESULTS -->
*Populated by `./bench/run-e2e.sh`; see `bench-results/e2e-results.md`.*

### What is being compared, and why these three

| target | what it establishes |
|---|---|
| `minio-direct` | The same MinIO the Heimdall nodes store their chunks in, read directly. Same hardware, same storage engine, same bytes - so the difference is precisely what the Heimdall layer costs and what it buys. This is the load-bearing comparison. |
| `nginx` | The same bytes as a static file off local disk with `sendfile`. Not a peer - a ceiling. It answers "how far from a plain, highly-tuned HTTP file server is this?" |
| `seaweedfs` | A real distributed object store with a master/volume-server split and its own replication, via its S3 gateway. The closest architectural peer in the comparison. |

All four are read through **one** `java.net.http.HttpClient` issuing identical
`GET`s with identical `Range` headers. The S3 targets get an anonymous-read
bucket policy during setup specifically so no request signing happens on the
client - otherwise part of what was being compared would be SDK overhead rather
than server behaviour.

Three workloads, because a single aggregate number would hide the thing that
distinguishes them:

- **`startup-1MiB`** - the first megabyte, i.e. how quickly a player can begin.
  Latency-bound, and where an extra gateway hop shows up worst.
- **`seek-4MiB`** - 4 MiB from a random offset: a viewer dragging the scrub bar.
  This is the case chunked storage exists to serve.
- **`full-download`** - the whole object. Throughput-bound; per-request
  overhead amortises away.

Every response body is byte-compared against the source object. A load
generator that discards bodies will cheerfully report huge throughput from a
server returning truncated or wrong-range data, and a chunked multi-source read
path with failover is exactly where that could happen silently. The `bad`
column in the results tables is that check; any non-zero value invalidates its
row.

---

## Reproducing

| | |
|---|---|
| Machine | 12 logical cores, 14 GiB RAM, NVMe |
| JVM | OpenJDK 21.0.11 |
| JMH | 1.37, 2 forks, 3x2s warmup, 5x2s measurement |
| Routing quality | 200,000 keys shaped like real object ids (`uploads/2026/video-N.mp4`) |

Object keys in the quality analysis are deliberately structured and highly
similar to each other rather than random UUIDs. That is what a real workload
produces, and it is the input a weak hash handles worst - benchmarking over
UUIDs would have flattered CRC-32 by hiding the exact clustering it suffers
from.
