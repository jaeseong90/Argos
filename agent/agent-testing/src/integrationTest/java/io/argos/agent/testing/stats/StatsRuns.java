/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.stats;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.collector.Collected;
import io.argos.agent.testing.collector.FakeCollector;
import io.argos.agent.testing.export.TransactionRecord;
import io.argos.agent.testing.launcher.AppLauncher;
import io.argos.agent.testing.launcher.HttpResult;
import io.argos.agent.testing.launcher.Jdk;
import io.argos.agent.testing.launcher.RunningApp;
import io.argos.agent.testing.launcher.SampleApp;
import io.argos.sample.extstub.ExtStub;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;

/**
 * Runs of the WO-16 statistics suite (package {@code stats}): a shop jar app of {@code -Pagent.e2e}
 * with the release agent jar over gRPC to a TLS {@link FakeCollector}, with its own in-process
 * ext-stub (127.0.0.1, any free port). The agent log and console are kept in {@code
 * argos.test.reportDir} as {@code wo16-<name>-*} when the run stops.
 */
final class StatsRuns {
  /** Jar apps that implement the shop catalog (18 §4.1). */
  static final List<String> SHOP_JAR_APPS = List.of("shop-boot2", "shop-boot3", "shop-boot4");

  private StatsRuns() {}

  /** One app on one JDK. */
  record Combo(String app, int jdk) {
    static Combo parse(String text) {
      String[] parts = text.strip().split("@", -1);
      if (parts.length != 2) {
        throw new IllegalArgumentException("expected <app>@<jdk>, got " + text);
      }
      return new Combo(parts[0], Integer.parseInt(parts[1]));
    }

    String name() {
      return app + "-jdk" + jdk;
    }

    @Override
    public String toString() {
      return app + "@" + jdk;
    }
  }

  /** The combinations of {@code apps} in {@code -Pagent.e2e} (skips when there is none). */
  static Stream<Combo> combosOf(String... apps) {
    List<String> wanted = List.of(apps);
    List<Combo> combos = new ArrayList<>();
    for (String item : System.getProperty("argos.test.e2e", "shop-boot3@21").split(",", -1)) {
      if (!item.isBlank() && !item.strip().equals("none")) {
        Combo combo = Combo.parse(item);
        if (SHOP_JAR_APPS.contains(combo.app()) && wanted.contains(combo.app())) {
          combos.add(combo);
        }
      }
    }
    Assumptions.assumeFalse(
        combos.isEmpty(), "none of " + wanted + " in -Pagent.e2e, e.g. shop-boot3@21");
    return combos.stream();
  }

  static String required(String name) {
    String value = System.getProperty(name);
    assertThat(value)
        .as("system property %s (set by agent-testing integrationTest)", name)
        .isNotBlank();
    return value;
  }

  /** Starts {@code combo} with {@code agentSettings} and waits for the collector session. */
  static Run start(String name, Combo combo, Map<String, String> agentSettings) throws Exception {
    Path workRoot = Files.createTempDirectory("wo16-stats-");
    ExtStub extStub = ExtStub.start(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 16);
    FakeCollector collector = null;
    Run run;
    try {
      AppLauncher launcher =
          AppLauncher.of(SampleApp.named(combo.app()), Jdk.of(combo.jdk()))
              .withEnv("SAMPLE_EXT_URL", extStub.baseUri().toString())
              .workDir(workRoot.resolve(name))
              .readyTimeout(Duration.ofMinutes(4))
              .withAgent(
                  Path.of(required("argos.test.releaseAgentJar")),
                  new LinkedHashMap<>(agentSettings));
      collector = FakeCollector.builder().tls(true).start();
      launcher.collector(collector);
      run = new Run(name, launcher.start(Duration.ofMinutes(4)), collector, extStub, workRoot);
    } catch (Exception | Error e) {
      if (collector != null) {
        collector.close();
      }
      extStub.close();
      deleteTree(workRoot);
      throw e;
    }
    try {
      run.awaitEstablished();
    } catch (InterruptedException | RuntimeException | Error e) {
      run.close();
      throw e;
    }
    return run;
  }

  /** Polls {@code condition} every {@code everyMillis} until it holds or {@code timeout} passes. */
  static boolean await(BooleanSupplier condition, Duration timeout, long everyMillis)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (true) {
      if (condition.getAsBoolean()) {
        return true;
      }
      if (System.nanoTime() > deadline) {
        return false;
      }
      Thread.sleep(everyMillis);
    }
  }

  /** {@code tx.field(name)} as a long (0 when absent). */
  static long number(TransactionRecord tx, String name) {
    Object value = tx.field(name);
    return value instanceof Number n ? n.longValue() : 0L;
  }

  /** {@code path} without its query string. */
  static String bareOf(String path) {
    if (path == null) {
      return null;
    }
    int query = path.indexOf('?');
    return query < 0 ? path : path.substring(0, query);
  }

  static void deleteTree(Path root) {
    if (root == null || !Files.exists(root)) {
      return;
    }
    try (Stream<Path> files = Files.walk(root)) {
      for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
        try {
          Files.deleteIfExists(file);
        } catch (IOException e) {
          // left behind; the next clean of the temp directory removes it
        }
      }
    } catch (IOException e) {
      // nothing more to do
    }
  }

  /** One started app with its collector and ext-stub. */
  static final class Run implements AutoCloseable {
    final String name;
    final RunningApp app;
    final FakeCollector collector;
    final ExtStub extStub;
    private final Path workRoot;

    Run(String name, RunningApp app, FakeCollector collector, ExtStub extStub, Path workRoot) {
      this.name = name;
      this.app = app;
      this.collector = collector;
      this.extStub = extStub;
      this.workRoot = workRoot;
    }

    Collected collected() {
      return app.collected();
    }

    /** Waits (at most 60 s) for the agent's {@code collector session established} log line. */
    void awaitEstablished() throws InterruptedException {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
      while (collector.batches().isEmpty()
          && !app.agentLog().contains("collector session established")) {
        assertThat(System.nanoTime() < deadline && app.isAlive())
            .as("collector session of %s within 60 s\n%s", name, app.describe())
            .isTrue();
        Thread.sleep(100);
      }
    }

    /** {@code GET path}, asserting {@code status}. */
    HttpResult get(String path, int status) throws IOException, InterruptedException {
      HttpResult result = app.get(path);
      assertThat(result.status())
          .as("GET %s on %s: %s", path, name, result.body())
          .isEqualTo(status);
      return result;
    }

    /** The C-142 HOST text of a call to {@code port} on 127.0.0.1. */
    static String hostText(int port) {
      return "http://127.0.0.1:" + port;
    }

    String describe() {
      return name + "\n" + app.describe();
    }

    /** Stops the app, the collector and ext-stub; keeps the agent log and console. */
    @Override
    public void close() {
      try {
        app.close();
        keepEvidence();
      } finally {
        collector.close();
        extStub.close();
        deleteTree(workRoot);
      }
    }

    private void keepEvidence() {
      String reportDir = System.getProperty("argos.test.reportDir");
      if (reportDir == null || reportDir.isBlank()) {
        return;
      }
      try {
        Path dir = Files.createDirectories(Path.of(reportDir));
        Files.writeString(
            dir.resolve("wo16-" + name + "-agent.log"), app.agentLog(), StandardCharsets.UTF_8);
        Files.write(
            dir.resolve("wo16-" + name + "-stdout.log"), app.stdoutLines(), StandardCharsets.UTF_8);
      } catch (IOException e) {
        System.out.println("[wo16-run] cannot keep the evidence of " + name + ": " + e);
      }
    }
  }
}
