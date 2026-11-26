/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.chaos;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * One chaos run: the options, the stack (started for the first scenario that runs and shared by the
 * others), the results in order, and the report written when the run ends ({@link #close}): {@code
 * <out>/<run>/report.md} and {@code results.json}, and with {@code -Pchaos.publish=true} {@code
 * docs/bench/chaos-<date>.md}.
 */
public final class ChaosSession implements AutoCloseable {

  private static final DateTimeFormatter RUN_ID =
      DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT).withZone(ZoneOffset.UTC);
  private static final DateTimeFormatter DATE =
      DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT).withZone(ZoneOffset.UTC);

  private final ChaosOptions options;
  private final Instant started = Instant.now().truncatedTo(ChronoUnit.SECONDS);
  private final String runId = RUN_ID.format(started);
  private final Path dir;
  private final List<ScenarioResult> results = new ArrayList<>();
  private final List<String> notes = new ArrayList<>();
  private ChaosStack stack;
  private Exception stackFailure;
  private String environment = "";

  public ChaosSession(ChaosOptions options) {
    this.options = options;
    this.dir = options.out().resolve(runId);
  }

  /** The options. */
  public ChaosOptions options() {
    return options;
  }

  /** {@code [chaos] <text>} on stdout (the test task shows it). */
  public static void log(String text) {
    System.out.println("[chaos] " + Instant.now().truncatedTo(ChronoUnit.SECONDS) + " " + text);
  }

  /** The stack, started on first use; a start failure is kept and rethrown to every scenario. */
  public synchronized ChaosStack stack() throws Exception {
    if (stackFailure != null) {
      throw stackFailure;
    }
    if (stack == null) {
      try {
        stack = ChaosStack.start(options, dir.resolve("work"), runId, ChaosSession::log);
        environment = environment();
        warmUp();
      } catch (Exception e) {
        stackFailure = e;
        notes.add("The stack did not start: " + e.toString().replace('\n', ' '));
        throw e;
      }
    }
    return stack;
  }

  private void warmUp() throws Exception {
    Duration w = options.profile().warmUp();
    log("warm-up " + w.toSeconds() + " s at " + options.rate() + "/s");
    try (LoadRun load =
        LoadRun.start(
            Paths.get(System.getProperty("java.home"), "bin", "java"),
            options.loadJar(),
            stack.appUrl(),
            options.rate(),
            w,
            stack.work().resolve("warm-up"))) {
      load.await(w.plus(Duration.ofMinutes(2)));
    }
  }

  private String environment() {
    return System.getProperty("os.name")
        + " "
        + System.getProperty("os.version")
        + ", "
        + Runtime.getRuntime().availableProcessors()
        + " CPUs; toxiproxy "
        + stack.toxiproxy().network().name().toLowerCase(Locale.ROOT)
        + " network ("
        + Toxiproxy.image()
        + "); server stop "
        + (stack.gracefulStops() ? "graceful (SIGTERM)" : "forced (Windows)")
        + "; harness "
        + System.getProperty("java.vendor")
        + " "
        + System.getProperty("java.version");
  }

  /**
   * Runs scenario {@code id} unless it is not selected; records and returns the result. A failure
   * of the stack or the driver records an aborted (FAIL) result.
   */
  public ScenarioResult run(String id, String title, Fault fault) {
    if (!options.selected(id)) {
      return record(ScenarioResult.skipped(id, title, "not selected (-Pchaos.scenarios)"));
    }
    try {
      ChaosStack s = stack();
      return record(new ScenarioDriver(s, options, ChaosSession::log).run(id, title, fault));
    } catch (Exception e) {
      log(id + " aborted: " + e);
      e.printStackTrace(System.out);
      return record(
          ScenarioResult.aborted(
              id,
              title,
              fault.describe(),
              "aborted: " + e.toString().replace('\n', ' '),
              List.of()));
    }
  }

  /** Records a result that was decided without running (skipped with a reason). */
  public synchronized ScenarioResult record(ScenarioResult result) {
    results.add(result);
    return result;
  }

  /** The results so far. */
  public synchronized List<ScenarioResult> results() {
    return List.copyOf(results);
  }

  /** Stops the stack and writes the report. */
  @Override
  public synchronized void close() throws IOException {
    if (stack != null) {
      stack.close();
    }
    if (results.isEmpty()) {
      return;
    }
    Instant finished = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    ChaosReport.RunInfo run =
        new ChaosReport.RunInfo(
            runId,
            started,
            finished,
            commit(options.repositoryRoot()),
            options.machineProfile(),
            options.profile().id(),
            options.rate(),
            options.valid(),
            environment,
            notes);
    Files.createDirectories(dir);
    String md = ChaosReport.markdown(run, results);
    Files.writeString(dir.resolve("report.md"), md, StandardCharsets.UTF_8);
    Files.writeString(
        dir.resolve("results.json"), ChaosReport.json(run, results), StandardCharsets.UTF_8);
    log("report " + dir.resolve("report.md"));
    if (options.publishDir() != null) {
      Path target = options.publishDir().resolve("chaos-" + DATE.format(started) + ".md");
      Files.createDirectories(target.getParent());
      Files.writeString(target, md, StandardCharsets.UTF_8);
      log("published " + target);
    }
  }

  private static String commit(Path root) {
    try {
      Process p =
          new ProcessBuilder("git", "rev-parse", "--short", "HEAD")
              .directory(root.toFile())
              .redirectErrorStream(true)
              .start();
      String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
      return p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0 ? out : "unknown";
    } catch (IOException e) {
      return "unknown";
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return "unknown";
    }
  }
}
