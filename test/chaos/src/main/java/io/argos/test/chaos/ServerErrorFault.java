/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.chaos;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;

/**
 * T-CH-004 "서버 5xx·RESOURCE_EXHAUSTED" (18 §11.1, "서버 테스트 플래그"): the server's failure injection
 * {@code argos.testing.ingest-fault-file} (18 §19, {@code IngestFaultInjector}) answers the agent's
 * data batches with {@link #PHASES} in turn, a quarter of the fault each: {@code UNAVAILABLE} and
 * {@code INTERNAL} (server errors: the agent resends the same {@code seq} after 200 ms, 1 s, 3 s
 * and drops the batch after the third retry), {@code RESOURCE_EXHAUSTED} with {@code
 * argos-retry-after-ms} (resent after that time, dropped after three) and {@code QUOTA:0.5} (half
 * of each transaction batch removed through the real quota path: {@code ACK_PARTIAL_DROP}, server
 * drops reason {@code quota}). Extra rows: the agent resent refused batches, the agent's own drops
 * and the server's quota drops are both counted, and {@code collection-health} shows them as
 * separate series ({@code agent_drops}, {@code server_drops_quota}) ("드롭(쿼터) 구분 표시").
 */
public final class ServerErrorFault implements Fault {

  /** The scenario. */
  public static final String ID = "T-CH-004";

  /** The fault file contents, in order. */
  static final List<String> PHASES =
      List.of("UNAVAILABLE", "INTERNAL", "RESOURCE_EXHAUSTED", "QUOTA:0.5");

  /** The server reads the file at most once per second. */
  static final Duration READ_INTERVAL = Duration.ofSeconds(1);

  @Override
  public String describe() {
    return "server failure injection (argos.testing.ingest-fault-file) on the data batches: "
        + String.join(", ", PHASES)
        + ", a quarter of the fault each";
  }

  /** When phase {@code i} of {@code n} starts in a fault of {@code length} from {@code start}. */
  static Instant phaseStart(Instant start, Duration length, int i, int n) {
    return start.plus(length.multipliedBy(i).dividedBy(n));
  }

  private static void write(Path file, String content) throws Exception {
    Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
    Files.writeString(tmp, content + "\n", StandardCharsets.UTF_8);
    Files.move(
        tmp,
        file,
        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
  }

  @Override
  public void inject(ScenarioContext c) throws Exception {
    write(c.stack().ingestFaultFile(), PHASES.get(0));
    c.note("fault file " + PHASES.get(0) + " at " + Instant.now());
  }

  @Override
  public void during(ScenarioContext c, Instant until) throws Exception {
    for (int i = 1; i < PHASES.size(); i++) {
      Fault.sleepUntil(phaseStart(c.faultStart(), c.faultLength(), i, PHASES.size()));
      write(c.stack().ingestFaultFile(), PHASES.get(i));
      c.note("fault file " + PHASES.get(i) + " at " + Instant.now());
    }
    Fault.sleepUntil(until);
  }

  @Override
  public void remove(ScenarioContext c) throws Exception {
    Files.deleteIfExists(c.stack().ingestFaultFile());
    // The server sees the removal within its read interval.
    Thread.sleep(READ_INTERVAL.toMillis() + 500);
  }

  @Override
  public List<Check> checks(ScenarioContext c) {
    List<Check> rows = new ArrayList<>();
    OptionalLong retries =
        AgentCounts.sumDelta(c.countsBefore(), c.countsAfter(), AgentCounts.BATCH_RETRIES);
    rows.add(
        new Check(
            c.id(),
            "refused batches resent by the agent",
            "> 0 (UNAVAILABLE, INTERNAL, RESOURCE_EXHAUSTED are retried)",
            retries.isPresent() ? Long.toString(retries.getAsLong()) : "-",
            Check.Kind.INVARIANT,
            Judge.invariant(retries.isPresent() && retries.getAsLong() > 0),
            "argos_agent_batch_retries_total, every kind"));
    OptionalLong agentDrops =
        AgentCounts.delta(c.countsBefore(), c.countsAfter(), AgentCounts.DROPPED_TRANSACTIONS);
    OptionalLong serverDrops =
        AgentCounts.delta(
            c.countsBefore(), c.countsAfter(), AgentCounts.SERVER_DROPPED_TRANSACTIONS);
    boolean both =
        agentDrops.isPresent()
            && agentDrops.getAsLong() > 0
            && serverDrops.isPresent()
            && serverDrops.getAsLong() > 0;
    rows.add(
        new Check(
            c.id(),
            "agent drops and server quota drops both counted by the agent",
            "agent drops > 0 (retries exhausted) and server drops > 0 (QUOTA)",
            String.format(
                Locale.ROOT,
                "agent %s, server %s",
                agentDrops.isPresent() ? Long.toString(agentDrops.getAsLong()) : "-",
                serverDrops.isPresent() ? Long.toString(serverDrops.getAsLong()) : "-"),
            Check.Kind.INVARIANT,
            Judge.invariant(both),
            "argos_agent_dropped_total{queue=transaction},"
                + " argos_agent_server_dropped_items_total{kind=transactions}"));
    HealthView view = null;
    String error = null;
    try {
      view = HealthView.of(c.stack().collectionHealth(c.start(), Instant.now()));
    } catch (RuntimeException e) {
      error = e.getMessage();
    }
    boolean separate = view != null && view.agentDrops() > 0 && view.serverDropsQuota() > 0;
    rows.add(
        new Check(
            c.id(),
            "agent drops and server quota drops shown separately in collection-health",
            "agent_drops > 0 and server_drops_quota > 0",
            view == null
                ? "no answer: " + error
                : String.format(
                    Locale.ROOT,
                    "agent_drops %.0f, server_drops %.0f, server_drops_quota %.0f",
                    view.agentDrops(),
                    view.serverDrops(),
                    view.serverDropsQuota()),
            Check.Kind.INVARIANT,
            Judge.invariant(separate),
            "GET /api/v1/apps/{id}/collection-health: the UI's collection status panel"));
    return rows;
  }
}
