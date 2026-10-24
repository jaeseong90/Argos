/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.load;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The demo load generator (image {@code argos-demo-load}, ADR-0003 C-33): fixed arrival rate
 * against a sample app, request mix of 17 §3.9 ({@code MIX=default}) or 18 §7.2 ({@code
 * MIX=bench}), progress line every report interval, JSON summary at the end. Own code on {@code
 * java.net.http} only (no third-party library). Exit codes: 0 done, 1 the unexpected-outcome gate
 * ({@code MAX_UNEXPECTED_RATE}) failed or the summary could not be written, 2 bad settings.
 */
public final class DemoLoadMain {

  private DemoLoadMain() {}

  public static void main(String[] args) {
    if (args.length == 1 && (args[0].equals("--help") || args[0].equals("-h"))) {
      System.out.print(LoadConfig.USAGE);
      return;
    }
    LoadConfig config;
    try {
      config = LoadConfig.from(System.getenv(), args);
    } catch (IllegalArgumentException e) {
      System.err.println(e.getMessage());
      System.exit(2);
      return;
    }
    int code = run(config, System.out);
    System.exit(code);
  }

  /** Runs until the configured duration has passed or the JVM is asked to stop. */
  static int run(LoadConfig config, PrintStream out) {
    String version = version();
    String userAgent = "argos-demo-load/" + version;
    LoadStats warmupStats = new LoadStats(config.mix());
    LoadStats stats = new LoadStats(config.mix());
    Progress progress = new Progress(out, config.warmup());
    CountDownLatch finished = new CountDownLatch(1);
    try (Transport transport =
            new JdkHttpTransport(
                config.target(), config.connectTimeout(), config.httpThreads(), userAgent);
        MachineSampler machine =
            MachineSampler.start(Duration.ofSeconds(1), OptionalLong.empty())) {
      FixedRateGenerator generator =
          new FixedRateGenerator(
              config, transport, TimeSource.SYSTEM, warmupStats, stats, progress);
      Thread hook =
          new Thread(
              () -> {
                generator.stop();
                try {
                  // Lets the main thread drain and write the summary (docker stop: 10 s).
                  if (!finished.await(8, TimeUnit.SECONDS)) {
                    System.err.println("[demo-load] stopped before the summary was written");
                  }
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              },
              "demo-load-shutdown");
      Runtime.getRuntime().addShutdownHook(hook);
      String startedAt = Instant.now().toString();
      out.printf(
          "[demo-load] %s -> %s rate=%s/s mix=%s duration=%s warmup=%s maxInFlight=%d"
              + " timeout=%dms%n",
          userAgent,
          config.target(),
          config.rate(),
          config.mix().name(),
          config.duration() == null ? "until stopped" : config.duration(),
          config.warmup(),
          config.maxInFlight(),
          config.requestTimeout().toMillis());
      try {
        FixedRateGenerator.Result result = generator.run();
        Map<String, Object> summary =
            Summary.of(
                config,
                version,
                startedAt,
                result,
                stats.snapshot(),
                warmupStats.snapshot(),
                machine.result());
        out.println(Summary.finalLine(summary));
        boolean written = write(config.summaryPath(), summary, out);
        return exitCode(config, summary, written, out);
      } finally {
        finished.countDown();
        removeHook(hook);
      }
    }
  }

  /** The hook is only needed while the generator runs; JVM shutdown already in progress is fine. */
  private static void removeHook(Thread hook) {
    try {
      Runtime.getRuntime().removeShutdownHook(hook);
    } catch (IllegalStateException e) {
      // Shutting down: the hook is running or about to run and waits for "finished".
    }
  }

  private static boolean write(Path path, Map<String, Object> summary, PrintStream out) {
    if (path == null) {
      return true;
    }
    try {
      Path parent = path.toAbsolutePath().getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.writeString(path, Json.write(summary), StandardCharsets.UTF_8);
      out.println("[demo-load] summary: " + path.toAbsolutePath());
      return true;
    } catch (IOException e) {
      System.err.println("[demo-load] cannot write the summary to " + path + ": " + e);
      return false;
    }
  }

  private static int exitCode(
      LoadConfig config, Map<String, Object> summary, boolean written, PrintStream out) {
    if (!written) {
      return 1;
    }
    if (Double.isNaN(config.maxUnexpectedRate())) {
      return 0;
    }
    Object rate = ((Map<?, ?>) summary.get("requests")).get("unexpectedRate");
    double unexpected = rate instanceof Double d ? d : Double.NaN;
    if (Double.isNaN(unexpected) || unexpected > config.maxUnexpectedRate()) {
      out.printf(
          "[demo-load] FAILED: unexpected rate %s > %s%n", unexpected, config.maxUnexpectedRate());
      return 1;
    }
    return 0;
  }

  static String version() {
    try (InputStream in =
        DemoLoadMain.class
            .getClassLoader()
            .getResourceAsStream("io/argos/sample/build-info.properties")) {
      if (in == null) {
        return "dev";
      }
      Properties properties = new Properties();
      properties.load(in);
      return properties.getProperty("version", "dev");
    } catch (IOException e) {
      return "dev";
    }
  }

  /**
   * Prints a progress line per report interval (interval rate and interval percentiles); lines of
   * the warm-up are marked. The measured phase has its own counters, so its first interval starts
   * at the end of the warm-up.
   */
  private static final class Progress implements FixedRateGenerator.Reporter {

    private final PrintStream out;
    private final long warmupNanos;
    private LoadStats.Snapshot last;
    private long lastNanos;
    private boolean lastWarmup = true;

    Progress(PrintStream out, Duration warmup) {
      this.out = out;
      this.warmupNanos = warmup.toNanos();
    }

    @Override
    public void report(
        long elapsedNanos, LoadStats.Snapshot snapshot, int inFlight, boolean warmup) {
      if (lastWarmup && !warmup) {
        last = null;
        lastNanos = warmupNanos;
      }
      lastWarmup = warmup;
      LoadStats.Snapshot before = last == null ? emptyLike(snapshot) : last;
      String line =
          Summary.progressLine(
              elapsedNanos / 1e9, (elapsedNanos - lastNanos) / 1e9, snapshot, before, inFlight);
      out.println(warmup ? line.replace("[demo-load] ", "[demo-load] warm-up ") : line);
      last = snapshot;
      lastNanos = elapsedNanos;
    }

    private static LoadStats.Snapshot emptyLike(LoadStats.Snapshot snapshot) {
      return new LoadStats.Snapshot(
          snapshot.routes().stream()
              .map(
                  r ->
                      new LoadStats.RouteSnapshot(
                          r.route(),
                          0,
                          0,
                          0,
                          0,
                          0,
                          0,
                          0,
                          0,
                          0,
                          0,
                          Map.of(),
                          LatencyHistogram.Snapshot.empty()))
              .toList(),
          LatencyHistogram.Snapshot.empty(),
          LatencyHistogram.Snapshot.empty());
    }
  }
}
