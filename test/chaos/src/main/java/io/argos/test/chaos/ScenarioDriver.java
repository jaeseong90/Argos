/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.chaos;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;

/**
 * Runs one scenario with the phases and the common verdicts of 18 §11.1:
 *
 * <ol>
 *   <li>quiet: the application's xlog count is stable; counts before;
 *   <li>one load generator run over baseline + fault + recovery ({@link LoadRun});
 *   <li>baseline window, {@link Fault#inject}, {@link Fault#during} the profile's fault length,
 *       {@link Fault#remove};
 *   <li>recovery window: the instance up on a new heartbeat and the first new row ({@link
 *       Recovery}), watched while the load runs and during the drain;
 *   <li>drain: until every transaction is stored or counted ({@link DropAccounting}) or nothing
 *       changes for 30 s (at most the profile's drain timeout); counts after;
 *   <li>the common rows: app p99 within the noise band ({@link NoiseBand}, MEASUREMENT), recovery
 *       within 60 s (MEASUREMENT), collection resumed, agent ERROR 0, drop accounting, duplicates
 *       0, drops and the expected outage shown by {@code collection-health} (INVARIANT), and the
 *       retransformation failure lines of the agent log (C-257 (2), INFO); then the fault's rows.
 * </ol>
 */
public final class ScenarioDriver {

  /** Nothing may change this long before the drain ends early. */
  static final Duration STABLE = Duration.ofSeconds(30);

  /** How long collection-health may take to show the drops (metric cadence). */
  static final Duration SHOW_TIMEOUT = Duration.ofSeconds(90);

  private final ChaosStack stack;
  private final ChaosOptions options;
  private final Consumer<String> log;

  public ScenarioDriver(ChaosStack stack, ChaosOptions options, Consumer<String> log) {
    this.stack = stack;
    this.options = options;
    this.log = log;
  }

  /**
   * Runs scenario {@code id} with {@code fault} and judges it. A fault with its own application
   * ({@link Fault#prepare}) is measured on that application; {@link Fault#cleanup} always runs and
   * the probes return to the main app.
   */
  public ScenarioResult run(String id, String title, Fault fault) throws Exception {
    ChaosProfile p = options.profile();
    Duration faultLength = p.fault(id);
    ScenarioContext c = new ScenarioContext(id, stack, options, faultLength);
    log.accept(id + ": " + fault.describe() + " (fault " + faultLength + ")");
    try {
      ChaosApp own = fault.prepare(c);
      if (own != null) {
        stack.use(own);
        log.accept(id + ": measuring " + own);
      }
      if (fault instanceof StandaloneFault standalone) {
        return standalone(c, id, title, standalone);
      }
      return phased(c, id, title, fault);
    } finally {
      try {
        fault.cleanup(c);
      } catch (RuntimeException e) {
        log.accept(id + ": cleanup failed: " + e);
      } finally {
        stack.useMain();
      }
    }
  }

  private ScenarioResult standalone(
      ScenarioContext c, String id, String title, StandaloneFault fault) throws Exception {
    c.start = Instant.now();
    List<Check> checks = new ArrayList<>(fault.run(c));
    c.end = Instant.now();
    Verdict verdict = Judge.scenario(checks);
    log.accept(id + ": " + verdict);
    return new ScenarioResult(
        id,
        title,
        fault.describe(),
        verdict,
        "",
        checks,
        c.notes(),
        c.start,
        c.faultStart,
        c.faultEnd,
        c.end);
  }

  private ScenarioResult phased(ScenarioContext c, String id, String title, Fault fault)
      throws Exception {
    ChaosProfile p = options.profile();
    Duration faultLength = c.faultLength();
    awaitQuiet();
    c.start = Instant.now();
    c.xlogBefore = stack.xlogCount();
    c.countsBefore = new AgentCounts(stack.agentCounters());
    Duration total =
        p.baseline().plus(faultLength).plus(fault.removalAllowance(stack)).plus(p.recovery());
    Path dir = stack.work().resolve(id);
    Recovery recovery;
    try (LoadRun load =
        LoadRun.start(java(), options.loadJar(), stack.appUrl(), options.rate(), total, dir)) {
      Fault.sleepUntil(load.started().plus(p.baseline()));
      c.faultStart = Instant.now();
      log.accept(id + ": fault on");
      try {
        fault.inject(c);
        fault.during(c, c.faultStart.plus(faultLength));
      } finally {
        // Always undone, so that a failed scenario does not leave the stack broken for the next.
        fault.remove(c);
      }
      c.faultEnd = Instant.now();
      log.accept(id + ": fault off");
      Instant loadEnd = load.started().plus(total).plus(LoadRun.INTERVAL);
      recovery = watchRecovery(c.faultEnd, null, null, loadEnd);
      c.load = load.await(Duration.ofMinutes(3).plus(Duration.between(Instant.now(), loadEnd)));
      c.intervals = load.intervals();
    }
    recovery = drain(c, recovery);
    c.end = Instant.now();
    List<Check> checks = new ArrayList<>(commonChecks(c, fault, recovery));
    checks.addAll(fault.checks(c));
    Verdict verdict = Judge.scenario(checks);
    log.accept(id + ": " + verdict);
    return new ScenarioResult(
        id,
        title,
        fault.describe(),
        verdict,
        "",
        checks,
        c.notes(),
        c.start,
        c.faultStart,
        c.faultEnd,
        c.end);
  }

  private static Path java() {
    return Paths.get(System.getProperty("java.home"), "bin", "java");
  }

  /** Waits until the application's xlog count has not changed for 10 s (at most 2 minutes). */
  private void awaitQuiet() throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
    long last = -1;
    int same = 0;
    while (System.nanoTime() < deadline && same < 2) {
      long now = stack.xlogCount();
      same = now == last ? same + 1 : 0;
      last = now;
      Thread.sleep(5_000);
    }
  }

  /** Polls until the instance is back and a new row exists, or {@code until}. */
  private Recovery watchRecovery(Instant faultEnd, Instant up, Instant rows, Instant until)
      throws InterruptedException {
    Instant instanceUp = up;
    Instant newRows = rows;
    while ((instanceUp == null || newRows == null) && Instant.now().isBefore(until)) {
      if (instanceUp == null && instanceBack(faultEnd)) {
        instanceUp = Instant.now();
      }
      if (newRows == null && rowsSince(faultEnd)) {
        newRows = Instant.now();
      }
      if (instanceUp == null || newRows == null) {
        Thread.sleep(1_000);
      }
    }
    return new Recovery(faultEnd, instanceUp, newRows);
  }

  private boolean instanceBack(Instant faultEnd) {
    try {
      JsonNode i = stack.instances().path(0);
      if (i.isMissingNode()) {
        return false;
      }
      JsonNode hb = i.path("last_heartbeat_at");
      Instant heartbeat =
          hb.isNull() || hb.isMissingNode()
              ? null
              : OffsetDateTime.parse(hb.asString()).toInstant();
      return Recovery.instanceBack(i.path("state").asString(), heartbeat, faultEnd);
    } catch (RuntimeException e) {
      return false;
    }
  }

  private boolean rowsSince(Instant faultEnd) {
    try {
      return stack.xlogSince(faultEnd) > 0;
    } catch (RuntimeException e) {
      return false;
    }
  }

  /**
   * Waits until every transaction is stored or counted, or nothing changes for {@link #STABLE}, at
   * most the profile's drain timeout; keeps watching the recovery meanwhile.
   */
  private Recovery drain(ScenarioContext c, Recovery recovery) throws InterruptedException {
    long deadline = System.nanoTime() + options.profile().drainTimeout().toNanos();
    Recovery r = recovery;
    long lastXlog = -1;
    long lastAccounted = -1;
    Instant stableSince = Instant.now();
    while (System.nanoTime() < deadline) {
      if (!r.resumed()) {
        r = watchRecovery(r.faultEnd(), r.instanceUp(), r.newRows(), Instant.now().plusSeconds(1));
      }
      long xlog = stack.xlogCount();
      AgentCounts counts = new AgentCounts(stack.agentCounters());
      DropAccounting a = accounting(c, xlog, counts);
      if (a.holds() && r.resumed()) {
        break;
      }
      if (xlog != lastXlog || a.accounted() != lastAccounted) {
        stableSince = Instant.now();
        lastXlog = xlog;
        lastAccounted = a.accounted();
      } else if (Duration.between(stableSince, Instant.now()).compareTo(STABLE) >= 0
          && r.resumed()) {
        break;
      }
      Thread.sleep(5_000);
    }
    c.xlogAfter = stack.xlogCount();
    c.countsAfter = new AgentCounts(stack.agentCounters());
    return r;
  }

  /** The drop accounting of the scenario with the given counts after it. */
  static DropAccounting accounting(ScenarioContext c, long xlogAfter, AgentCounts after) {
    long agent =
        AgentCounts.delta(c.countsBefore, after, AgentCounts.DROPPED_TRANSACTIONS).orElse(0);
    long server =
        AgentCounts.delta(c.countsBefore, after, AgentCounts.SERVER_DROPPED_TRANSACTIONS).orElse(0);
    long min = c.load == null ? 0 : c.load.minTransactions();
    long max = c.load == null ? 0 : c.load.maxTransactions();
    return new DropAccounting(xlogAfter - c.xlogBefore, agent, server, min, max);
  }

  private List<Check> commonChecks(ScenarioContext c, Fault fault, Recovery recovery) {
    List<Check> rows = new ArrayList<>();
    String id = c.id();
    boolean valid = options.valid();
    String invalid = Judge.invalidNote(options);

    // App p99 within the noise band of the baseline (first interval left out: generator start).
    List<Double> baseline = new ArrayList<>();
    List<Double> faultP99 = new ArrayList<>();
    boolean first = true;
    for (LoadInterval i : c.intervals) {
      if (i.end().isAfter(c.faultStart)) {
        if (i.within(c.faultStart, c.faultEnd)) {
          faultP99.add(i.p99());
        }
        continue;
      }
      if (first) {
        first = false;
        continue;
      }
      baseline.add(i.p99());
    }
    NoiseBand band = new NoiseBand(baseline);
    double faultMedian = NoiseBand.median(faultP99);
    boolean measured = band.defined() && !Double.isNaN(faultMedian);
    rows.add(
        new Check(
            id,
            "app p99 during the fault within the baseline noise band",
            "median of the fault intervals' p99 <= band upper (README: noise band)",
            String.format(
                Locale.ROOT,
                "fault p99 median %.1f ms (%d intervals); %s",
                faultMedian,
                faultP99.size(),
                band.describe()),
            Check.Kind.MEASUREMENT,
            Judge.measurement(valid, measured, band.contains(faultMedian)),
            valid ? "" : invalid));

    // Recovery.
    rows.add(
        new Check(
            id,
            "recovery: instance up on a new heartbeat and new rows stored",
            "<= 60 s after the fault is removed",
            recovery.time().map(t -> t.toMillis() / 1000.0 + " s").orElse("not resumed"),
            Check.Kind.MEASUREMENT,
            Judge.measurement(valid, recovery.resumed(), recovery.withinTarget()),
            valid ? "" : invalid));
    rows.add(
        new Check(
            id,
            "collection resumed after the fault",
            "instance UP/WARN on a new heartbeat and new xlog rows",
            "instance "
                + (recovery.instanceUp() == null ? "not back" : "back")
                + ", new rows "
                + (recovery.newRows() == null ? "none" : "stored"),
            Check.Kind.INVARIANT,
            Judge.invariant(recovery.resumed()),
            ""));

    // Agent log.
    AgentLog log = stack.agentLog();
    List<AgentLog.Line> errors =
        log.errors(c.start, c.end).stream().filter(l -> !fault.errorExpected(l)).toList();
    rows.add(
        new Check(
            id,
            "agent log ERROR lines",
            "0",
            errors.size() + (errors.isEmpty() ? "" : ": " + errors.get(0).message()),
            Check.Kind.INVARIANT,
            Judge.invariant(errors.isEmpty()),
            ""));
    List<AgentLog.Line> retransform = log.retransformFailures(c.start, c.end);
    rows.add(
        new Check(
            id,
            "agent log retransformation failure lines (C-257 (2))",
            "reported, not silent",
            retransform.size() + (retransform.isEmpty() ? "" : ": " + retransform.get(0).message()),
            Check.Kind.INFO,
            Verdict.INFO,
            "agent-core RetransformFailureListener WARN"));

    // Drop accounting (D-13, D-06).
    DropAccounting a = accounting(c, c.xlogAfter, c.countsAfter);
    boolean counted =
        c.countsAfter.get(AgentCounts.DROPPED_TRANSACTIONS).isPresent()
            && c.countsBefore.get(AgentCounts.DROPPED_TRANSACTIONS).isPresent();
    rows.add(
        new Check(
            id,
            "every transaction stored or counted as dropped (D-13)",
            "stored + agent drops + server drops = transactions",
            a.describe(),
            Check.Kind.INVARIANT,
            Judge.invariant(counted && a.holds()),
            counted
                ? (a.exact() ? "exact" : "requests without an answer: a range")
                : "the agent's self-diagnostics did not report the drop counter"));

    // Duplicates (D-13): every tx_id once.
    long rowsTotal = stack.xlogCount();
    long unique = stack.xlogUniqueTransactions();
    rows.add(
        new Check(
            id,
            "no duplicate rows after resends (D-13)",
            "count(xlog) = uniqExact(tx_id)",
            rowsTotal + " rows, " + unique + " transactions",
            Check.Kind.INVARIANT,
            Judge.invariant(rowsTotal == unique),
            "application total since the run started"));

    // What collection-health shows.
    rows.addAll(shown(c, fault, a));
    return rows;
  }

  private List<Check> shown(ScenarioContext c, Fault fault, DropAccounting a) {
    List<Check> rows = new ArrayList<>();
    String id = c.id();
    String expectedOutage = fault.expectedOutage(c);
    String skip = fault.outageSkipReason(c);
    long deadline = System.nanoTime() + SHOW_TIMEOUT.toNanos();
    HealthView view = null;
    String error = null;
    while (true) {
      try {
        view = HealthView.of(stack.collectionHealth(c.start, Instant.now()));
        error = null;
      } catch (RuntimeException e) {
        error = e.getMessage();
      }
      boolean dropsOk = view != null && (a.agentDropped() == 0 || view.agentDrops() > 0);
      boolean outageOk =
          view != null
              && (expectedOutage == null
                  || skip != null
                  || view.hasOutage(
                      expectedOutage, c.faultStart, c.faultEnd.plus(Recovery.TARGET)));
      if ((dropsOk && outageOk) || System.nanoTime() > deadline) {
        break;
      }
      try {
        Thread.sleep(5_000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    if (view == null) {
      rows.add(
          new Check(
              id,
              "collection-health answered",
              "200",
              String.valueOf(error),
              Check.Kind.INVARIANT,
              Verdict.FAIL,
              ""));
      return rows;
    }
    if (a.agentDropped() > 0) {
      rows.add(
          new Check(
              id,
              "agent drops shown in collection-health",
              "agent_drops > 0 when the agent dropped",
              String.format(
                  Locale.ROOT,
                  "agent_drops %.0f (agent counted %d)",
                  view.agentDrops(),
                  a.agentDropped()),
              Check.Kind.INVARIANT,
              Judge.invariant(view.agentDrops() > 0),
              ""));
    } else {
      rows.add(
          new Check(
              id,
              "agent drops shown in collection-health",
              "agent_drops > 0 when the agent dropped",
              String.format(Locale.ROOT, "no agent drops; agent_drops %.0f", view.agentDrops()),
              Check.Kind.INFO,
              Verdict.INFO,
              ""));
    }
    if (a.serverDropped() > 0) {
      rows.add(
          new Check(
              id,
              "server drops shown in collection-health",
              "server_drops > 0 when the server dropped",
              String.format(
                  Locale.ROOT,
                  "server_drops %.0f, quota %.0f (agent saw %d)",
                  view.serverDrops(),
                  view.serverDropsQuota(),
                  a.serverDropped()),
              Check.Kind.INVARIANT,
              Judge.invariant(view.serverDrops() > 0),
              ""));
    }
    String outages =
        view.outages().isEmpty()
            ? "none"
            : String.join(
                ", ",
                view.outages().stream()
                    .map(o -> o.reason() + " " + o.from() + ".." + (o.to() == null ? "" : o.to()))
                    .toList());
    if (expectedOutage == null) {
      rows.add(
          new Check(
              id, "outages in collection-health", "-", outages, Check.Kind.INFO, Verdict.INFO, ""));
    } else if (skip != null) {
      rows.add(
          new Check(
              id,
              "outage " + expectedOutage + " shown in collection-health",
              "an outage overlapping the fault",
              outages,
              Check.Kind.INVARIANT,
              Verdict.SKIPPED,
              skip));
    } else {
      rows.add(
          new Check(
              id,
              "outage " + expectedOutage + " shown in collection-health",
              "an outage overlapping the fault",
              outages,
              Check.Kind.INVARIANT,
              Judge.invariant(
                  view.hasOutage(expectedOutage, c.faultStart, c.faultEnd.plus(Recovery.TARGET))),
              ""));
    }
    return rows;
  }

  /** {@code after - before} of {@code key}, or -1 when not reported. */
  static long delta(ScenarioContext c, String key) {
    OptionalLong d = AgentCounts.delta(c.countsBefore, c.countsAfter, key);
    return d.orElse(-1);
  }
}
