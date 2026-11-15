/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.leak;

import io.argos.agent.testing.jvm.ClassHistogram;
import io.argos.agent.testing.jvm.JcmdClient;
import io.argos.agent.testing.launcher.RunningApp;
import io.argos.agent.testing.launcher.SelfDiagnostics;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * The four context-leak checks of 18 §5.9 (T-AG-053, N-02) after a workload:
 *
 * <ol>
 *   <li>ThreadLocal residue: self-diagnostics {@code threadlocal_contexts_live} = 0;
 *   <li>active transaction registry: {@code active_registry_size} = 0;
 *   <li>weak map: {@code weakmap_size} = 0 after GC (the fallback context-store entries that hold a
 *       {@code TraceContext} or {@code Scope}; caches keyed by long-lived objects are {@code
 *       weakmap_entries}, checked for growth by {@code LoadLeakE2eTest});
 *   <li>heap residency: {@code jcmd GC.class_histogram}, bytes of {@value #AGENT_PREFIX} classes
 *       (the agent's own and its shaded dependencies, ADR-0009 C-87; the sample apps' {@code
 *       io.argos.sample.*} are not counted) {@code <=} 64 MB.
 * </ol>
 *
 * Before reading, {@code jcmd GC.run} runs twice; the self-diagnostics are a snapshot the agent
 * wrote after that ({@link RunningApp#awaitSelfDiagnostics}: the agent expunges collected weak keys
 * before it measures). A value the agent does not report is {@link
 * LeakReport.Outcome#NOT_AVAILABLE} with the reason, never passed. The WAR redeploy and native
 * memory parts of 18 §5.9 are {@link TomcatRedeployDriver} and {@code NmtSummary}.
 */
public final class LeakProbe {
  /** N-02: agent heap residency limit. */
  public static final long HEAP_LIMIT_BYTES = 64L * 1024 * 1024;

  /**
   * Classes whose instances count as agent heap (ADR-0009 C-87): the agent's own classes and its
   * shaded dependencies ({@code io.argos.agent.shaded.}).
   */
  public static final String AGENT_PREFIX = "io.argos.agent.";

  /** How long to wait for a self-diagnostics snapshot written after the GC (written every 2 s). */
  public static final Duration SNAPSHOT_WAIT = Duration.ofSeconds(15);

  private final JcmdClient jcmd;
  private final Function<Instant, SelfDiagnostics> diagnostics;
  private final boolean agentAttached;

  /** A probe of a run without a known agent (the histogram is checked for rows only). */
  public LeakProbe(JcmdClient jcmd, SelfDiagnostics diagnostics) {
    this(jcmd, diagnostics, false);
  }

  /**
   * A probe with fixed self-diagnostics; with {@code agentAttached} the heap check also requires
   * agent instances in the histogram (none means the histogram is not the agent's JVM or was not
   * read).
   */
  public LeakProbe(JcmdClient jcmd, SelfDiagnostics diagnostics, boolean agentAttached) {
    this(jcmd, after -> diagnostics, agentAttached);
  }

  /**
   * A probe that reads the self-diagnostics after the GC: {@code diagnostics} gets the instant the
   * second {@code GC.run} completed and returns a snapshot taken after it.
   */
  public LeakProbe(
      JcmdClient jcmd, Function<Instant, SelfDiagnostics> diagnostics, boolean agentAttached) {
    this.jcmd = jcmd;
    this.diagnostics = diagnostics;
    this.agentAttached = agentAttached;
  }

  /** The probe of a running app ({@link RunningApp#agentAttached()}). */
  public static LeakProbe of(RunningApp app) {
    return new LeakProbe(
        app.jcmd(), after -> app.awaitSelfDiagnostics(after, SNAPSHOT_WAIT), app.agentAttached());
  }

  /** {@code GC.run} twice, then the four checks. */
  public LeakReport check() {
    jcmd.gcRun();
    jcmd.gcRun();
    SelfDiagnostics snapshot = diagnostics.apply(Instant.now());
    List<LeakReport.Check> checks = new ArrayList<>();
    checks.add(selfDiagnostic(snapshot, LeakReport.THREADLOCAL, "threadlocal_contexts_live"));
    checks.add(selfDiagnostic(snapshot, LeakReport.ACTIVE_REGISTRY, "active_registry_size"));
    checks.add(selfDiagnostic(snapshot, LeakReport.WEAK_MAP, "weakmap_size"));
    checks.add(heapResidency(jcmd.classHistogram(), agentAttached));
    return new LeakReport(checks);
  }

  /** Check 4 on a histogram of a run without a known agent (also for recorded histograms). */
  public static LeakReport.Check heapResidency(ClassHistogram histogram) {
    return heapResidency(histogram, false);
  }

  /**
   * Check 4 on a histogram (also for unit tests and recorded histograms). Without a measured value
   * it is {@link LeakReport.Outcome#NOT_AVAILABLE}, never a pass (18 §7.6): a histogram without
   * rows (empty or unparsable jcmd output), and, for a run with the agent ({@code agentAttached}),
   * a histogram without one {@value #AGENT_PREFIX} instance.
   */
  public static LeakReport.Check heapResidency(ClassHistogram histogram, boolean agentAttached) {
    if (histogram.rows().isEmpty()) {
      return new LeakReport.Check(
          LeakReport.HEAP_RESIDENCY,
          LeakReport.Outcome.NOT_AVAILABLE,
          -1,
          "the class histogram has no rows (empty or unparsable jcmd GC.class_histogram output)");
    }
    long agentInstances = histogram.instancesOf(AGENT_PREFIX);
    if (agentAttached && agentInstances == 0) {
      return new LeakReport.Check(
          LeakReport.HEAP_RESIDENCY,
          LeakReport.Outcome.NOT_AVAILABLE,
          -1,
          "the agent is attached but the class histogram ("
              + histogram.rows().size()
              + " rows) has no "
              + AGENT_PREFIX
              + " instance: not the agent's JVM, or the histogram is incomplete");
    }
    long bytes = histogram.bytesOf(AGENT_PREFIX);
    return new LeakReport.Check(
        LeakReport.HEAP_RESIDENCY,
        bytes <= HEAP_LIMIT_BYTES ? LeakReport.Outcome.PASS : LeakReport.Outcome.FAIL,
        bytes,
        AGENT_PREFIX
            + "* instances "
            + agentInstances
            + ", "
            + bytes
            + " bytes (limit "
            + HEAP_LIMIT_BYTES
            + ", C-87)");
  }

  private static LeakReport.Check selfDiagnostic(
      SelfDiagnostics diagnostics, String name, String field) {
    if (!diagnostics.available()) {
      return new LeakReport.Check(name, LeakReport.Outcome.NOT_AVAILABLE, -1, diagnostics.reason());
    }
    long value;
    try {
      value = diagnostics.value(field);
    } catch (SelfDiagnostics.NotAvailableException e) {
      return new LeakReport.Check(name, LeakReport.Outcome.NOT_AVAILABLE, -1, e.getMessage());
    }
    return new LeakReport.Check(
        name,
        value == 0 ? LeakReport.Outcome.PASS : LeakReport.Outcome.FAIL,
        value,
        field + " = " + value);
  }
}
