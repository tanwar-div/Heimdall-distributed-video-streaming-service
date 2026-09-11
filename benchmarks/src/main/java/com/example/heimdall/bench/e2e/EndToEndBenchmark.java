package com.example.heimdall.bench.e2e;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * The end-to-end comparison: Heimdall against the real products it would be
 * judged next to, serving identical bytes over identical HTTP requests.
 *
 * <pre>
 *   java -cp heimdall-benchmarks.jar com.example.heimdall.bench.e2e.EndToEndBenchmark \
 *        [objectSizeMiB] [measureSeconds] [outputFile.md]
 * </pre>
 *
 * <p>Targets that fail to provision or fail verification are reported as
 * unavailable and skipped rather than silently omitted, so a partial run is
 * obviously partial.
 */
public final class EndToEndBenchmark {

    private static final String OBJECT_ID = "bench-object.mp4";
    private static final int[] CONCURRENCIES = {1, 8, 32};

    public static void main(String[] args) throws Exception {
        int objectSizeMiB = args.length > 0 ? Integer.parseInt(args[0]) : 64;
        Duration measure = Duration.ofSeconds(args.length > 1 ? Integer.parseInt(args[1]) : 15);
        Path output = args.length > 2 ? Path.of(args[2]) : null;
        Duration warmup = Duration.ofSeconds(5);

        String gatewayUrl = env("HEIMDALL_URL", "http://localhost:8080");
        String apiKey = env("HEIMDALL_API_KEY", "changeme");
        String minioUrl = env("MINIO_URL", "http://localhost:9000");
        String minioAccessKey = env("MINIO_ACCESS_KEY", "heimdall");
        String minioSecretKey = env("MINIO_SECRET_KEY", "heimdall-secret");
        String nginxUrl = env("NGINX_URL", "http://localhost:8090");
        String seaweedUrl = env("SEAWEEDFS_URL", "http://localhost:8333");
        Path nginxDataDir = Path.of(env("NGINX_DATA_DIR", "bench-data"));

        byte[] payload = payload(objectSizeMiB * 1024 * 1024);
        System.err.printf("[setup] benchmark object: %d MiB%n", objectSizeMiB);

        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        TargetProvisioner provisioner = new TargetProvisioner(httpClient, Duration.ofMinutes(5));
        List<Target> targets = new ArrayList<>();
        targets.add(provisioner.heimdall(gatewayUrl, apiKey, OBJECT_ID, payload));
        targets.add(provisioner.minioDirect(minioUrl, minioAccessKey, minioSecretKey, "bench", OBJECT_ID, payload));
        targets.add(provisioner.nginx(nginxUrl, nginxDataDir, OBJECT_ID, payload));
        targets.add(provisioner.seaweedfs(seaweedUrl, "bench", OBJECT_ID, payload));

        LoadDriver driver = new LoadDriver(httpClient, payload, OBJECT_ID, Duration.ofMinutes(5));

        List<Target> usable = new ArrayList<>();
        StringBuilder skipped = new StringBuilder();
        for (Target target : targets) {
            if (!target.available()) {
                skipped.append("- `").append(target.name()).append("` - could not be provisioned; not running.\n");
                continue;
            }
            List<String> problems = driver.verify(target);
            if (problems.isEmpty()) {
                usable.add(target);
                System.err.printf("[verify] %-14s OK%n", target.name());
            } else {
                skipped.append("- `").append(target.name()).append("` - failed verification: ")
                        .append(String.join("; ", problems)).append("\n");
                System.err.printf("[verify] %-14s FAILED: %s%n", target.name(), problems);
            }
        }

        List<LoadDriver.Measurement> measurements = new ArrayList<>();
        for (Workload workload : Workload.values()) {
            for (int concurrency : CONCURRENCIES) {
                for (Target target : usable) {
                    System.err.printf("[run] %-14s %-16s c=%-3d ", target.name(), workload.label(), concurrency);
                    LoadDriver.Measurement m = driver.measure(target, workload, concurrency, warmup, measure);
                    measurements.add(m);
                    System.err.printf("%.1f MiB/s, %.0f ops/s, p99 %.1f ms%s%n",
                            m.throughputMiBPerSecond(), m.operationsPerSecond(),
                            Latencies.percentileMillis(m.totalSorted(), 99),
                            m.integrityFailures() > 0 ? "  !! " + m.integrityFailures() + " CORRUPT" : "");
                }
            }
        }

        String markdown = render(measurements, usable, skipped.toString(), objectSizeMiB, measure);
        System.out.print(markdown);
        if (output != null) {
            Files.createDirectories(output.toAbsolutePath().getParent());
            Files.writeString(output, markdown);
            System.err.println("wrote " + output);
        }
    }

    private static String render(List<LoadDriver.Measurement> measurements, List<Target> targets,
                                 String skipped, int objectSizeMiB, Duration measure) {
        StringBuilder out = new StringBuilder();
        out.append("<!-- generated by EndToEndBenchmark; do not edit by hand -->\n\n");
        out.append("Object: **").append(objectSizeMiB).append(" MiB**, ")
                .append(measure.toSeconds()).append("s measured per cell after a 5s warmup. ")
                .append("Every response body is byte-compared against the source object; ")
                .append("a non-zero \"bad\" column invalidates that row's numbers.\n\n");

        out.append("Targets:\n\n");
        for (Target target : targets) {
            out.append("- `").append(target.name()).append("` - ").append(target.description()).append("\n");
        }
        if (!skipped.isEmpty()) {
            out.append("\nNot measured:\n\n").append(skipped);
        }
        out.append("\n");

        for (Workload workload : Workload.values()) {
            out.append("### ").append(workload.label()).append("\n\n");
            out.append("| target | conc | MiB/s | ops/s | TTFB p50 | TTFB p99 | total p50 | total p99 | errors | bad |\n");
            out.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
            for (LoadDriver.Measurement m : measurements) {
                if (m.workload() != workload) {
                    continue;
                }
                out.append("| `").append(m.target()).append("` | ").append(m.concurrency()).append(" | ")
                        .append(f(m.throughputMiBPerSecond())).append(" | ")
                        .append(String.format(Locale.ROOT, "%.0f", m.operationsPerSecond())).append(" | ")
                        .append(f(Latencies.percentileMillis(m.ttfbSorted(), 50))).append(" | ")
                        .append(f(Latencies.percentileMillis(m.ttfbSorted(), 99))).append(" | ")
                        .append(f(Latencies.percentileMillis(m.totalSorted(), 50))).append(" | ")
                        .append(f(Latencies.percentileMillis(m.totalSorted(), 99))).append(" | ")
                        .append(m.errors()).append(" | ")
                        .append(m.integrityFailures()).append(" |\n");
            }
            out.append("\n");
        }
        return out.toString();
    }

    private static String f(double value) {
        return Double.isNaN(value) ? "-" : String.format(Locale.ROOT, "%.2f", value);
    }

    private static byte[] payload(int size) {
        // Incompressible: several of these systems will happily compress or
        // deduplicate a buffer of zeroes and report throughput that no real
        // video would ever see.
        byte[] data = new byte[size];
        new Random(20260909L).nextBytes(data);
        return data;
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
