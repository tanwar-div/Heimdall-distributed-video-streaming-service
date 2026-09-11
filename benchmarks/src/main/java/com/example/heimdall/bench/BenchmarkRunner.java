package com.example.heimdall.bench;

import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.ChainedOptionsBuilder;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Entry point for the JMH suites.
 *
 * <pre>
 *   java -jar heimdall-benchmarks.jar [quick|full] [regex] [results.json]
 * </pre>
 *
 * <p>{@code quick} exists so a change to a benchmark can be validated in about
 * a minute; it is not statistically meaningful and the runner says so on the
 * way out. Published numbers come from {@code full}, which uses two forks so
 * that a single unlucky JIT compilation plan cannot masquerade as a result.
 */
public final class BenchmarkRunner {

    public static void main(String[] args) throws RunnerException, java.io.IOException {
        String mode = args.length > 0 ? args[0] : "full";
        String include = args.length > 1 ? args[1] : "com.example.heimdall.bench.*";
        String resultFile = args.length > 2 ? args[2] : null;

        boolean quick = "quick".equalsIgnoreCase(mode);

        ChainedOptionsBuilder options = new OptionsBuilder()
                .include(include)
                .shouldFailOnError(true)
                .shouldDoGC(true);

        if (quick) {
            options = options
                    .warmupIterations(1).warmupTime(TimeValue.milliseconds(500))
                    .measurementIterations(2).measurementTime(TimeValue.milliseconds(500))
                    .forks(1);
        }

        if (resultFile != null) {
            Path path = Path.of(resultFile);
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            options = options.resultFormat(ResultFormatType.JSON).result(resultFile);
        }

        new Runner(options.build()).run();

        if (quick) {
            System.err.println();
            System.err.println("!! quick mode: 1 fork, 2x500ms measurement. Smoke-test only -");
            System.err.println("!! do not quote these numbers. Re-run with 'full' to publish.");
        }
    }
}
