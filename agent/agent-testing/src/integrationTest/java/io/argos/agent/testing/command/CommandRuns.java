/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.command;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.collector.CommandOutcome;
import io.argos.agent.testing.collector.FakeCollector;
import io.argos.agent.testing.launcher.AppLauncher;
import io.argos.agent.testing.launcher.HttpResult;
import io.argos.agent.testing.launcher.Jdk;
import io.argos.agent.testing.launcher.RunningApp;
import io.argos.agent.testing.launcher.SampleApp;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * Shared code of the command suite (WO-17, 10 §12.1, 11 §8.2): shop-boot3 of {@code -Pagent.e2e}
 * with the release agent jar over gRPC to a TLS {@link FakeCollector}, the trivial app on the
 * attach JDKs, the JSON shape of a thread dump result, and the evidence files ({@code wo17-*} logs
 * in {@code build/reports/agent-e2e}). Public harness API only.
 */
final class CommandRuns {
  static final String APP = "shop-boot3";

  /** Blocks the request thread in {@code ThreadDumpTarget.blockHere} (18 §4.2). */
  static final String DUMP_ME = "/api/threads/dump-me";

  /** The frame every {@link #DUMP_ME} request shows. */
  static final String BLOCK_FRAME = "io.argos.sample.common.diag.ThreadDumpTarget.blockHere";

  static final Duration COMMAND_WAIT = Duration.ofSeconds(30);

  /**
   * A little more than the default THREAD_DUMP cooldown (5 s,
   * commands.thread-dump.cooldown-seconds).
   */
  static final long DUMP_COOLDOWN_MS = 5_500L;

  private CommandRuns() {}

  /** The JDKs of {@code shop-boot3@N} in {@code -Pagent.e2e}, in order. */
  static List<Integer> shopJdks() {
    List<Integer> jdks = new ArrayList<>();
    for (String item : System.getProperty("argos.test.e2e", APP + "@21").split(",", -1)) {
      String[] parts = item.strip().split("@", -1);
      if (parts.length == 2 && parts[0].equals(APP)) {
        jdks.add(Integer.parseInt(parts[1]));
      }
    }
    return jdks;
  }

  /** {@code -Pagent.attach.jdks} (default 8 and 21). */
  static List<Integer> attachJdks() {
    return Arrays.stream(System.getProperty("argos.test.jdks", "8,21").split(",", -1))
        .map(String::trim)
        .map(Integer::parseInt)
        .toList();
  }

  static String required(String name) {
    String value = System.getProperty(name);
    assertThat(value)
        .as("system property %s (set by agent-testing integrationTest)", name)
        .isNotBlank();
    return value;
  }

  static Path releaseJar() {
    return Path.of(required("argos.test.releaseAgentJar"));
  }

  /** shop-boot3 on {@code jdk} with the release agent sending to {@code collector}. */
  static AppLauncher shop(
      Path dir, int jdk, FakeCollector collector, Map<String, String> settings) {
    AppLauncher launcher =
        AppLauncher.of(SampleApp.named(APP), Jdk.of(jdk))
            .workDir(dir)
            .readyTimeout(Duration.ofMinutes(4))
            .withAgent(releaseJar(), settings);
    launcher.collector(collector);
    return launcher;
  }

  /** Waits for the agent's session with {@code collector} (60 s). */
  static void awaitEstablished(RunningApp app, FakeCollector collector)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
    while (collector.openSessionCount() == 0) {
      assertThat(System.nanoTime() < deadline && app.isAlive())
          .as("collector session within 60 s\n%s", app.describe())
          .isTrue();
      Thread.sleep(100);
    }
  }

  /** {@code GET path} on another thread. */
  static CompletableFuture<HttpResult> async(RunningApp app, String path, Executor executor) {
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            return app.get(path);
          } catch (IOException e) {
            throw new IllegalStateException(e);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
          }
        },
        executor);
  }

  /** The command's answer within {@link #COMMAND_WAIT}. */
  static CommandOutcome run(FakeCollector collector, String type, Map<String, String> args)
      throws Exception {
    return collector.command(type, args).get(COMMAND_WAIT.toMillis(), TimeUnit.MILLISECONDS);
  }

  /** The JSON object of an {@code application/json} outcome. */
  static Map<String, Object> json(CommandOutcome outcome) {
    assertThat(outcome.contentType())
        .as("content type of %s", outcome)
        .startsWith("application/json");
    return TestJson.object(outcome.payloadText());
  }

  @SuppressWarnings("unchecked")
  static List<Map<String, Object>> threads(Map<String, Object> dump) {
    Object threads = dump.get("threads");
    assertThat(threads).as("threads[] of the dump").isInstanceOf(List.class);
    return (List<Map<String, Object>>) threads;
  }

  @SuppressWarnings("unchecked")
  static List<String> frames(Map<String, Object> thread) {
    return (List<String>) thread.get("frames");
  }

  /** The threads whose frames contain {@code frame}. */
  static List<Map<String, Object>> threadsAt(Map<String, Object> dump, String frame) {
    List<Map<String, Object>> found = new ArrayList<>();
    for (Map<String, Object> t : threads(dump)) {
      for (String f : frames(t)) {
        if (f.contains(frame)) {
          found.add(t);
          break;
        }
      }
    }
    return found;
  }

  /** Index of the first frame containing {@code frame}, -1 when none. */
  static int indexOf(List<String> frames, String frame) {
    for (int i = 0; i < frames.size(); i++) {
      if (frames.get(i).contains(frame)) {
        return i;
      }
    }
    return -1;
  }

  /** Occurrences of {@code needle} in {@code text}. */
  static int count(String text, String needle) {
    int found = 0;
    for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) {
      found++;
    }
    return found;
  }

  /** Writes the agent log and stdout of {@code app} to {@code build/reports/agent-e2e/wo17-*}. */
  static void keepEvidence(String name, RunningApp app) {
    String reportDir = System.getProperty("argos.test.reportDir");
    if (reportDir == null || reportDir.isBlank() || app == null) {
      return;
    }
    try {
      Path dir = Files.createDirectories(Path.of(reportDir));
      Files.writeString(
          dir.resolve("wo17-" + name + "-agent.log"), app.agentLog(), StandardCharsets.UTF_8);
      Files.write(
          dir.resolve("wo17-" + name + "-stdout.log"), app.stdoutLines(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      System.out.println("[wo17] cannot keep the logs of " + name + ": " + e);
    }
  }
}
