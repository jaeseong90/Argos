/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.command;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.collector.CommandOutcome;
import io.argos.agent.testing.collector.FakeCollector;
import io.argos.agent.testing.launcher.AppLauncher;
import io.argos.agent.testing.launcher.ExitedApp;
import io.argos.agent.testing.launcher.HttpResult;
import io.argos.agent.testing.launcher.Jdk;
import io.argos.agent.testing.launcher.RunningApp;
import io.argos.agent.testing.launcher.SampleApp;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Virtual threads in the thread dump (T-AG-054 dump item, 10 §12.1, 10 U-2):
 *
 * <ul>
 *   <li>shop-boot3 with {@code SAMPLE_VT=true} (Tomcat on virtual threads) on each {@code
 *       shop-boot3@N} of {@code -Pagent.e2e} with N &ge; 21: while {@code /api/threads/dump-me}
 *       blocks, {@code THREAD_DUMP} lists its thread with {@code virtual=true} and the request's
 *       {@code tx_id}, and {@code THREAD_STACK(tx_id)} returns the same thread and stack;
 *   <li>10 U-2: the trivial app on each attach JDK ({@code -Pagent.attach.jdks}) answers {@code
 *       THREAD_DUMP} with {@code virtual_threads_source} {@code dumpThreads} on JDK 21+ and {@code
 *       unavailable} below; console line {@code [wo17-u2] jdk=N dumpThreads=yes|no}.
 * </ul>
 */
@Tag("req:F-A12")
@Tag("req:F-A30")
class ThreadDumpVirtualTest {
  @TempDir Path work;

  static Stream<Integer> vtJdks() {
    List<Integer> jdks = CommandRuns.shopJdks().stream().filter(jdk -> jdk >= 21).toList();
    Assumptions.assumeFalse(jdks.isEmpty(), "no shop-boot3@21+ in -Pagent.e2e");
    return jdks.stream();
  }

  static Stream<Integer> attachJdks() {
    return CommandRuns.attachJdks().stream();
  }

  @ParameterizedTest(name = "T-AG-054/T-AG-054r shop-boot3 SAMPLE_VT=true JDK {0}")
  @MethodSource("vtJdks")
  @DisplayName(
      "T-AG-054/T-AG-054r THREAD_DUMP of a virtual-thread request: virtual=true with the running tx_id;"
          + " THREAD_STACK(tx_id) returns the same thread and stack")
  void virtualThreadInDump(int jdk) throws Exception {
    ExecutorService requests = Executors.newFixedThreadPool(2);
    try (FakeCollector collector = FakeCollector.builder().tls(true).start()) {
      Map<String, String> settings = new LinkedHashMap<>();
      settings.put("sampling.profile.normal-rate", "1.0");
      AppLauncher launcher =
          CommandRuns.shop(work.resolve("vt-" + jdk), jdk, collector, settings)
              .virtualThreads(true);
      RunningApp app = launcher.start(Duration.ofMinutes(4));
      try {
        CommandRuns.awaitEstablished(app, collector);
        assertThat(app.get("/api/orders/1").status()).isEqualTo(200);
        CompletableFuture<HttpResult> blocked =
            CommandRuns.async(app, CommandRuns.DUMP_ME + "?ms=15000", requests);
        Map<String, Object> dump = null;
        List<Map<String, Object>> at = List.of();
        for (int attempt = 0; attempt < 3 && at.isEmpty(); attempt++) {
          Thread.sleep(attempt == 0 ? 1_500L : CommandRuns.DUMP_COOLDOWN_MS);
          CommandOutcome outcome = CommandRuns.run(collector, "THREAD_DUMP", Map.of());
          assertThat(outcome.status()).as("THREAD_DUMP: %s", outcome.error()).isEqualTo("OK");
          dump = CommandRuns.json(outcome);
          at = CommandRuns.threadsAt(dump, CommandRuns.BLOCK_FRAME);
          System.out.println(
              "[wo17-vt] jdk="
                  + jdk
                  + " threads="
                  + CommandRuns.threads(dump).size()
                  + " virtual="
                  + CommandRuns.threads(dump).stream()
                      .filter(t -> Boolean.TRUE.equals(t.get("virtual")))
                      .count()
                  + " source="
                  + dump.get("virtual_threads_source")
                  + " bytes="
                  + outcome.payload().size()
                  + " durationMs="
                  + outcome.durationMs());
        }
        assertThat(dump.get("virtual_threads_source")).isEqualTo("dumpThreads");
        assertThat(at).as("the blocked request thread in the dump").hasSize(1);
        Map<String, Object> vt = at.get(0);
        assertThat(vt.get("virtual")).as("the request runs on a virtual thread").isEqualTo(true);
        assertThat(vt.get("tx_id")).as("tx_id of the running request").isNotNull();
        String txId = (String) vt.get("tx_id");
        assertThat(txId).matches("[0-9]{1,20}");

        CommandOutcome stack = CommandRuns.run(collector, "THREAD_STACK", Map.of("tx_id", txId));
        assertThat(stack.status()).as("THREAD_STACK: %s", stack.error()).isEqualTo("OK");
        List<Map<String, Object>> one = CommandRuns.threads(CommandRuns.json(stack));
        assertThat(one).hasSize(1);
        Map<String, Object> s = one.get(0);
        assertThat(s.get("id")).isEqualTo(vt.get("id"));
        assertThat(s.get("virtual")).isEqualTo(true);
        assertThat(s.get("tx_id")).isEqualTo(txId);
        List<String> dumpFrames = CommandRuns.frames(vt);
        List<String> stackFrames = CommandRuns.frames(s);
        int inDump = CommandRuns.indexOf(dumpFrames, CommandRuns.BLOCK_FRAME);
        int inStack = CommandRuns.indexOf(stackFrames, CommandRuns.BLOCK_FRAME);
        assertThat(inStack).isNotNegative();
        int common = Math.min(dumpFrames.size() - inDump, stackFrames.size() - inStack);
        assertThat(common).isPositive();
        assertThat(stackFrames.subList(inStack, inStack + common))
            .as("THREAD_STACK frames from the blocking frame on = THREAD_DUMP frames")
            .isEqualTo(dumpFrames.subList(inDump, inDump + common));
        assertThat(blocked.get(30, TimeUnit.SECONDS).status()).isEqualTo(200);
      } finally {
        app.close();
        CommandRuns.keepEvidence("vt-dump-" + jdk, app);
      }
    } finally {
      requests.shutdownNow();
    }
  }

  @ParameterizedTest(name = "10 U-2 dumpThreads on JDK {0}")
  @MethodSource("attachJdks")
  @DisplayName(
      "10 U-2 THREAD_DUMP uses HotSpotDiagnosticMXBean.dumpThreads from JDK 21 on and"
          + " ThreadMXBean alone below")
  void dumpThreadsAvailability(int jdk) throws Exception {
    try (FakeCollector collector = FakeCollector.builder().tls(true).start()) {
      Path dir = work.resolve("u2-jdk" + jdk);
      SampleApp trivial =
          SampleApp.mainClass(
              "trivial",
              "example.trivial.TrivialApp",
              CommandRuns.required("argos.test.trivialAppClasses"));
      AppLauncher launcher =
          AppLauncher.of(trivial, Jdk.of(jdk))
              .withAgent(CommandRuns.releaseJar())
              .collector(collector)
              .args(dir.resolve("out").resolve("results.properties").toString(), "20000")
              .workDir(dir);
      CompletableFuture<ExitedApp> run =
          CompletableFuture.supplyAsync(
              () -> {
                try {
                  return launcher.runToExit(Duration.ofMinutes(2));
                } catch (Exception e) {
                  throw new IllegalStateException(e);
                }
              });
      List<String> statuses = new ArrayList<>();
      try {
        assertThat(collector.awaitSessions(1, Duration.ofSeconds(60)))
            .as("a session from the trivial app on JDK %d", jdk)
            .isNotEmpty();
        CommandOutcome outcome = CommandRuns.run(collector, "THREAD_DUMP", Map.of());
        statuses.add(outcome.status());
        assertThat(outcome.status()).as("THREAD_DUMP: %s", outcome.error()).isEqualTo("OK");
        Map<String, Object> dump = CommandRuns.json(outcome);
        boolean used = "dumpThreads".equals(dump.get("virtual_threads_source"));
        System.out.println(
            "[wo17-u2] jdk="
                + jdk
                + " dumpThreads="
                + (used ? "yes" : "no")
                + " source="
                + dump.get("virtual_threads_source")
                + " threads="
                + CommandRuns.threads(dump).size()
                + " durationMs="
                + outcome.durationMs());
        assertThat(used).as("dumpThreads used on JDK %d", jdk).isEqualTo(jdk >= 21);
        assertThat(dump.get("virtual_threads_source"))
            .isEqualTo(jdk >= 21 ? "dumpThreads" : "unavailable");
        assertThat(CommandRuns.threadsAt(dump, "example.trivial.TrivialApp.main"))
            .as("the trivial app's main thread")
            .isNotEmpty();
      } finally {
        ExitedApp exited = run.get(3, TimeUnit.MINUTES);
        assertThat(exited.timedOut()).as(exited.describe()).isFalse();
      }
      assertThat(statuses).containsExactly("OK");
    }
  }
}
