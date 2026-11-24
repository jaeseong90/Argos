/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.errorstack;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.collector.Collected;
import io.argos.agent.testing.collector.FakeCollector;
import io.argos.agent.testing.export.TransactionRecord;
import io.argos.agent.testing.export.TransactionRecord.StepRecord;
import io.argos.agent.testing.launcher.AppLauncher;
import io.argos.agent.testing.launcher.Jdk;
import io.argos.agent.testing.launcher.RunningApp;
import io.argos.agent.testing.launcher.SampleApp;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The error stack of a real agent (10 §9.1, F-A08, ADR-0010 C-222 (1), C-237 (6); WO-19). The
 * shop-boot2 and shop-boot3 entries of {@code -Pagent.e2e} (default {@code shop-boot3@21}) run with
 * the agent jar of the build over gRPC to a TLS {@link FakeCollector}, default settings ({@code
 * capture.stack-depth} 30, {@code instrumentation.app-packages} unset: inferred from the jar's
 * {@code Start-Class} as {@code io.argos.sample}).
 *
 * <ul>
 *   <li>{@code GET /api/error-500} (the controller throws {@code IllegalStateException}): the
 *       profile has exactly one top-level ERROR step named {@code java.lang.IllegalStateException},
 *       error flag, no detail, 1 to 30 frames {@code class.method(file:line)}, the first one the
 *       controller method; the record's {@code error_top_frame_id} resolves (pre-sent dictionary)
 *       to the line-free {@code io.argos.sample.<app>.web.DiagnosticsController.error500(
 *       DiagnosticsController.java)} (T-AG-059a/b/c);
 *   <li>{@code GET /api/send-error} ({@code sendError(503)}, no exception): no ERROR step and no
 *       top frame (T-AG-059d).
 * </ul>
 *
 * One {@code [wo19-errorstack]} line per app. Test IDs T-AG-059a..d are proposed by WO-19.
 */
@Tag("req:F-A08")
class ErrorStackE2eTest {
  static final Set<String> APPS = Set.of("shop-boot2", "shop-boot3");
  static final int ERRORS = 3;

  /** One app run. */
  record Combo(String app, int jdk) {
    String name() {
      return app + "-jdk" + jdk;
    }

    /** {@code boot2} / {@code boot3}: the sample app's package segment. */
    String segment() {
      return app.substring("shop-".length());
    }

    @Override
    public String toString() {
      return app + "@" + jdk;
    }
  }

  static Stream<Combo> combos() {
    List<Combo> combos = new ArrayList<>();
    for (String item : System.getProperty("argos.test.e2e", "shop-boot3@21").split(",", -1)) {
      String[] parts = item.strip().split("@", -1);
      if (parts.length == 2 && APPS.contains(parts[0])) {
        combos.add(new Combo(parts[0], Integer.parseInt(parts[1])));
      }
    }
    Assumptions.assumeFalse(
        combos.isEmpty(), "no shop-boot2/shop-boot3 in -Pagent.e2e, e.g. shop-boot3@21");
    return combos.stream();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("combos")
  @DisplayName(
      "T-AG-059 error stack: one ERROR step (class name, <= 30 frames, controller first) and"
          + " error_top_frame_id = the line-free application frame; sendError has neither")
  void errorStack(Combo combo) throws Exception {
    Path workRoot = Files.createTempDirectory("wo19-errorstack-");
    String agentJar = System.getProperty("argos.test.releaseAgentJar");
    assertThat(agentJar).as("argos.test.releaseAgentJar").isNotBlank();
    try (FakeCollector collector = FakeCollector.builder().tls(true).start()) {
      AppLauncher launcher =
          AppLauncher.of(SampleApp.named(combo.app()), Jdk.of(combo.jdk()))
              .workDir(workRoot.resolve(combo.name()))
              .readyTimeout(Duration.ofMinutes(4))
              .withAgent(Path.of(agentJar), new LinkedHashMap<>());
      launcher.collector(collector);
      RunningApp app = launcher.start(Duration.ofMinutes(4));
      try {
        run(combo, app, collector);
      } finally {
        app.close();
        keepEvidence(combo.name(), app);
      }
    } finally {
      deleteTree(workRoot);
    }
  }

  private static void run(Combo combo, RunningApp app, FakeCollector collector) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
    while (collector.openSessionCount() == 0) {
      assertThat(System.nanoTime() < deadline && app.isAlive())
          .as("collector session within 60 s\n%s", app.describe())
          .isTrue();
      Thread.sleep(100);
    }
    Collected collected = app.collected();
    for (int i = 0; i < ERRORS; i++) {
      assertThat(app.get("/api/error-500").status()).isEqualTo(500);
    }
    assertThat(app.get("/api/send-error").status()).isEqualTo(503);

    List<TransactionRecord> errors =
        collected.awaitTransactions(
            tx -> "/api/error-500".equals(tx.path()), ERRORS, Duration.ofSeconds(30));
    assertThat(errors).as("the %d error transactions", ERRORS).hasSize(ERRORS);
    List<TransactionRecord> sendErrors =
        collected.awaitTransactions(
            tx -> "/api/send-error".equals(tx.path()), 1, Duration.ofSeconds(30));
    assertThat(sendErrors).hasSize(1);

    String controller = "io.argos.sample." + combo.segment() + ".web.DiagnosticsController";
    String expectedTop = controller + ".error500(DiagnosticsController.java)";
    String firstFramePattern =
        java.util.regex.Pattern.quote(controller + ".error500(DiagnosticsController.java:")
            + "\\d+\\)";
    int maxFrames = 0;
    String top = null;
    String firstFrame = null;
    for (TransactionRecord tx : errors) {
      assertThat(tx.status()).isEqualTo(500);
      assertThat(tx.error()).isTrue();
      assertThat(tx.errorClass()).isEqualTo("java.lang.IllegalStateException");
      assertThat(tx.profileCaptured()).as("profile of the error %s", tx.txId()).isTrue();
      List<StepRecord> errorSteps = tx.steps("ERROR");
      assertThat(errorSteps).as("ERROR steps of %s\n%s", tx.txId(), tx).hasSize(1);
      StepRecord step = errorSteps.get(0);
      assertThat(step.name()).isEqualTo("java.lang.IllegalStateException");
      assertThat(step.field("error")).isEqualTo(true);
      assertThat(step.field("detail")).as("the message stays on the record").isNull();
      assertThat(step.parent()).as("top level").isIn(-1, null);
      List<String> frames = frames(step);
      assertThat(frames).as("frames of the ERROR step of %s", tx.txId()).isNotEmpty();
      assertThat(frames.size()).isLessThanOrEqualTo(30);
      assertThat(frames).allMatch(f -> f.matches("[^()]+\\.[^.()]+\\(.+\\)"), "class.method(file)");
      assertThat(frames.get(0)).matches(firstFramePattern);
      maxFrames = Math.max(maxFrames, frames.size());
      firstFrame = frames.get(0);
      top = (String) tx.field("error_top_frame");
      assertThat(top).as("error_top_frame of %s", tx.txId()).isEqualTo(expectedTop);
    }
    TransactionRecord sent = sendErrors.get(0);
    assertThat(sent.status()).isEqualTo(503);
    assertThat(sent.error()).isTrue();
    assertThat(sent.steps("ERROR")).as("no ERROR step without a Throwable").isEmpty();
    assertThat(sent.field("error_top_frame")).isNull();
    assertThat(app.agentLog()).doesNotContain("error stack failed");

    System.out.printf(
        "[wo19-errorstack] %s: %d error transactions, 1 ERROR step each, max frames %d, first"
            + " frame %s, error_top_frame %s; sendError: ERROR steps 0, top frame null%n",
        combo, errors.size(), maxFrames, firstFrame, top);
  }

  @SuppressWarnings("unchecked")
  private static List<String> frames(StepRecord step) {
    Object stack = step.field("stack");
    List<String> out = new ArrayList<>();
    if (stack instanceof List<?> list) {
      for (Object frame : list) {
        out.add(String.valueOf(frame));
      }
    }
    return out;
  }

  private static void keepEvidence(String name, RunningApp app) {
    String reportDir = System.getProperty("argos.test.reportDir");
    if (reportDir == null || reportDir.isBlank()) {
      return;
    }
    try {
      Path dir = Files.createDirectories(Path.of(reportDir));
      Files.writeString(
          dir.resolve("wo19-errorstack-" + name + "-agent.log"),
          app.agentLog(),
          StandardCharsets.UTF_8);
      Files.write(
          dir.resolve("wo19-errorstack-" + name + "-stdout.log"),
          app.stdoutLines(),
          StandardCharsets.UTF_8);
    } catch (IOException e) {
      System.out.println("[wo19-errorstack] cannot keep the evidence of " + name + ": " + e);
    }
  }

  private static void deleteTree(Path root) {
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
}
