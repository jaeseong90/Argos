/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.leak;

import io.argos.agent.testing.jvm.ClassHistogram;
import io.argos.agent.testing.jvm.JcmdClient;
import io.argos.agent.testing.launcher.RunningApp;
import io.argos.agent.testing.launcher.SelfDiagnostics;
import java.util.ArrayList;
import java.util.List;

/**
 * The four context-leak checks of 18 §5.9 (T-AG-053, N-02) after a workload:
 *
 * <ol>
 *   <li>ThreadLocal residue: self-diagnostics {@code threadlocal_contexts_live} = 0;
 *   <li>active transaction registry: {@code active_registry_size} = 0;
 *   <li>weak map: {@code weakmap_size} = 0 after GC;
 *   <li>heap residency: {@code jcmd GC.class_histogram}, bytes of {@code io.argos.} classes (shaded
 *       packages included) {@code <=} 64 MB.
 * </ol>
 *
 * Before reading, {@code jcmd GC.run} runs twice. The first three need the agent's self-diagnostics
 * (WO-10): until then they are reported {@link LeakReport.Outcome#NOT_AVAILABLE} with the reason,
 * never as passed. The WAR redeploy and native memory parts of 18 §5.9 are {@link
 * TomcatRedeployDriver} and {@code NmtSummary}.
 */
public final class LeakProbe {
  /** N-02: agent heap residency limit. */
  public static final long HEAP_LIMIT_BYTES = 64L * 1024 * 1024;

  /** Agent classes, shaded dependencies included ({@code io.argos.agent.shaded.}). */
  public static final String AGENT_PREFIX = "io.argos.";

  /** Classes of the agent itself: an attached agent always has live instances of some. */
  public static final String AGENT_OWN_PREFIX = "io.argos.agent.";

  private final JcmdClient jcmd;
  private final SelfDiagnostics diagnostics;
  private final boolean agentAttached;

  /** A probe of a run without a known agent (the histogram is checked for rows only). */
  public LeakProbe(JcmdClient jcmd, SelfDiagnostics diagnostics) {
    this(jcmd, diagnostics, false);
  }

  /**
   * A probe of a run; with {@code agentAttached} the heap check also requires agent instances in
   * the histogram (none means the histogram is not the agent's JVM or was not read).
   */
  public LeakProbe(JcmdClient jcmd, SelfDiagnostics diagnostics, boolean agentAttached) {
    this.jcmd = jcmd;
    this.diagnostics = diagnostics;
    this.agentAttached = agentAttached;
  }

  /** The probe of a running app ({@link RunningApp#agentAttached()}). */
  public static LeakProbe of(RunningApp app) {
    return new LeakProbe(app.jcmd(), app.selfDiagnostics(), app.agentAttached());
  }

  /** {@code GC.run} twice, then the four checks. */
  public LeakReport check() {
    jcmd.gcRun();
    jcmd.gcRun();
    List<LeakReport.Check> checks = new ArrayList<>();
    checks.add(selfDiagnostic(LeakReport.THREADLOCAL, "threadlocal_contexts_live"));
    checks.add(selfDiagnostic(LeakReport.ACTIVE_REGISTRY, "active_registry_size"));
    checks.add(selfDiagnostic(LeakReport.WEAK_MAP, "weakmap_size"));
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
   * a histogram without one {@value #AGENT_OWN_PREFIX} instance.
   */
  public static LeakReport.Check heapResidency(ClassHistogram histogram, boolean agentAttached) {
    if (histogram.rows().isEmpty()) {
      return new LeakReport.Check(
          LeakReport.HEAP_RESIDENCY,
          LeakReport.Outcome.NOT_AVAILABLE,
          -1,
          "the class histogram has no rows (empty or unparsable jcmd GC.class_histogram output)");
    }
    long agentInstances = histogram.instancesOf(AGENT_OWN_PREFIX);
    if (agentAttached && agentInstances == 0) {
      return new LeakReport.Check(
          LeakReport.HEAP_RESIDENCY,
          LeakReport.Outcome.NOT_AVAILABLE,
          -1,
          "the agent is attached but the class histogram ("
              + histogram.rows().size()
              + " rows) has no "
              + AGENT_OWN_PREFIX
              + " instance: not the agent's JVM, or the histogram is incomplete");
    }
    long bytes = histogram.bytesOf(AGENT_PREFIX);
    return new LeakReport.Check(
        LeakReport.HEAP_RESIDENCY,
        bytes <= HEAP_LIMIT_BYTES ? LeakReport.Outcome.PASS : LeakReport.Outcome.FAIL,
        bytes,
        "io.argos.* instances "
            + histogram.instancesOf(AGENT_PREFIX)
            + ", "
            + bytes
            + " bytes (limit "
            + HEAP_LIMIT_BYTES
            + ")");
  }

  private LeakReport.Check selfDiagnostic(String name, String field) {
    if (!diagnostics.available()) {
      return new LeakReport.Check(name, LeakReport.Outcome.NOT_AVAILABLE, -1, diagnostics.reason());
    }
    long value = diagnostics.value(field);
    return new LeakReport.Check(
        name,
        value == 0 ? LeakReport.Outcome.PASS : LeakReport.Outcome.FAIL,
        value,
        field + " = " + value);
  }
}
