/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.chaos;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

/**
 * T-CH-003 "손실 10%" (18 §11.1: toxiproxy {@code limit_data}/{@code timeout}). toxiproxy works on
 * TCP streams, so loss means broken connections, not dropped packets:
 *
 * <ul>
 *   <li>standing toxics with toxicity 0.1 (each applies to 10 % of the connections): {@code
 *       limit_data} upstream (the connection closes after 256 KiB) and {@code reset_peer}
 *       downstream (TCP reset after 3 s);
 *   <li>a cutter that, in every 10 s cycle, adds a {@code timeout} toxic (data stops, the
 *       connection closes after 500 ms) on both directions for 1 s: the link is broken 10 % of the
 *       time and the agent reconnects, so new connections meet the standing toxics.
 * </ul>
 *
 * Broken connections fail RPCs whose batches may already be stored: the agent resends them with the
 * same {@code seq} and the server must not store them twice (the common duplicate row, D-13). Extra
 * rows (references): the resends, the new sessions and the cuts.
 */
public final class LossFault implements Fault {

  /** One cycle of the cutter. */
  static final Duration CYCLE = Duration.ofSeconds(10);

  /** How long the link is cut per cycle (10 % of {@link #CYCLE}). */
  static final Duration CUT = Duration.ofSeconds(1);

  /** Share of connections the standing toxics apply to. */
  static final double TOXICITY = 0.1;

  @Override
  public String describe() {
    return "toxiproxy limit_data 256 KiB upstream and reset_peer 3 s downstream at toxicity 0.1,"
        + " plus a timeout toxic (500 ms) on both directions for 1 s of every 10 s";
  }

  /** The standing toxics. */
  static List<ToxiproxyClient.Toxic> standing() {
    return List.of(
        ToxiproxyClient.Toxic.limitData("loss-limit", "upstream", 256 * 1024, TOXICITY),
        ToxiproxyClient.Toxic.resetPeer("loss-reset", "downstream", 3_000, TOXICITY));
  }

  /** The cutter's toxics. */
  static List<ToxiproxyClient.Toxic> cut() {
    return List.of(
        ToxiproxyClient.Toxic.timeout("cut-up", "upstream", 500, 1.0),
        ToxiproxyClient.Toxic.timeout("cut-down", "downstream", 500, 1.0));
  }

  @Override
  public void inject(ScenarioContext c) {
    ToxiproxyClient t = c.stack().toxiproxy().client();
    for (ToxiproxyClient.Toxic toxic : standing()) {
      t.addToxic(Toxiproxy.PROXY, toxic);
    }
  }

  @Override
  public void during(ScenarioContext c, Instant until) throws Exception {
    ToxiproxyClient t = c.stack().toxiproxy().client();
    int cuts = 0;
    Instant next = Instant.now().plus(CYCLE.minus(CUT));
    while (next.plus(CUT).isBefore(until)) {
      Fault.sleepUntil(next);
      for (ToxiproxyClient.Toxic toxic : cut()) {
        t.addToxic(Toxiproxy.PROXY, toxic);
      }
      Fault.sleepUntil(Instant.now().plus(CUT));
      for (ToxiproxyClient.Toxic toxic : cut()) {
        t.removeToxic(Toxiproxy.PROXY, toxic.name());
      }
      cuts++;
      next = next.plus(CYCLE);
    }
    c.put("cuts", cuts);
    Fault.sleepUntil(until);
  }

  @Override
  public void remove(ScenarioContext c) {
    c.stack().toxiproxy().client().reset();
  }

  @Override
  public List<Check> checks(ScenarioContext c) {
    List<Check> rows = new ArrayList<>();
    OptionalLong retries =
        AgentCounts.sumDelta(c.countsBefore(), c.countsAfter(), AgentCounts.BATCH_RETRIES);
    OptionalLong sessions =
        AgentCounts.delta(c.countsBefore(), c.countsAfter(), AgentCounts.SESSIONS);
    rows.add(
        new Check(
            c.id(),
            "resends and reconnects caused by the loss (reference)",
            "> 0 (the fault reached the agent)",
            "batch retries "
                + (retries.isPresent() ? retries.getAsLong() : "-")
                + ", new sessions "
                + (sessions.isPresent() ? sessions.getAsLong() : "-")
                + ", cuts "
                + c.get("cuts"),
            Check.Kind.INFO,
            Verdict.INFO,
            "argos_agent_batch_retries_total, argos_agent_session_established_total"));
    return rows;
  }
}
