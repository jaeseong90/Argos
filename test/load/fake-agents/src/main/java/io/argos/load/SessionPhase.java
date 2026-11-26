/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.load;

import io.argos.server.testing.fakeagent.AgentChannels;
import io.argos.server.testing.fakeagent.AgentTokens;
import io.argos.server.testing.fakeagent.FakeAgent;
import io.argos.server.testing.fakeagent.HelloResult;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * The memory cost of the server-wide session cap (ADR-0010 C-157 (1), 12 §10, WO-29 step A2): after
 * the load, {@code N} idle sessions (full 5,000 minus the sessions already open, smoke 1,000, mini
 * 100), each on a TLS connection of its own like a real agent, over apps of 100 instances (the
 * hourly cap of new instances per app); the server's heap after a full GC, NMT committed memory and
 * resident set before and after give the bytes per session and the extrapolation to {@value
 * #SERVER_CAP} sessions. The sessions send a heartbeat every 20 s while they are held.
 */
final class SessionPhase implements LoadPhase {

  /** {@code HelloService.MAX_SESSIONS}: live agent sessions of one server (C-157 (1)). */
  static final int SERVER_CAP = 5_000;

  /** New instances per app and hour (server default), so idle apps hold 100 sessions each. */
  static final int SESSIONS_PER_APP = 100;

  private final List<ReportSection> sections = new ArrayList<>();
  private final List<Criterion> criteria = new ArrayList<>();
  private long bytesPerSession = -1;

  /** Idle sessions of a profile before the server-wide cap is applied. */
  static int planned(LoadProfile p) {
    return switch (p) {
      case FULL -> SERVER_CAP;
      case SMOKE -> 1_000;
      case MINI -> 100;
    };
  }

  /** Idle sessions to open: the plan, within the cap left by {@code liveSessions} (10 spare). */
  static int toOpen(LoadProfile p, long liveSessions) {
    return (int) Math.max(0, Math.min(planned(p), SERVER_CAP - Math.max(0, liveSessions) - 10));
  }

  /** Heap bytes per session measured by the phase, -1 before it ran. */
  long heapBytesPerSession() {
    return bytesPerSession;
  }

  @Override
  public String name() {
    return "idle sessions";
  }

  @Override
  public void underLoad(LoadContext context) {}

  @Override
  public void afterLoad(LoadContext c) throws Exception {
    // The steady sessions stopped sending with the load and would end by idle timeout (90 s) in
    // the middle of the measurement: end them first.
    c.generator().close();
    TimeUnit.SECONDS.sleep(5);
    long live = (long) PhaseSupport.scrape(c).sum("argos_sessions_active");
    int n = toOpen(c.options().profile(), live);
    if (n <= 0) {
      sections.add(
          new ReportSection(
              ReportSection.PLANNED.get(1), "No room under the session cap (" + live + " live)."));
      return;
    }
    int appCount = (n + SESSIONS_PER_APP - 1) / SESSIONS_PER_APP;
    List<AgentTokens.IssuedToken> tokens = new ArrayList<>();
    for (int i = 0; i < appCount; i++) {
      tokens.add(PhaseSupport.app(c, "idle-app-" + i));
    }
    LoadRunner.log("idle sessions: measuring before (" + live + " live)");
    // Two baseline points 15 s apart: the heap of an idle server still drifts (alert windows,
    // caches), and the drift over the same interval is taken off the sessions' difference.
    PhaseSupport.MemoryPoint baseline = PhaseSupport.memory(c);
    long baselineNanos = System.nanoTime();
    TimeUnit.SECONDS.sleep(15);
    PhaseSupport.MemoryPoint before = PhaseSupport.memory(c);
    long beforeNanos = System.nanoTime();
    List<FakeAgent> agents = Collections.synchronizedList(new ArrayList<>(n));
    Map<String, Long> refused = Collections.synchronizedMap(new LinkedHashMap<>());
    long epoch = System.currentTimeMillis() * 1_000 + 500_000;
    long connectStart = System.nanoTime();
    ExecutorService pool = Executors.newFixedThreadPool(16, Generator.daemonThreads("idle-"));
    try {
      List<Future<?>> futures = new ArrayList<>();
      for (int i = 0; i < n; i++) {
        int k = i;
        futures.add(
            pool.submit(
                () -> {
                  int app = k / SESSIONS_PER_APP;
                  FakeAgent a =
                      PhaseSupport.agent(
                          PhaseSupport.channel(c, AgentChannels.Options.defaults()),
                          true,
                          tokens.get(app),
                          "idle-app-" + app,
                          "idle-" + k,
                          epoch + k,
                          3_000 + k % 1_000,
                          false);
                  HelloResult h = a.connect();
                  if (h.accepted()) {
                    agents.add(a);
                  } else {
                    refused.merge(h.rejectReason() + "/" + h.code(), 1L, Long::sum);
                    a.close();
                  }
                }));
      }
      for (Future<?> f : futures) {
        f.get(10, TimeUnit.MINUTES);
      }
      long connectMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - connectStart);
      int opened = agents.size();
      LoadRunner.log("idle sessions: " + opened + " of " + n + " open in " + connectMillis + " ms");
      heartbeats(agents);
      TimeUnit.SECONDS.sleep(10);
      heartbeats(agents);
      long liveAfter = (long) PhaseSupport.scrape(c).sum("argos_sessions_active");
      PhaseSupport.MemoryPoint after = PhaseSupport.memory(c);
      long afterNanos = System.nanoTime();
      long drift =
          driftOver(
              before.heapUsed(),
              baseline.heapUsed(),
              beforeNanos - baselineNanos,
              afterNanos - beforeNanos);
      long stillOpen = agents.stream().filter(FakeAgent::sessionOpen).count();
      closeAll(agents, pool);
      TimeUnit.SECONDS.sleep(5);
      PhaseSupport.MemoryPoint released = PhaseSupport.memory(c);
      report(
          c.options(),
          n,
          opened,
          stillOpen,
          refused,
          connectMillis,
          live,
          liveAfter,
          before,
          after,
          released,
          drift);
    } finally {
      closeAll(agents, pool);
      pool.shutdownNow();
    }
  }

  private static void heartbeats(List<FakeAgent> agents) {
    synchronized (agents) {
      for (FakeAgent a : agents) {
        try {
          a.heartbeat();
        } catch (RuntimeException e) {
          // a session the server ended: counted by sessionOpen() below
        }
      }
    }
  }

  private static void closeAll(List<FakeAgent> agents, ExecutorService pool) {
    List<FakeAgent> copy;
    synchronized (agents) {
      copy = new ArrayList<>(agents);
      agents.clear();
    }
    List<Future<?>> futures = new ArrayList<>();
    for (FakeAgent a : copy) {
      if (pool.isShutdown()) {
        a.close();
      } else {
        futures.add(pool.submit(a::close));
      }
    }
    for (Future<?> f : futures) {
      try {
        f.get(1, TimeUnit.MINUTES);
      } catch (Exception e) {
        // closing anyway
      }
    }
  }

  /**
   * The heap drift of the idle server over {@code intervalNanos}, from two baseline points {@code
   * spanNanos} apart ({@link Long#MIN_VALUE} when unknown).
   */
  static long driftOver(long later, long earlier, long spanNanos, long intervalNanos) {
    if (later < 0 || earlier < 0 || spanNanos <= 0) {
      return Long.MIN_VALUE;
    }
    return Math.round((double) (later - earlier) * intervalNanos / spanNanos);
  }

  /** {@code delta / sessions} or -1. */
  static long perSession(long delta, int sessions) {
    return delta == Long.MIN_VALUE || sessions <= 0 ? -1 : delta / sessions;
  }

  private void report(
      LoadOptions o,
      int planned,
      int opened,
      long stillOpen,
      Map<String, Long> refused,
      long connectMillis,
      long liveBefore,
      long liveAfter,
      PhaseSupport.MemoryPoint before,
      PhaseSupport.MemoryPoint after,
      PhaseSupport.MemoryPoint released,
      long drift) {
    PhaseSupport.MemoryPoint d = after.minus(before);
    PhaseSupport.MemoryPoint r = released.minus(before);
    long rawHeap = perSession(d.heapUsed(), opened);
    long heap =
        d.heapUsed() == Long.MIN_VALUE || drift == Long.MIN_VALUE
            ? rawHeap
            : perSession(d.heapUsed() - drift, opened);
    long nmt =
        d.nmtCommittedKb() == Long.MIN_VALUE ? -1 : perSession(d.nmtCommittedKb() * 1024, opened);
    long rss = perSession(d.rss(), opened);
    bytesPerSession = heap;
    List<List<String>> rows = new ArrayList<>();
    rows.add(
        List.of(
            "Item",
            "Before",
            "With the idle sessions",
            "Difference",
            "Per session",
            "Extrapolated to 5,000"));
    rows.add(
        row(
            "Heap used after a full GC (raw)",
            before.heapUsed(),
            after.heapUsed(),
            d.heapUsed(),
            rawHeap));
    rows.add(
        List.of(
            "Heap drift of the idle server over the same interval (baseline 15 s before)",
            "",
            "",
            PhaseSupport.signedBytes(drift),
            "",
            ""));
    rows.add(
        row(
            "Heap used after a full GC, drift taken off",
            before.heapUsed(),
            after.heapUsed(),
            d.heapUsed() == Long.MIN_VALUE || drift == Long.MIN_VALUE
                ? d.heapUsed()
                : d.heapUsed() - drift,
            heap));
    rows.add(
        row(
            "NMT committed (heap reserved up front: -Xms = -Xmx)",
            kib(before.nmtCommittedKb()),
            kib(after.nmtCommittedKb()),
            d.nmtCommittedKb() == Long.MIN_VALUE ? Long.MIN_VALUE : d.nmtCommittedKb() * 1024,
            nmt));
    rows.add(row("Resident set", before.rss(), after.rss(), d.rss(), rss));
    rows.add(
        List.of(
            "Live threads",
            Long.toString(before.liveThreads()),
            Long.toString(after.liveThreads()),
            d.liveThreads() == Long.MIN_VALUE ? "n/a" : Long.toString(d.liveThreads()),
            "",
            ""));
    StringBuilder md = new StringBuilder();
    md.append(
        String.format(
            Locale.ROOT,
            "%,d idle sessions planned, %,d accepted in %,d ms (refused: %s), %,d still open at the"
                + " measurement; `argos_sessions_active` %,d -> %,d. One TLS connection per session,"
                + " %d sessions per app (the hourly cap of new instances), a heartbeat every 10-20 s."
                + " Measured after the load stopped and drained.%n%n",
            planned,
            opened,
            connectMillis,
            PhaseSupport.counts(refused),
            stillOpen,
            liveBefore,
            liveAfter,
            SESSIONS_PER_APP));
    md.append(PhaseSupport.table(rows)).append('\n');
    md.append(
        String.format(
            Locale.ROOT,
            "After closing them and 5 s: heap %s, resident set %s against the start of the"
                + " phase.%n%n",
            PhaseSupport.signedBytes(r.heapUsed()),
            PhaseSupport.signedBytes(r.rss())));
    md.append(
        "The heap figure is the server's own state per session (registry, session handler,"
            + " gRPC stream and Netty channel objects, the tracker's epoch memory, instance"
            + " caches); the resident set adds Netty's pooled direct buffers and the TLS engine."
            + " Proposal (C-157 (1)): keep the 5,000 constant if the extrapolated heap stays well"
            + " below 10 % of the 4 GiB heap of 12 §11 on the target machine; this laptop's value"
            + " is a reference (INVALID).\n");
    sections.add(new ReportSection(ReportSection.PLANNED.get(1), md.toString()));
    criteria.add(
        PhaseSupport.invariant(
            "T-NFR-06",
            "C-157 (1)",
            "idle sessions accepted up to the planned number",
            planned + " accepted and still open",
            opened + " accepted, " + stillOpen + " open",
            true,
            opened == planned && stillOpen == opened,
            "idle-session phase after the load"));
    criteria.add(
        PhaseSupport.measurement(
            o,
            "T-NFR-06",
            "C-157 (1), 12 §11",
            "heap of 5,000 sessions (extrapolated)",
            "<= 10 % of -Xmx",
            heap < 0
                ? "n/a"
                : Format.bytes(heap * SERVER_CAP) + " (" + Format.bytes(heap) + " per session)",
            heap >= 0,
            heap >= 0 && heap * SERVER_CAP <= LoadOptions.size(o.serverHeap(), 0) / 10,
            "proposal threshold of this WO, not a design target"));
  }

  private static long kib(long kb) {
    return kb < 0 ? -1 : kb * 1024;
  }

  private static List<String> row(String item, long before, long after, long delta, long per) {
    return List.of(
        item,
        Format.bytes(before),
        Format.bytes(after),
        PhaseSupport.signedBytes(delta),
        per < 0 ? "n/a" : Format.bytes(per),
        per < 0 ? "n/a" : Format.bytes(per * SERVER_CAP));
  }

  @Override
  public List<ReportSection> sections() {
    return List.copyOf(sections);
  }

  @Override
  public List<Criterion> criteria() {
    return List.copyOf(criteria);
  }
}
