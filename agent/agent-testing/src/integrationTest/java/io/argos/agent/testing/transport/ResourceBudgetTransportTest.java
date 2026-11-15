/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.transport;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.collector.BatchKind;
import io.argos.agent.testing.collector.FakeCollector;
import io.argos.agent.testing.collector.ReceivedBatch;
import io.argos.agent.testing.jvm.ThreadCounter;
import io.argos.agent.testing.launcher.AppLauncher;
import io.argos.agent.testing.launcher.Jdk;
import io.argos.agent.testing.launcher.LoadResult;
import io.argos.agent.testing.launcher.RunningApp;
import io.argos.agent.testing.launcher.SampleApp;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * T-NFR-26 reduced (N-26, DSN-10-11, 10 §10.3, §14): under load with batches of about 1 MiB without
 * compression (the worst case for socket buffers) the agent has at most 6 {@code argos-} threads
 * and its direct memory stays at or under 8 MiB. The dedicated-machine verdict of N-26 belongs to
 * WO-19 and WO-51: the metaspace printed here is a reference value of this machine (compare
 * ADR-0005 "+17 MiB" for the transport).
 */
@Tag("req:N-26")
@Tag("req:DSN-10-11")
class ResourceBudgetTransportTest {
  private static final long EIGHT_MIB = 8L * 1024 * 1024;
  private static final long ONE_MIB = 1024L * 1024;
  private static final Pattern METASPACE_USED = Pattern.compile("Metaspace\\s+used\\s+(\\d+)K");
  private static final Pattern FOREIGN_TRANSPORT_THREAD =
      Pattern.compile(
          "grpc-.*|globalEventExecutor.*|nioEventLoopGroup.*|ObjectCleanerThread|threadDeathWatcher.*");

  @TempDir(cleanup = CleanupMode.NEVER)
  Path work;

  @AfterEach
  void deleteRun() {
    TransportRuns.deleteRun(work);
  }

  /** Paths that the agent records at 2 KiB each, random so that nothing helps a compressor. */
  private static String[] longPaths() {
    Random random = new Random(42);
    String[] paths = new String[64];
    for (int p = 0; p < paths.length; p++) {
      StringBuilder path = new StringBuilder("/api/orders/");
      while (path.length() < 2_040) {
        path.append(Long.toHexString(random.nextLong()));
      }
      paths[p] = path.substring(0, 2_040);
    }
    return paths;
  }

  private static long metaspaceUsedKb(RunningApp app) {
    Matcher matcher = METASPACE_USED.matcher(app.jcmd().run("GC.heap_info"));
    return matcher.find() ? Long.parseLong(matcher.group(1)) : -1;
  }

  @Test
  @DisplayName(
      "T-NFR-26a under load with 1 MiB batches: argos- threads <= 6, direct_memory_bytes <= 8 MiB")
  void threadsAndDirectMemory() throws Exception {
    String combo = TransportRuns.firstJarCombo();
    Assumptions.assumeTrue(combo != null, "no jar app in -Pagent.e2e");
    SampleApp sample = SampleApp.named(TransportRuns.appOf(combo));
    Jdk jdk = Jdk.of(TransportRuns.jdkOf(combo));
    long baselineKb;
    try (RunningApp plain =
        AppLauncher.of(sample, jdk)
            .withoutAgent()
            .workDir(work.resolve("no-agent"))
            .start(Duration.ofMinutes(4))) {
      plain.load(50, Duration.ofSeconds(3), "/api/orders/1");
      baselineKb = metaspaceUsedKb(plain);
    }
    try (FakeCollector collector = FakeCollector.builder().tls(true).tlsDirectory(work).start();
        RunningApp app =
            largeBatches(AppLauncher.of(sample, jdk))
                .collector(collector)
                .workDir(work.resolve("agent"))
                .start(Duration.ofMinutes(4))) {
      measure(app, collector, combo, baselineKb);
    }
  }

  @Test
  @DisplayName(
      "T-NFR-26a Linux (container mode): argos- threads <= 6, direct_memory_bytes <= 8 MiB with 1 MiB"
          + " batches (the JDK's socket buffers differ from Windows)")
  void threadsAndDirectMemoryOnLinux() throws Exception {
    Assumptions.assumeTrue(
        Boolean.getBoolean("argos.test.container"),
        "container mode runs with -Pagent.container=true");
    String combo = TransportRuns.firstJarCombo();
    Assumptions.assumeTrue(combo != null, "no jar app in -Pagent.e2e");
    try (FakeCollector collector =
            FakeCollector.builder().tls(true).tlsDirectory(work).bindAddress("0.0.0.0").start();
        RunningApp app =
            largeBatches(
                    AppLauncher.of(
                        SampleApp.named(TransportRuns.appOf(combo)),
                        Jdk.of(TransportRuns.jdkOf(combo))))
                .collector(collector)
                .container(true, "2", "2g")
                .workDir(work.resolve("agent-linux"))
                .start(Duration.ofMinutes(4))) {
      measure(app, collector, combo + " in a Linux container", -1);
    }
  }

  private static AppLauncher largeBatches(AppLauncher launcher) {
    return launcher
        .withAgent(TransportRuns.releaseJar())
        .agentConfig("sender.compression", "none")
        .agentConfig("sender.flush-interval-ms", "3000");
  }

  private void measure(RunningApp app, FakeCollector collector, String label, long baselineKb)
      throws Exception {
    assertThat(collector.awaitSessions(1, Duration.ofSeconds(60))).isNotEmpty();

    AtomicBoolean loading = new AtomicBoolean(true);
    AtomicLong maxDirect = new AtomicLong();
    List<Long> samples = new CopyOnWriteArrayList<>();
    Thread sampler =
        new Thread(
            () -> {
              while (loading.get()) {
                try {
                  long direct = app.selfDiagnostics().value("direct_memory_bytes");
                  samples.add(direct);
                  maxDirect.accumulateAndGet(direct, Math::max);
                } catch (RuntimeException e) {
                  // The snapshot file is being replaced or not there yet: the next round reads it.
                }
                try {
                  Thread.sleep(500);
                } catch (InterruptedException e) {
                  return;
                }
              }
            },
            "direct-memory-sampler");
    sampler.setDaemon(true);
    sampler.start();
    LoadResult load = app.load(200, Duration.ofSeconds(20), longPaths());
    assertThat(load.failures()).isZero();
    Thread.sleep(4_000); // two more snapshots after the last flush
    String dump = app.jcmd().threadPrint();
    List<String> threads = ThreadCounter.namesStartingWith(dump, "argos-");
    long metaspaceKb = metaspaceUsedKb(app);
    loading.set(false);
    sampler.join(2_000);

    assertThat(threads)
        .as("agent threads (10 §14 budget)")
        .contains("argos-sender", "argos-io", "argos-logger", "argos-watch")
        .hasSizeLessThanOrEqualTo(6);
    assertThat(ThreadCounter.names(dump))
        .as(
            "no thread of gRPC's shared executors or timer, or of Netty, outside argos-io (ADR-0005)")
        .noneMatch(name -> FOREIGN_TRANSPORT_THREAD.matcher(name).matches());

    int largest =
        collector.batches(BatchKind.TRANSACTIONS).stream()
            .mapToInt(ReceivedBatch::bytes)
            .max()
            .orElse(0);
    assertThat(largest)
        .as("the batches really are about 1 MiB (11 §2 target); %s", collector.counts())
        .isGreaterThan((int) (ONE_MIB / 2))
        .isLessThanOrEqualTo((int) ONE_MIB);

    assertThat(samples).as("direct_memory_bytes was reported").isNotEmpty();
    assertThat(maxDirect.get())
        .as("direct_memory_bytes samples %s", samples)
        .isLessThanOrEqualTo(EIGHT_MIB);

    System.out.println(
        "[transport] "
            + label
            + ": threads "
            + threads
            + ", largest batch "
            + largest
            + " B, direct_memory_bytes max "
            + maxDirect.get()
            + " B ("
            + samples.size()
            + " samples), metaspace used "
            + metaspaceKb
            + " KiB"
            + (baselineKb < 0
                ? ""
                : ", without the agent "
                    + baselineKb
                    + " KiB, +"
                    + (metaspaceKb - baselineKb) / 1024
                    + " MiB")
            + " (reference values, INVALID machine)");
  }
}
