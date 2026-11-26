/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.chaos;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;

/**
 * T-CH-002 "지연 500ms" (18 §11.1): a toxiproxy {@code latency} toxic of 500 ms on both directions.
 * Extra rows:
 *
 * <ul>
 *   <li>in-flight bound: with at most {@code sender.max-in-flight} (4) batches in flight and a
 *       round trip of at least 1 s (500 ms each way), the server can acknowledge at most 4 batches
 *       per second; the measured rate of {@code argos_agent_batches_sent_total} (every kind) during
 *       the fault must stay within that, plus 10 % for batches already on the way when the toxic
 *       was added;
 *   <li>agent heap: the bytes of the agent's classes ({@code io.argos.agent.}, {@code
 *       GC.class_histogram} after a full GC) grow by at most the queue bound {@code
 *       buffer.max-bytes} (32 MB) between the start and the end of the fault.
 * </ul>
 */
public final class LatencyFault implements Fault {

  /** Delay per direction. */
  static final long LATENCY_MS = 500;

  /** {@code sender.max-in-flight} default. */
  static final int MAX_IN_FLIGHT = 4;

  /** {@code buffer.max-bytes} default (32 MB). */
  static final long QUEUE_BYTES = 32L * 1024 * 1024;

  /** Allowance on the acknowledged-batch rate. */
  static final double RATE_ALLOWANCE = 1.10;

  @Override
  public String describe() {
    return "toxiproxy latency " + LATENCY_MS + " ms upstream and downstream";
  }

  @Override
  public void inject(ScenarioContext c) {
    c.put("heapBefore", c.stack().agentHeapBytes());
    ToxiproxyClient t = c.stack().toxiproxy().client();
    t.addToxic(Toxiproxy.PROXY, ToxiproxyClient.Toxic.latency("lat-up", "upstream", LATENCY_MS));
    t.addToxic(
        Toxiproxy.PROXY, ToxiproxyClient.Toxic.latency("lat-down", "downstream", LATENCY_MS));
    c.put("countsOn", new AgentCounts(c.stack().agentCounters()));
    c.put("timeOn", Instant.now());
  }

  @Override
  public void remove(ScenarioContext c) {
    c.put("countsOff", new AgentCounts(c.stack().agentCounters()));
    c.put("timeOff", Instant.now());
    c.put("heapAfter", c.stack().agentHeapBytes());
    c.stack().toxiproxy().client().reset();
  }

  /** The most batches per second the in-flight bound allows for a round trip of {@code rtt}. */
  static double maxAckRate(int maxInFlight, Duration rtt) {
    return maxInFlight / (rtt.toMillis() / 1000.0);
  }

  @Override
  public List<Check> checks(ScenarioContext c) {
    List<Check> rows = new ArrayList<>();
    AgentCounts on = (AgentCounts) c.get("countsOn");
    AgentCounts off = (AgentCounts) c.get("countsOff");
    Instant t0 = (Instant) c.get("timeOn");
    Instant t1 = (Instant) c.get("timeOff");
    OptionalLong batches = AgentCounts.sumDelta(on, off, AgentCounts.BATCHES_SENT);
    double seconds = Duration.between(t0, t1).toMillis() / 1000.0;
    double limit = maxAckRate(MAX_IN_FLIGHT, Duration.ofMillis(2 * LATENCY_MS)) * RATE_ALLOWANCE;
    double rate = batches.isPresent() && seconds > 0 ? batches.getAsLong() / seconds : Double.NaN;
    rows.add(
        new Check(
            c.id(),
            "in-flight bound kept (acknowledged batches per second)",
            String.format(Locale.ROOT, "<= %.1f/s (4 in flight, RTT >= 1 s, +10 %%)", limit),
            String.format(
                Locale.ROOT,
                "%.2f/s (%s batches in %.0f s)",
                rate,
                batches.isPresent() ? Long.toString(batches.getAsLong()) : "-",
                seconds),
            Check.Kind.INVARIANT,
            Judge.invariant(!Double.isNaN(rate) && rate <= limit && batches.getAsLong() > 0),
            "argos_agent_batches_sent_total, every kind; 0 batches would mean nothing was sent"));
    long before = (Long) c.get("heapBefore");
    long after = (Long) c.get("heapAfter");
    long growth = after - before;
    rows.add(
        new Check(
            c.id(),
            "agent heap growth during the fault",
            "<= queue bound buffer.max-bytes (32 MB)",
            String.format(
                Locale.ROOT,
                "%+.1f MiB (%.1f -> %.1f MiB of %s classes)",
                growth / 1048576.0,
                before / 1048576.0,
                after / 1048576.0,
                ChaosStack.AGENT_PACKAGE),
            Check.Kind.INVARIANT,
            Judge.invariant(growth <= QUEUE_BYTES),
            "jcmd GC.class_histogram (full GC) at the start and the end of the fault"));
    return rows;
  }
}
