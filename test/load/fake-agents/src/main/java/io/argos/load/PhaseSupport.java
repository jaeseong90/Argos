/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.load;

import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.server.testing.fakeagent.AgentChannels;
import io.argos.server.testing.fakeagent.AgentClock;
import io.argos.server.testing.fakeagent.AgentTokens;
import io.argos.server.testing.fakeagent.FakeAgent;
import io.argos.server.testing.fakeagent.Records;
import io.argos.server.testing.load.Jcmd;
import io.argos.server.testing.load.PrometheusText;
import io.grpc.ManagedChannel;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Helpers shared by the measurement phases (WO-29 step A2): fake agents on channels of their own,
 * app tokens, scraped counters, the server's memory after a full GC, PostgreSQL scalars and
 * criteria rows.
 */
final class PhaseSupport {

  private PhaseSupport() {}

  /** A new TLS channel to the collector. */
  static ManagedChannel channel(LoadContext c, AgentChannels.Options options) {
    return AgentChannels.tls(
        "localhost", c.env().collectorPort(), c.env().collectorCaCertificate(), options);
  }

  /** A fake agent of {@code token}'s app on {@code channel}. */
  static FakeAgent agent(
      ManagedChannel channel,
      boolean ownsChannel,
      AgentTokens.IssuedToken token,
      String app,
      String instance,
      long epoch,
      int node,
      boolean preCompressed) {
    FakeAgent.Builder b =
        FakeAgent.builder(channel)
            .ownsChannel(ownsChannel)
            .token(token.token())
            .appName(app)
            .env("prod")
            .instanceName(instance)
            .records(new Records(AgentClock.system(), node, epoch))
            .agentEpoch(epoch)
            .callDeadline(Duration.ofSeconds(60))
            .helloTimeout(Duration.ofSeconds(30))
            .maxRecorded(16);
    if (preCompressed) {
      b.compression(FakeAgent.Compression.NONE);
    }
    return b.build();
  }

  /** Registers app {@code name} (env prod) and issues a token for it. */
  static AgentTokens.IssuedToken app(LoadContext c, String name) throws SQLException {
    try (Connection con = c.env().connect(PgAccount.APP)) {
      return AgentTokens.issue(con, AgentTokens.ensureApp(con, name, "prod"));
    }
  }

  /** One scrape of the server's {@code /actuator/prometheus}. */
  static PrometheusText scrape(LoadContext c) {
    return c.server().scraper().scrape();
  }

  /** The sum of {@code name} whose label {@code key} is {@code value} ({@code key} null: all). */
  static double sum(PrometheusText p, String name, String key, String value) {
    return key == null ? p.sum(name) : p.sum(name, l -> value.equals(l.get(key)));
  }

  /**
   * The server's memory after a full GC ({@code jcmd GC.run}): heap used ({@code
   * jvm_memory_used_bytes{area="heap"}}), NMT committed in total and the resident set.
   *
   * @param heapUsed heap used after the GC, -1 when not read
   * @param nmtCommittedKb NMT committed in KiB, -1 when not read
   * @param rss resident set (or Windows working set), -1 when not read
   * @param liveThreads {@code jvm_threads_live_threads}, -1 when not read
   */
  record MemoryPoint(long heapUsed, long nmtCommittedKb, long rss, long liveThreads) {

    /** {@code this - before}, field by field (-1 when either side is missing). */
    MemoryPoint minus(MemoryPoint before) {
      return new MemoryPoint(
          diff(heapUsed, before.heapUsed),
          diff(nmtCommittedKb, before.nmtCommittedKb),
          diff(rss, before.rss),
          diff(liveThreads, before.liveThreads));
    }

    private static long diff(long a, long b) {
      return a < 0 || b < 0 ? Long.MIN_VALUE : a - b;
    }
  }

  /** Measures a {@link MemoryPoint}; failures leave fields at -1. */
  static MemoryPoint memory(LoadContext c) throws InterruptedException {
    long pid = c.server().pid();
    try {
      Jcmd.run(pid, 120, "GC.run");
    } catch (RuntimeException e) {
      LoadRunner.log("jcmd GC.run failed: " + e);
    }
    TimeUnit.SECONDS.sleep(2);
    long heap = -1;
    long threads = -1;
    try {
      PrometheusText p = scrape(c);
      heap = (long) p.sum("jvm_memory_used_bytes", l -> "heap".equals(l.get("area")));
      threads = (long) p.sum("jvm_threads_live_threads");
    } catch (RuntimeException e) {
      LoadRunner.log("scrape failed: " + e);
    }
    long nmt = -1;
    try {
      nmt =
          Jcmd.parseNativeMemory(Jcmd.run(pid, 120, "VM.native_memory", "summary", "scale=KB"))
              .committedKb();
    } catch (RuntimeException e) {
      LoadRunner.log("jcmd VM.native_memory failed: " + e);
    }
    long rss = c.rss().sampleNow().orElse(-1);
    return new MemoryPoint(heap, nmt, rss, threads);
  }

  /** A {@code long} scalar of PostgreSQL ({@code argos_app}), -1 on failure. */
  static long pgScalar(LoadContext c, String sql) {
    try (Connection con = c.env().connect(PgAccount.APP);
        Statement st = con.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      return rs.next() ? rs.getLong(1) : -1;
    } catch (SQLException e) {
      LoadRunner.log("PostgreSQL query failed (" + sql + "): " + e.getMessage());
      return -1;
    }
  }

  /** A measurement row: PASS/FAIL only at full scale, INVALID otherwise. */
  static Criterion measurement(
      LoadOptions o,
      String id,
      String requirement,
      String title,
      String target,
      String measured,
      boolean wasMeasured,
      boolean pass,
      String how) {
    Verdict v = Verdicts.measurementVerdict(o.fullScale(), wasMeasured, pass);
    String note =
        v == Verdict.INVALID
            ? "profile "
                + o.profile().id()
                + ", not N-06 at full scale on 13 §7.1 hardware: reference only. "
                + how
            : how;
    return new Criterion(
        id, requirement, title, target, measured, Criterion.Kind.MEASUREMENT, v, note);
  }

  /** An invariant row: PASS/FAIL in every profile, a FAIL fails the task. */
  static Criterion invariant(
      String id,
      String requirement,
      String title,
      String target,
      String measured,
      boolean wasMeasured,
      boolean pass,
      String how) {
    return new Criterion(
        id,
        requirement,
        title,
        target,
        measured,
        Criterion.Kind.INVARIANT,
        Verdicts.invariantVerdict(wasMeasured, pass),
        how);
  }

  /** A Markdown table of {@code rows} (first row = header). */
  static String table(List<List<String>> rows) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < rows.size(); i++) {
      sb.append('|');
      for (String cell : rows.get(i)) {
        sb.append(' ').append(Format.cell(cell)).append(" |");
      }
      sb.append('\n');
      if (i == 0) {
        sb.append('|').append("---|".repeat(rows.get(0).size())).append('\n');
      }
    }
    return sb.toString();
  }

  /** {@code key value, ...} of a map of counts. */
  static String counts(Map<String, Long> counts) {
    if (counts.isEmpty()) {
      return "none";
    }
    StringBuilder sb = new StringBuilder();
    counts.forEach(
        (k, v) ->
            sb.append(sb.length() == 0 ? "" : ", ")
                .append(k)
                .append(' ')
                .append(String.format(Locale.ROOT, "%,d", v)));
    return sb.toString();
  }

  /** Bytes with sign, or n/a for a missing difference. */
  static String signedBytes(long delta) {
    if (delta == Long.MIN_VALUE) {
      return "n/a";
    }
    return (delta < 0 ? "-" : "+") + Format.bytes(Math.abs(delta));
  }
}
