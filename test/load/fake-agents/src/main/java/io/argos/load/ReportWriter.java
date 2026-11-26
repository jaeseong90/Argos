/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.load;

import io.argos.load.LoadCounters.Counter;
import io.argos.server.testing.load.CumulativeHistogram;
import io.argos.server.testing.load.Jcmd;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Writes {@code server-load.md} (docs/bench style, English): run conditions, the N-06 / N-18 / D-13
 * verdict table, sent against stored rows, ingest lag and WAL fsync, the dashboard users, the
 * ClickHouse figures, the memory breakdown, the collector counters, the class histogram and pinned
 * virtual threads, the sections of the plugged phases (placeholders for the planned ones that did
 * not run) and the files of the run.
 */
final class ReportWriter {

  private ReportWriter() {}

  static String write(RunReport r) {
    StringBuilder sb = new StringBuilder();
    LoadOptions o = r.options;
    boolean full = o != null && o.fullScale();
    sb.append("# Server load test (`serverLoadTest`, WO-29)\n\n");
    sb.append(
        "Fake agents at protocol level (18 §8) against the whole server (boot jar, child JVM),"
            + " PostgreSQL and ClickHouse; 01 N-06, N-18, D-13; 12 §11.\n\n");
    if (!full) {
      sb.append("> **INVALID as a measurement.** Profile `")
          .append(o == null ? "?" : o.profile().id())
          .append("` on machine profile `")
          .append(o == null ? "?" : o.machineProfile())
          .append(
              "`: this is not the N-06 run (5,000 TPS, 50,000 step rows/s, 10,000 statistics"
                  + " rows/s for 1 hour on 13 §7.1 hardware). Every timing and resource verdict is"
                  + " INVALID and its value is a reference only; the invariants (D-13 counts, OOM 0,"
                  + " ClickHouse settings) are judged in every profile. The full run on target"
                  + " hardware: `./gradlew serverLoadTest -Pbench.machine-profile=<id>"
                  + " -Pserver.load.profile=full`.\n\n");
    }
    conditions(sb, r);
    verdicts(sb, r);
    sentAndStored(sb, r);
    lagAndWal(sb, r);
    users(sb, r);
    clickHouse(sb, r);
    memory(sb, r);
    counters(sb, r);
    heapAndPinning(sb, r);
    for (ReportSection s : r.sections) {
      sb.append("## ").append(s.title()).append("\n\n").append(s.markdown().strip()).append("\n\n");
    }
    for (String planned : ReportSection.PLANNED) {
      boolean present = r.sections.stream().anyMatch(s -> s.title().equals(planned));
      if (!present) {
        sb.append("## ")
            .append(planned)
            .append(
                "\n\nNot measured in this run (the phase did not run, was not selected or"
                    + " failed; see the notes).\n\n");
      }
    }
    notes(sb, r);
    return sb.toString();
  }

  private static void conditions(StringBuilder sb, RunReport r) {
    LoadOptions o = r.options;
    sb.append("## Run conditions\n\n| Item | Value |\n|---|---|\n");
    row(sb, "Date (UTC)", r.started == null ? "n/a" : r.started.toString());
    row(sb, "Code", r.commit);
    row(sb, "Machine profile", o == null ? "n/a" : "`" + o.machineProfile() + "`");
    row(sb, "Machine", r.machine);
    row(sb, "Java", r.java);
    row(sb, "Stack", r.stack);
    row(sb, "Server JVM options", "`" + String.join(" ", r.serverJvmOptions) + "`");
    if (o != null) {
      row(
          sb,
          "Profile",
          "`" + o.profile().id() + "`" + (o.fullScale() ? " (N-06 at full scale)" : ""));
      row(sb, "Generator", String.join("<br>", o.describe()));
      row(
          sb,
          "Steps per profile",
          o.stepsPerTx() > 0
              ? o.stepsPerProfile() + " (given)"
              : o.stepsPerProfile()
                  + " = round(step rows/s / (TPS x profile rate)): 18 §8's default of 15 steps"
                  + " gives 9,000 step rows/s at 5,000 TPS and contradicts N-06's 50,000; the N-06"
                  + " rate governs until the PM decides (WO-29 PM question 1)");
      if (!o.statsWithinQuota()) {
        row(sb, "Statistics quota", "per-instance rows exceed 6,000 per 10 s (C-157 (3))");
      }
    }
    row(
        sb,
        "Sessions",
        r.instancesConnected
            + " accepted, over "
            + r.channels
            + " shared TLS channel(s); apps "
            + r.apps.stream().map(a -> a.name() + "/" + a.env()).toList());
    if (!r.skewsMs.isEmpty()) {
      row(sb, "Agent clock skews (ms)", r.skewsMs.toString());
    }
    row(sb, "Windows (UTC)", windows(r));
    row(
        sb,
        "Topology",
        "managed single host: generator, server child JVM and the Testcontainers databases share"
            + " this machine (18 §2.1 asks for a separate load generator; an external-target mode"
            + " is a follow-up, WO-29 PM question 2)");
    sb.append('\n');
  }

  private static String windows(RunReport r) {
    StringBuilder w = new StringBuilder();
    w.append("start ").append(t(r.started));
    w.append(", measured ").append(t(r.windowStart)).append(" .. ").append(t(r.windowEnd));
    if (r.windowStart != null && r.windowEnd != null) {
      w.append(" (").append(Duration.between(r.windowStart, r.windowEnd).toSeconds()).append(" s)");
    }
    w.append(", load stopped ").append(t(r.loadEnd));
    w.append(", drained in ").append(r.drainSeconds < 0 ? "n/a" : r.drainSeconds + " s");
    w.append(", end ").append(t(r.finished));
    return w.toString();
  }

  private static String t(Instant i) {
    return i == null ? "n/a" : i.toString();
  }

  private static void verdicts(StringBuilder sb, RunReport r) {
    sb.append("## Verdicts (N-06, N-18, D-13, 12 §11)\n\n");
    sb.append("| ID | Req. | Criterion | Target | Measured | Verdict | Note |\n");
    sb.append("|---|---|---|---|---|---|---|\n");
    for (Criterion c : r.criteria) {
      sb.append("| ")
          .append(c.id())
          .append(" | ")
          .append(c.requirement())
          .append(" | ")
          .append(Format.cell(c.title()))
          .append(" | ")
          .append(Format.cell(c.target()))
          .append(" | ")
          .append(Format.cell(c.measured()))
          .append(" | **")
          .append(c.verdict())
          .append("** | ")
          .append(Format.cell(c.note()))
          .append(" |\n");
    }
    long failing = r.criteria.stream().filter(Criterion::failsTask).count();
    sb.append('\n')
        .append(
            failing == 0
                ? "Every invariant passed."
                : failing + " invariant(s) failed: the task fails.")
        .append("\n\n");
  }

  private static void sentAndStored(StringBuilder sb, RunReport r) {
    sb.append("## Sent and stored\n\n");
    LoadCounters.Snapshot e = r.atEnd;
    RunReport.Stored s = r.stored;
    sb.append("| Data | Acknowledged (generator) | Stored (load apps) | Rate in the window |\n");
    sb.append("|---|---|---|---|\n");
    storedRow(sb, r, "Transactions (xlog)", Counter.TX_ACCEPTED, s == null ? -1 : s.xlog());
    storedRow(
        sb, r, "Profile steps (profile_step)", Counter.STEPS, s == null ? -1 : s.profileSteps());
    storedRow(sb, r, "SqlStat (sql_stat_raw)", Counter.STAT_SQL, s == null ? -1 : s.sqlStatRaw());
    storedRow(sb, r, "ExtStat (ext_stat_raw)", Counter.STAT_EXT, s == null ? -1 : s.extStatRaw());
    storedRow(
        sb, r, "Metric samples (metric)", Counter.METRIC_SAMPLES, s == null ? -1 : s.metric());
    sb.append('\n');
    if (e != null) {
      sb.append(
          String.format(
              Locale.ROOT,
              "Transactions: %,d late, %,d duplicate batches (%,d answered ACK_DUPLICATE), %,d"
                  + " generated as errors, %,d dropped by the server (dropped_items), %,d"
                  + " unacknowledged after every retry. Profiles: %,d. The metric table also holds"
                  + " the agents' window counters (argos_agent_*), so it can exceed the samples"
                  + " sent.%n%n",
              e.get(Counter.TX_LATE),
              e.get(Counter.DUPLICATE_BATCHES),
              e.get(Counter.DUPLICATE_ACKS),
              e.get(Counter.TX_ERRORS),
              e.get(Counter.TX_DROPPED),
              e.get(Counter.TX_UNACKNOWLEDGED),
              e.get(Counter.PROFILES)));
    }
    if (s != null && !r.apps.isEmpty() && e != null) {
      sb.append("| App | Acknowledged | count(xlog) |\n|---|---|---|\n");
      long[] byApp = e.acceptedByApp();
      for (LoadApp a : r.apps) {
        sb.append("| ")
            .append(a.name())
            .append('/')
            .append(a.env())
            .append(" (id ")
            .append(a.appId())
            .append(") | ")
            .append(Format.count(a.index() < byApp.length ? byApp[a.index()] : -1))
            .append(" | ")
            .append(Format.count(s.xlogByApp().getOrDefault(a.appId(), 0L)))
            .append(" |\n");
      }
      sb.append('\n');
    }
  }

  private static void storedRow(
      StringBuilder sb, RunReport r, String name, Counter counter, long stored) {
    String sent = r.atEnd == null ? "n/a" : Format.count(r.atEnd.get(counter));
    String rate =
        r.atWindowStart == null || r.atWindowEnd == null
            ? "n/a"
            : Format.rate(r.atWindowEnd.ratePerSecond(counter, r.atWindowStart));
    sb.append("| ")
        .append(name)
        .append(" | ")
        .append(sent)
        .append(" | ")
        .append(stored < 0 ? "n/a" : Format.count(stored))
        .append(" | ")
        .append(rate)
        .append(" |\n");
  }

  private static void lagAndWal(StringBuilder sb, RunReport r) {
    sb.append("## Ingest lag and WAL fsync\n\n| Item | Value |\n|---|---|\n");
    ResourceSampler.Summary s = r.sampler;
    if (s == null) {
      sb.append("| Samplers | n/a |\n\n");
      return;
    }
    CumulativeHistogram lag = s.lagWindow();
    row(
        sb,
        "argos_ingest_lag_seconds (measured window)",
        String.format(
            Locale.ROOT,
            "p50 %s, p99 %s, mean %s, %,.0f observations",
            Format.seconds(lag.quantile(0.5)),
            Format.seconds(lag.quantile(0.99)),
            Format.seconds(lag.mean()),
            lag.count()));
    row(sb, "Worst 5 s interval p99 (whole run)", Format.seconds(s.peaks().intervalLagP99Max));
    CumulativeHistogram fsync = s.fsyncWindow();
    row(
        sb,
        "argos_wal_fsync_seconds (measured window, C-158 (6), D-21)",
        String.format(
            Locale.ROOT,
            "p50 %s, p99 %s, max %s, %,.0f fsyncs",
            Format.seconds(fsync.quantile(0.5)),
            Format.seconds(fsync.quantile(0.99)),
            Format.seconds(s.fsyncMaxSeconds()),
            fsync.count()));
    sb.append(
        "\nQuantiles are interpolated in the histogram buckets of the difference between the"
            + " scrapes at the start and the end of the measured window; the fsync max is the"
            + " largest `_max` (Micrometer's 2-minute decaying maximum) seen by the 5 s samples in"
            + " the window. WAL fsync on a dedicated disk needs a human run (a laptop SSD shared with"
            + " Docker is not representative).\n\n");
  }

  private static void users(StringBuilder sb, RunReport r) {
    sb.append("## Dashboard users\n\n");
    sb.append(
        String.format(
            Locale.ROOT,
            "%d users, every %s; %,d calls in the measured window, %,d errors, p95 %s.%n%n",
            r.options == null ? 0 : r.options.users(),
            r.options == null ? "n/a" : Format.duration(r.options.userInterval()),
            r.userCalls,
            r.userErrors,
            Format.millis(r.userP95Millis)));
    if (!r.userEndpoints.isEmpty()) {
      sb.append("| Call | Calls | Errors | p50 | p95 | max |\n|---|---|---|---|---|---|\n");
      r.userEndpoints.forEach(
          (endpoint, v) ->
              sb.append(
                  String.format(
                      Locale.ROOT,
                      "| %s | %,.0f | %,.0f | %s | %s | %s |%n",
                      endpoint,
                      v[0],
                      v[1],
                      Format.millis(v[2]),
                      Format.millis(v[3]),
                      Format.millis(v[4]))));
      sb.append('\n');
    }
  }

  private static void clickHouse(StringBuilder sb, RunReport r) {
    sb.append("## ClickHouse\n\n| Item | Value |\n|---|---|\n");
    ResourceSampler.Summary s = r.sampler;
    if (s != null) {
      ResourceSampler.Peaks p = s.peaks();
      row(
          sb,
          "Active parts per partition, maximum",
          (p.chActiveParts < 0 ? "n/a" : Long.toString(p.chActiveParts))
              + (p.chBusiestPartition.isEmpty() ? "" : " (" + p.chBusiestPartition + ")"));
      row(sb, "DelayedInserts during the run", Long.toString(s.delayedInsertsDelta()));
      row(
          sb,
          "QueryMemoryLimitExceeded during the run",
          Long.toString(s.queryMemoryLimitExceededDelta()));
      row(sb, "MemoryTracking peak", Format.bytes(p.chMemoryTracking));
      row(sb, "MemoryResident peak", Format.bytes(p.chMemoryResident));
    }
    if (r.clickHouseSettings != null) {
      row(sb, "Settings (N-18)", ClickHouseSettingsRule.describe(r.clickHouseSettings));
    }
    row(
        sb,
        "MEMORY_LIMIT_EXCEEDED in the error log",
        r.clickHouseMemoryLimitErrors < 0 ? "n/a" : Long.toString(r.clickHouseMemoryLimitErrors));
    sb.append('\n');
  }

  private static void memory(StringBuilder sb, RunReport r) {
    sb.append("## Memory breakdown (12 §11, 17 §7.1)\n\n| Item | Value |\n|---|---|\n");
    LoadOptions o = r.options;
    if (o != null) {
      row(
          sb,
          "Budgets",
          "server -Xmx "
              + o.serverHeap()
              + " + off-heap 1 GiB (direct "
              + o.maxDirectMemory()
              + "); ClickHouse container "
              + Format.bytes(o.clickHouseMemoryBytes())
              + "; PostgreSQL container "
              + Format.bytes(o.postgresMemoryBytes()));
    }
    row(
        sb,
        "Server resident set peak",
        Format.bytes(r.rssPeak)
            + " (sampled maximum "
            + Format.bytes(r.rssSampledMax)
            + "); source: "
            + r.rssSource);
    ResourceSampler.Summary s = r.sampler;
    if (s != null) {
      ResourceSampler.Peaks p = s.peaks();
      row(
          sb,
          "Server heap used / committed peak",
          Format.bytes(p.heapUsed) + " / " + Format.bytes(p.heapCommitted));
      row(sb, "Server non-heap used peak", Format.bytes(p.nonHeapUsed));
      row(sb, "Server direct buffers peak", Format.bytes(p.directUsed));
      row(sb, "Server live threads peak", p.threads < 0 ? "n/a" : Long.toString(p.threads));
      row(sb, "Sessions peak", p.sessions < 0 ? "n/a" : Long.toString(p.sessions));
      row(
          sb,
          "Writer queue rows peak",
          p.writerQueueRows < 0 ? "n/a" : Format.count(p.writerQueueRows));
      row(
          sb,
          "ClickHouse container peak (docker stats)",
          Format.bytes(p.chContainer) + " of " + Format.bytes(p.chContainerLimit));
      row(
          sb,
          "PostgreSQL container peak (docker stats)",
          Format.bytes(p.pgContainer) + " of " + Format.bytes(p.pgContainerLimit));
    }
    Jcmd.NativeMemory nmt = r.nativeMemory;
    if (nmt != null) {
      row(
          sb,
          "Native memory (NMT, end of run): committed",
          Format.bytes(nmt.committedKb() * 1024)
              + " in total, off-heap "
              + Format.bytes(nmt.committedOffHeapKb() * 1024));
      StringBuilder cats = new StringBuilder();
      nmt.committedKbByCategory().entrySet().stream()
          .filter(e -> e.getValue() >= 1024)
          .forEach(
              e ->
                  cats.append(cats.length() == 0 ? "" : ", ")
                      .append(e.getKey())
                      .append(' ')
                      .append(Format.bytes(e.getValue() * 1024)));
      row(sb, "NMT committed by category (>= 1 MiB)", cats.toString());
    } else {
      row(sb, "Native memory (NMT)", "n/a");
    }
    sb.append('\n');
  }

  private static void counters(StringBuilder sb, RunReport r) {
    sb.append("## Collector and generator counters\n\n| Item | Value |\n|---|---|\n");
    ResourceSampler.Summary s = r.sampler;
    if (s != null) {
      row(sb, "argos_collector_rejected_total by reason", byReason(s.rejectedByReason()));
      row(sb, "argos_collector_dropped_total by reason", byReason(s.droppedByReason()));
      row(
          sb,
          "Samples",
          s.samples()
              + " taken, "
              + s.sampleErrors()
              + " failed"
              + (s.errorExamples().isEmpty()
                  ? ""
                  : " (" + String.join("; ", s.errorExamples()) + ")"));
    }
    LoadCounters.Snapshot e = r.atEnd;
    if (e != null) {
      row(
          sb,
          "Generator calls",
          String.format(
              Locale.ROOT,
              "%,d calls, %,d answered with an error status, %,d retries, %,d reconnects, %,d"
                  + " throttle hints",
              e.get(Counter.CALLS),
              e.get(Counter.CALLS_FAILED),
              e.get(Counter.RETRIES),
              e.get(Counter.RECONNECTS),
              e.get(Counter.THROTTLES)));
    }
    row(sb, "Generator failures", Integer.toString(r.generatorFailureCount));
    sb.append('\n');
    if (!r.generatorFailures.isEmpty()) {
      sb.append("First generator failures:\n\n");
      r.generatorFailures.stream()
          .limit(20)
          .forEach(f -> sb.append("- ").append(f.replace('\n', ' ')).append('\n'));
      sb.append('\n');
    }
  }

  private static String byReason(Map<String, Double> byReason) {
    if (byReason.isEmpty()) {
      return "none";
    }
    StringBuilder b = new StringBuilder();
    byReason.forEach(
        (k, v) ->
            b.append(b.length() == 0 ? "" : ", ")
                .append(k.isEmpty() ? "(no reason)" : k)
                .append(' ')
                .append(String.format(Locale.ROOT, "%,.0f", v)));
    return b.toString();
  }

  private static void heapAndPinning(StringBuilder sb, RunReport r) {
    sb.append("## Heap histogram and pinned virtual threads (end of run)\n\n");
    Jcmd.ClassHistogram h = r.classHistogram;
    if (h == null) {
      sb.append("Class histogram: n/a.\n\n");
    } else {
      sb.append(
          String.format(
              Locale.ROOT,
              "Live heap after a full GC (`jcmd GC.class_histogram`): %s in %,d objects."
                  + " HdrHistogram classes (org.HdrHistogram.*): %s in %,d objects.%n%n",
              Format.bytes(h.totalBytes()),
              h.totalInstances(),
              Format.bytes(h.bytes(n -> n.startsWith("org.HdrHistogram."))),
              h.instances(n -> n.startsWith("org.HdrHistogram."))));
      sb.append("| # | Class | Instances | Bytes |\n|---|---|---|---|\n");
      List<Jcmd.HistogramRow> top = h.top(15);
      for (int i = 0; i < top.size(); i++) {
        Jcmd.HistogramRow row = top.get(i);
        sb.append(
            String.format(
                Locale.ROOT,
                "| %d | `%s` | %,d | %s |%n",
                i + 1,
                row.className(),
                row.instances(),
                Format.bytes(row.bytes())));
      }
      sb.append('\n');
    }
    if (r.pinning == null) {
      sb.append("JFR `jdk.VirtualThreadPinned`: n/a.\n\n");
    } else {
      sb.append(
          String.format(
              Locale.ROOT,
              "JFR `jdk.VirtualThreadPinned` (threshold 20 ms): %,d events, longest %d ms, total %d"
                  + " ms; top frames %s.%n%n",
              r.pinning.events(),
              r.pinning.longest().toMillis(),
              r.pinning.total().toMillis(),
              r.pinning.topFrames().isEmpty() ? "none" : r.pinning.topFrames().toString()));
    }
  }

  private static void notes(StringBuilder sb, RunReport r) {
    if (!r.notes.isEmpty()) {
      sb.append("## Notes\n\n");
      r.notes.forEach(n -> sb.append("- ").append(n).append('\n'));
      sb.append('\n');
    }
    if (!r.files.isEmpty()) {
      sb.append("## Files of the run\n\n");
      r.files.forEach(f -> sb.append("- `").append(f).append("`\n"));
      sb.append('\n');
    }
  }

  private static void row(StringBuilder sb, String item, String value) {
    sb.append("| ")
        .append(Format.cell(item))
        .append(" | ")
        .append(Format.cell(value))
        .append(" |\n");
  }
}
