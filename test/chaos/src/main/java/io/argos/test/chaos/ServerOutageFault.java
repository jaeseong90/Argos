/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.chaos;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;

/**
 * T-CH-001 "서버 중단 1시간" (18 §11.1): the server is stopped and started again on the same databases,
 * WAL and collector port after the fault's length (18 §11.1 writes {@code docker stop
 * argos-server}; the stack runs the server as a child JVM, see {@link ChaosStack}). Extra rows: the
 * agent's reconnect attempts never wait longer than the back-off cap ({@link ReconnectGaps}), the
 * outage {@code server_down} is shown (graceful stops only), and the expected size of the drops
 * (queue age 120 s, {@code buffer.max-age-seconds}) as a reference next to the exact accounting of
 * the common rows.
 */
public final class ServerOutageFault implements Fault {

  /** {@code buffer.max-age-seconds} default (docs/agent-config.md). */
  static final Duration QUEUE_AGE = Duration.ofMinutes(2);

  /** {@code buffer.transaction.capacity} default. */
  static final long QUEUE_CAPACITY = 20_000;

  @Override
  public String describe() {
    return "server stopped (SIGTERM on POSIX, terminated on Windows), restarted on the same WAL"
        + " and collector port after the fault length";
  }

  @Override
  public void inject(ScenarioContext c) {
    c.stack().stopServer();
  }

  @Override
  public void remove(ScenarioContext c) throws Exception {
    c.stack().startServer();
  }

  /** The restart takes about as long as the first start: the load runs twice that longer. */
  @Override
  public Duration removalAllowance(ChaosStack stack) {
    Duration twice = stack.lastServerStart().multipliedBy(2);
    return twice.compareTo(Duration.ofMinutes(1)) < 0 ? Duration.ofMinutes(1) : twice;
  }

  @Override
  public String expectedOutage(ScenarioContext c) {
    return "server_down";
  }

  @Override
  public String outageSkipReason(ScenarioContext c) {
    return c.stack().gracefulStops()
        ? null
        : "Windows: the server JVM is terminated, not stopped, so it writes no stop event and no"
            + " server_down outage exists (a crash, not 18 §11.1's docker stop); judged on POSIX"
            + " runners";
  }

  /**
   * The drops a queue bounded by age and capacity should show for an outage: transactions older
   * than the queue age when the server comes back, or beyond the capacity (a reference, not exact:
   * the outage starts and ends between batches).
   */
  static long expectedDrops(int rate, Duration outage) {
    long seconds = outage.toSeconds();
    long byAge = Math.max(0, (seconds - QUEUE_AGE.toSeconds()) * rate);
    long byCapacity = Math.max(0, seconds * rate - QUEUE_CAPACITY);
    return Math.max(byAge, byCapacity);
  }

  @Override
  public List<Check> checks(ScenarioContext c) {
    List<Check> rows = new ArrayList<>();
    ReconnectGaps gaps =
        ReconnectGaps.fromToxiproxyLog(
            c.stack().toxiproxy().logLines(), c.faultStart(), c.faultEnd());
    rows.add(
        new Check(
            c.id(),
            "reconnect attempts while the server is down",
            "longest gap <= 30 s back-off cap with +-20 % jitter (36 s)",
            String.format(
                Locale.ROOT,
                "%d attempts, longest gap %s",
                gaps.attempts().size(),
                gaps.longest().map(d -> d.toMillis() / 1000.0 + " s").orElse("-")),
            Check.Kind.INVARIANT,
            Judge.invariant(gaps.withinLimit()),
            "toxiproxy 'Accepted client' times (one TCP connection per attempt)"));
    OptionalLong dropped =
        AgentCounts.delta(c.countsBefore(), c.countsAfter(), AgentCounts.DROPPED_TRANSACTIONS);
    long outage = java.time.Duration.between(c.faultStart(), c.faultEnd()).toSeconds();
    rows.add(
        new Check(
            c.id(),
            "agent drops beyond the 120 s queue (reference)",
            "about rate x (outage - 120 s)",
            String.format(
                Locale.ROOT,
                "counted %s, reference %d (rate %d/s, outage %d s)",
                dropped.isPresent() ? Long.toString(dropped.getAsLong()) : "-",
                expectedDrops(c.options().rate(), Duration.ofSeconds(outage)),
                c.options().rate(),
                outage),
            Check.Kind.INFO,
            Verdict.INFO,
            "the exact rule is the drop accounting row"));
    OptionalLong sessions =
        AgentCounts.delta(c.countsBefore(), c.countsAfter(), AgentCounts.SESSIONS);
    rows.add(
        new Check(
            c.id(),
            "new sessions after the restart",
            ">= 1",
            sessions.isPresent() ? Long.toString(sessions.getAsLong()) : "-",
            Check.Kind.INVARIANT,
            Judge.invariant(sessions.isPresent() && sessions.getAsLong() >= 1),
            "argos_agent_session_established_total"));
    return rows;
  }
}
