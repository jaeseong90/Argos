/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.chaos;

import io.argos.agent.testing.launcher.AppLauncher;
import io.argos.agent.testing.launcher.RunningApp;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * T-CH-005 "에이전트 로그 디스크 풀" (18 §11.1, "로그 디렉터리 tmpfs 1MB"): the scenario's own shop-boot3 runs in a
 * container (JDK 21 image pinned by digest, the run's Docker network, the collector through
 * toxiproxy's alias) with the agent's {@code log.dir} on a 1 MB tmpfs. The fault fills the tmpfs
 * ({@code dd} until it is full) and, so that the agent has something to log, cuts the collector
 * connection for the first half of the fault (toxiproxy proxy disabled: the agent's WARN "collector
 * connection failed"). Extra rows: the agent's records reach the container's stderr instead (the
 * fallback works and the agent does not go silent), at most 10 lines per minute, the application
 * answers every request and its container keeps running ("앱 영향 0"; the p99 row is the common one).
 */
public final class LogDiskFullFault implements Fault {

  /** The scenario. */
  public static final String ID = "T-CH-005";

  /** The agent's log directory inside the container (a tmpfs). */
  static final String LOG_DIR = "/argos/logs";

  /** The tmpfs options (18 §11.1: 1 MB). */
  static final String TMPFS = "size=1m";

  /** The file that fills the tmpfs. */
  static final String FILL = LOG_DIR + "/fill";

  /** tmpfs page size (x86-64 and arm64 Linux containers). */
  static final int PAGE = 4096;

  /**
   * Fills the tmpfs: {@code dd} until no page is left, then blanks to the end of the agent log
   * file's last page (a tmpfs file keeps the rest of its last page, so the agent's next lines would
   * still fit without a new page; the padding is one line of spaces, which is no log record, and
   * needs no new page itself). Prints {@code df -k} and the log file's size.
   */
  static final String FILL_SCRIPT =
      "dd if=/dev/zero of="
          + FILL
          + " bs=4k 2>/dev/null; f="
          + LOG_DIR
          + "/argos-agent.log; s=$(wc -c < $f); p=$(( ("
          + PAGE
          + " - s % "
          + PAGE
          + ") % "
          + PAGE
          + " )); if [ $p -gt 1 ]; then head -c $((p - 1)) /dev/zero | tr '\\000' ' ' >> $f; fi;"
          + " if [ $p -gt 0 ]; then echo >> $f; fi; sync; df -k "
          + LOG_DIR
          + "; wc -c < $f";

  private ChaosApp app;
  private StderrFallback fallback;
  private String dfAfterFill = "";
  private boolean aliveAtEnd;

  @Override
  public String describe() {
    return "container app with log.dir on a 1 MB tmpfs: tmpfs filled (dd), collector connection"
        + " cut for the first half so that the agent logs";
  }

  @Override
  public ChaosApp prepare(ScenarioContext c) throws Exception {
    ChaosStack stack = c.stack();
    String address = stack.toxiproxy().containerAddress();
    if (address == null) {
      throw new IllegalStateException("toxiproxy is not on the run's Docker network");
    }
    ChaosApp started =
        stack.startApp(
            "argos-chaos-logfull",
            "chaos-logfull",
            address,
            Map.of("log.dir", LOG_DIR),
            tmpfs(),
            stack.work().resolve(ID).resolve("app"),
            true);
    String container = ChaosStack.containerId(started);
    app = started.withLog(() -> readLog(started, container));
    stack.use(app);
    stack.awaitInstanceUp(Duration.ofMinutes(2));
    ChaosSession.log(ID + ": container app up in " + app.readyTime().toSeconds() + " s; warm-up");
    warmUp(c);
    return app;
  }

  private static void warmUp(ScenarioContext c) throws Exception {
    Duration w = c.options().profile().warmUp();
    try (LoadRun load =
        LoadRun.start(
            java.nio.file.Paths.get(System.getProperty("java.home"), "bin", "java"),
            c.options().loadJar(),
            c.stack().appUrl(),
            c.options().rate(),
            w,
            c.stack().work().resolve(ID).resolve("warm-up"))) {
      load.await(w.plus(Duration.ofMinutes(2)));
    }
  }

  /** The launcher setting: {@link #LOG_DIR} on a tmpfs of {@link #TMPFS}. */
  static UnaryOperator<AppLauncher> tmpfs() {
    return launcher -> launcher.containerTmpfs(LOG_DIR, TMPFS);
  }

  /** The agent log file in the tmpfs ({@code docker exec cat}) and the records on stderr. */
  static AgentLog readLog(ChaosApp app, String container) {
    StringBuilder text = new StringBuilder();
    try {
      ChaosStack.Exec cat =
          ChaosStack.exec(container, "sh", "-c", "cat " + LOG_DIR + "/argos-agent*.log*");
      text.append(cat.stdout()).append('\n');
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (RuntimeException e) {
      // the container is gone: stderr only
    }
    try {
      text.append(String.join("\n", app.app().stderrLines()));
    } catch (RuntimeException e) {
      // no logs
    }
    return AgentLog.parse(text.toString());
  }

  @Override
  public void inject(ScenarioContext c) throws Exception {
    String container = ChaosStack.containerId(app);
    ChaosStack.Exec fill = ChaosStack.exec(container, "sh", "-c", FILL_SCRIPT);
    dfAfterFill = fill.stdout().strip();
    c.put("filledAt", Instant.now());
    c.note("tmpfs after the fill (df -k, log bytes): " + dfAfterFill.replace('\n', ' '));
    c.stack().toxiproxy().client().setEnabled(Toxiproxy.PROXY, false);
  }

  @Override
  public void during(ScenarioContext c, Instant until) throws Exception {
    Fault.sleepUntil(c.faultStart().plus(c.faultLength().dividedBy(2)));
    c.stack().toxiproxy().client().setEnabled(Toxiproxy.PROXY, true);
    Fault.sleepUntil(until);
  }

  @Override
  public void remove(ScenarioContext c) throws Exception {
    c.stack().toxiproxy().client().setEnabled(Toxiproxy.PROXY, true);
    if (app != null) {
      ChaosStack.exec(ChaosStack.containerId(app), "rm", "-f", FILL);
    }
  }

  @Override
  public List<Check> checks(ScenarioContext c) {
    List<Check> rows = new ArrayList<>();
    RunningApp running = app.app();
    aliveAtEnd = running.isAlive();
    fallback = StderrFallback.parse(String.join("\n", running.stderrLines()));
    Instant filled = (Instant) c.get("filledAt");
    List<AgentLog.Line> afterFill = filled == null ? List.of() : fallback.since(filled);
    rows.add(
        new Check(
            c.id(),
            "agent records on stderr after the log disk filled (fallback, not silent)",
            ">= 1 record on the container's stderr",
            afterFill.size()
                + (afterFill.isEmpty()
                    ? ""
                    : ": " + afterFill.get(0).level() + " " + afterFill.get(0).message()),
            Check.Kind.INVARIANT,
            Judge.invariant(!afterFill.isEmpty()),
            "the connection cut makes the agent WARN; its log file cannot take it"));
    rows.add(
        new Check(
            c.id(),
            "stderr fallback lines per minute",
            "<= " + StderrFallback.LINES_PER_MINUTE,
            "max " + fallback.maxPerMinute() + " (windows " + fallback.perMinute() + ")",
            Check.Kind.INVARIANT,
            Judge.invariant(fallback.withinCap()),
            "agent records on the container's stderr, the sink's one-minute windows"));
    LoadSummary load = c.load();
    rows.add(
        new Check(
            c.id(),
            "application answered every request",
            "0 requests without an answer, container running",
            load == null
                ? "no load summary"
                : String.format(
                    Locale.ROOT,
                    "%d sent, %d answered, %d without answer; container %s",
                    load.sent(),
                    load.answered(),
                    load.failedWithoutAnswer(),
                    aliveAtEnd ? "running" : "stopped"),
            Check.Kind.INVARIANT,
            Judge.invariant(load != null && load.failedWithoutAnswer() == 0 && aliveAtEnd),
            "sample-apps/load summary of the whole scenario"));
    rows.add(
        new Check(
            c.id(),
            "log tmpfs after the fill",
            "full",
            dfAfterFill.replace('\n', ' '),
            Check.Kind.INFO,
            Verdict.INFO,
            "df -k " + LOG_DIR));
    return rows;
  }

  @Override
  public void cleanup(ScenarioContext c) {
    if (app == null) {
      return;
    }
    try {
      Files.writeString(
          c.stack().work().resolve(ID).resolve("container-stderr.log"),
          String.join("\n", app.app().stderrLines()),
          StandardCharsets.UTF_8);
      Files.writeString(
          c.stack().work().resolve(ID).resolve("agent.log"),
          String.join(
              "\n",
              app.agentLog().lines().stream()
                  .map(l -> l.time() + " " + l.level() + " [" + l.thread() + "] " + l.message())
                  .toList()),
          StandardCharsets.UTF_8);
    } catch (Exception e) {
      c.note("container logs not saved: " + e);
    }
    try {
      c.stack().toxiproxy().client().setEnabled(Toxiproxy.PROXY, true);
    } catch (RuntimeException e) {
      // already enabled
    }
    app.close();
    ChaosStack.deleteAgentSecrets(app);
  }
}
