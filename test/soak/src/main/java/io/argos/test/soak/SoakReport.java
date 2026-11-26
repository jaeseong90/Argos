/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.soak;

import io.argos.test.soak.SeriesMath.Point;
import io.argos.test.soak.SoakSamples.AgentSample;
import io.argos.test.soak.SoakSamples.Segment;
import io.argos.test.soak.SoakSamples.StackSample;
import io.argos.test.soak.SoakVerdicts.ConsistencyRun;
import io.argos.test.soak.SoakVerdicts.Criterion;
import io.argos.test.soak.SoakVerdicts.Firing;
import io.argos.test.soak.SoakVerdicts.Verdict;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.ToLongFunction;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The soak report ({@code report.md}, published as {@code docs/bench/soak-<date>.md}) and {@code
 * results.json} (one row per criterion with its test id, for the traceability report).
 */
public final class SoakReport {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** The first line of the banner of a run whose time and resource verdicts are INVALID. */
  public static final String INVALID_BANNER =
      "> **INVALID run: reference values only.** The time and resource verdicts of this report";

  private SoakReport() {}

  /** The file name of a published report: {@code soak-<yyyy-MM-dd>.md} of the start (UTC). */
  public static String publishedName(Instant started) {
    return "soak-" + LocalDate.ofInstant(started, ZoneOffset.UTC) + ".md";
  }

  /** The Markdown report. */
  public static String markdown(RunFacts f, SoakSamples s, List<Criterion> criteria) {
    StringBuilder b = new StringBuilder();
    SoakOptions o = f.options;
    String invalid = o.invalidReason().orElse(null);
    b.append("# Soak test (18 §11.2)\n\n");
    if (invalid != null) {
      b.append(INVALID_BANNER)
          .append(" are not\n> judged: ")
          .append(invalid)
          .append(". 18 §11.2 needs 72 hours at 500 TPS on the dedicated soak machine")
          .append("\n> (`argos-bench-app`, 18 §2.3). The invariants (OOM 0, consistency check,")
          .append(" ClickHouse settings)\n> are PASS/FAIL in every run.\n\n");
    }
    if (f.aborted) {
      b.append("> **The run aborted** before its end; see the notes.\n\n");
    }
    b.append("## Run\n\n| | |\n|---|---|\n");
    row(b, "Started (UTC)", text(f.started));
    row(b, "Measured window", text(f.windowStart) + " to " + text(f.windowEnd));
    row(b, "Run ended (drain, checks, report)", text(f.finished));
    row(b, "Planned duration", SoakOptions.format(o.duration()));
    row(b, "Machine profile", "`" + o.machineProfile() + "`");
    row(b, "Machine", f.machine);
    row(b, "Commit", f.commit);
    row(b, "Harness JVM", f.java);
    row(
        b,
        "Load",
        o.tps()
            + " requests/s, sample-apps/load `--mix "
            + o.mix()
            + "` (18 §7.2) on shop-boot3"
            + (o.virtualThreads()
                ? " with `SAMPLE_VT=true` (JDK 21 virtual threads, C-232 (9))"
                : ""));
    row(b, "Intervals", f.schedule.describe());
    row(b, "Stack", f.stack);
    row(b, "Server JVM", f.serverJvm);
    row(b, "Server RSS source", f.rssSource);
    row(b, "FD source", f.fdSource);
    b.append('\n');

    b.append("## Verdicts\n\n");
    b.append("| ID | Requirement | Criterion | Limit | Measured | Verdict | Note |\n");
    b.append("|---|---|---|---|---|---|---|\n");
    for (Criterion c : criteria) {
      b.append("| ")
          .append(c.id())
          .append(" | ")
          .append(c.requirement())
          .append(" | ")
          .append(cell(c.title()))
          .append(c.invariant() ? " (invariant)" : "")
          .append(" | ")
          .append(cell(c.limit()))
          .append(" | ")
          .append(cell(c.measured()))
          .append(" | **")
          .append(c.verdict())
          .append("** | ")
          .append(cell(c.reason()))
          .append(" |\n");
    }
    b.append('\n');

    b.append("## Series\n\n");
    b.append("| Series | Points | First | Last | Min | Max | Slope per hour |\n");
    b.append("|---|---|---|---|---|---|---|\n");
    agentRow(b, s, "agent heap (io.argos., bytes)", AgentSample::heapBytes);
    agentRow(b, s, "agent buffer estimate (bytes)", AgentSample::bufferBytes);
    agentRow(
        b, s, "agent heap judged (io.argos. + buffers, bytes; C-259 (5))", AgentSample::agentBytes);
    agentRow(
        b, s, "app live heap after GC (all classes, bytes; reference)", AgentSample::liveHeapBytes);
    agentRow(b, s, "NMT Internal+Other+Thread (KB)", AgentSample::nmtKb);
    agentRow(b, s, "agent threads (argos-*)", AgentSample::agentThreads);
    agentRow(b, s, "JVM threads (NMT)", AgentSample::jvmThreads);
    stackRow(b, s, "app FDs / handles", StackSample::appFds);
    stackRow(b, s, "server RSS (bytes)", StackSample::serverRssBytes);
    stackRow(b, s, "server heap used (bytes)", StackSample::serverHeapUsed);
    stackRow(b, s, "server non-heap used (bytes)", StackSample::serverNonHeapUsed);
    stackRow(b, s, "server direct buffers (bytes)", StackSample::serverDirectBytes);
    stackRow(b, s, "WAL (bytes)", StackSample::walBytes);
    stackRow(b, s, "WAL unloaded backlog (bytes; C-259 (4))", StackSample::walBacklogBytes);
    stackRow(b, s, "ClickHouse max parts per partition", StackSample::maxPartsPerPartition);
    stackRow(b, s, "ClickHouse running merges", StackSample::merges);
    stackRow(b, s, "ClickHouse argos bytes on disk", StackSample::diskBytes);
    b.append("\nEvery sample is in `samples.tsv` (ts, elapsed_s, series, name, value).\n\n");

    b.append("## App p99 per hourly point\n\n");
    List<Segment> segs = s.segments();
    if (segs.isEmpty()) {
      b.append("No load segment finished.\n\n");
    } else {
      int k =
          SoakSchedule.p99BaselinePoints(
              (int)
                  segs.stream()
                      .filter(x -> x.exitCode() == 0 && !Double.isNaN(x.p99Millis()))
                      .count());
      b.append("| # | Start (s) | Sent | p99 (ms) | Unexpected | 5xx | Exit | Baseline |\n");
      b.append("|---|---|---|---|---|---|---|---|\n");
      int valid = 0;
      for (Segment g : segs) {
        boolean ok = g.exitCode() == 0 && !Double.isNaN(g.p99Millis());
        b.append(
            String.format(
                Locale.ROOT,
                "| %d | %.0f | %d | %.1f | %.4f | %.4f | %d | %s |%n",
                g.index(),
                g.seconds(),
                g.sent(),
                g.p99Millis(),
                g.unexpectedRate(),
                g.http5xxRate(),
                g.exitCode(),
                ok && valid++ < k ? "yes" : ""));
      }
      b.append('\n');
    }

    b.append("## Nightly consistency check\n\n");
    if (f.consistency == null || f.consistency.isEmpty()) {
      b.append("Not run.\n\n");
    } else {
      b.append(
          "| Day (UTC) | Exit | Result | Rollup mismatch (min) | D-12 (min, recorded) | Error |\n");
      b.append("|---|---|---|---|---|---|\n");
      for (ConsistencyRun r : f.consistency) {
        b.append("| ")
            .append(r.day())
            .append(" | ")
            .append(r.exitCode())
            .append(" | ")
            .append(cell(r.result()))
            .append(" | ")
            .append(r.rollupMismatchMinutes())
            .append(" | ")
            .append(r.d12MismatchMinutes())
            .append(" | ")
            .append(cell(r.error()))
            .append(" |\n");
      }
      b.append('\n');
    }

    b.append("## Alert events (FIRING)\n\n");
    if (f.firing == null) {
      b.append("Not read.\n\n");
    } else if (f.firing.isEmpty()) {
      b.append("None.\n\n");
    } else {
      b.append("| Time | App | Metric | Severity | Value | Threshold | Expected by the load |\n");
      b.append("|---|---|---|---|---|---|---|\n");
      for (Firing x : f.firing) {
        b.append(
            String.format(
                Locale.ROOT,
                "| %s | %s | %s | %s | %.4f | %.4f | %s |%n",
                cell(x.ts()),
                cell(x.app()),
                cell(x.metric()),
                cell(x.severity()),
                x.value(),
                x.threshold(),
                x.expectedByLoad() ? "yes" : "no"));
      }
      b.append('\n');
    }

    b.append("## End of the run\n\n| | |\n|---|---|\n");
    row(b, "Load requests sent", Long.toString(f.loadSent) + " in " + f.loadSegments + " segments");
    row(
        b,
        "shop-batch runs",
        f.batchRuns + " started, " + f.batchRefused + " refused, last state " + f.batchLastState);
    for (Map.Entry<String, String> e : f.endState.entrySet()) {
      row(b, e.getKey(), e.getValue());
    }
    row(
        b,
        "OOM checks not available",
        f.oomUnavailable.isEmpty() ? "none" : String.join("; ", f.oomUnavailable));
    b.append('\n');

    b.append("## Method and deviations\n\n");
    b.append(
        """
        - Stack (18 §11.2): ServerTestEnvironment (Testcontainers PostgreSQL and ClickHouse with \
        the installation's configuration), the server boot jar as a child JVM (LoadServerJvm, 17 \
        §7.2 options, NMT), shop-boot3 and shop-batch with the agent (gRPC over TLS to the \
        collector, `-XX:NativeMemoryTracking=summary`, self-diagnostics file), ext-stub, and the \
        sample-apps/load generator, all on one host.
        - Deviation: the load is the sample-apps/load generator (bench mix of 18 §7.2, own Java \
        code, p99 per run) instead of k6; one generator run per hourly point gives the hourly p99.
        - Deviation: Testcontainers instead of a Compose `test` profile (the server load run's \
        layout); the Compose profile is a follow-up proposal.
        - Single host: the split load generator of ADR-0010 C-244 (6) is not built; the generator \
        costs little at 500 requests/s (a few percent of one core) and its CPU shows in the host's \
        load, not in the agent's measurements.
        - Agent heap: `jcmd GC.run` twice, then `GC.class_histogram`, bytes of the classes whose \
        name starts with `io.argos.` (the agent's own and relocated classes; arrays the agent owns \
        count under their array class, so the app's whole live heap of the same histogram is \
        listed as a reference series). NMT: committed Internal + Other + Thread of \
        `VM.native_memory summary`. Agent threads: `argos-*` names of `Thread.print`.
        - Agent heap criteria (ADR-0010 C-259 (5)): the `io.argos.` classes of the histogram plus \
        the agent's own buffer estimate (`argos_agent_heap_bytes_estimated`: queues, dictionary \
        texts and service names, the newest value the agent sent before the sample, read from the \
        `metric` table); the app's live heap is a reference series only. A sample without either \
        value is left out of the slope and the maximum.
        - WAL criteria (ADR-0010 C-259 (4)): the unloaded backlog, the bytes from the oldest data \
        record not yet in ClickHouse to the write position (a read-only scan of the WAL segments: \
        committed block intents per table against the data records), below 100 MB; the WAL size \
        (`argos_wal_bytes`) at most `argos.wal.segment-bytes` (256 MiB, 12 §3.4) + 100 MB as a \
        secondary condition, because the WAL deletes only closed segments and so holds about one \
        segment in normal operation.
        - p99 verdict: mean of the later hourly p99 points minus the mean of the first 6 (the first \
        half when the run has fewer than 12), against twice the baseline's sample standard \
        deviation (at least 5% of its mean).
        - Alert false positives: FIRING events of the template rules (`/alerts/templates/apply` for \
        both apps). The bench mix has 5% intended `500` answers, which meets the `error_rate` WARN \
        threshold of 5% by design, and the collector certificate the harness generates is valid \
        for 2 days, which fires the server self rule on certificate expiry: such firings are \
        listed as expected and not counted.
        - Disk growth: bytes on disk of the formula's tables against `Σ rows × B` of 13 §14 \
        (N-19), between the first and the last 10-minute sample.
        - Not judged here: T-NFR-28 (virtual thread overhead, WO-51 bench); `-Psoak.vt=true` only \
        runs the soak with virtual threads.

        """);
    List<String> notes = f.notes();
    b.append("## Notes\n\n");
    if (notes.isEmpty()) {
      b.append("None.\n\n");
    } else {
      for (String n : notes) {
        b.append("- ").append(cell(n)).append('\n');
      }
      b.append('\n');
    }
    b.append("## Files\n\n");
    for (String file : f.files) {
      b.append("- `").append(file).append("`\n");
    }
    b.append('\n');
    b.append("## For a person\n\n");
    b.append(
        """
        - The 72-hour run on the dedicated soak machine (`argos-bench-app`, 18 §2.3):
          `./gradlew soakTest -Pbench.machine-profile=argos-bench-app -Psoak.publish=true`
          (`.github/workflows/soak.yml`). Until a valid run exists the release notes keep saying \
        that the 72-hour soak has not run (ADR-0010 C-246 (2)).
        """);
    return b.toString();
  }

  /** {@code results.json}. */
  public static String json(RunFacts f, List<Criterion> criteria, int exitCode) {
    ObjectNode root = JSON.createObjectNode();
    root.put("tool", "argos-soak");
    root.put("design", "docs/18-test-design.md §11.2");
    root.put("started", text(f.started));
    root.put("finished", text(f.finished));
    root.put("machineProfile", f.options.machineProfile());
    root.put("durationSeconds", f.options.duration().toSeconds());
    root.put("tps", f.options.tps());
    root.put("virtualThreads", f.options.virtualThreads());
    String invalid = f.options.invalidReason().orElse(null);
    root.put("valid", invalid == null);
    if (invalid != null) {
      root.put("invalidReason", invalid);
    }
    root.put("aborted", f.aborted);
    root.put("exitCode", exitCode);
    ArrayNode rows = root.putArray("results");
    for (Criterion c : criteria) {
      ObjectNode n = rows.addObject();
      n.put("id", c.id());
      n.put("requirement", c.requirement());
      n.put("criterion", c.title());
      n.put("limit", c.limit());
      n.put("measured", c.measured());
      n.put("invariant", c.invariant());
      n.put("verdict", c.verdict().name());
      n.put("reason", c.reason());
    }
    return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n";
  }

  /** The verdict counts of a run, for the log. */
  static String counts(List<Criterion> criteria) {
    long pass = criteria.stream().filter(c -> c.verdict() == Verdict.PASS).count();
    long fail = criteria.stream().filter(c -> c.verdict() == Verdict.FAIL).count();
    long invalid = criteria.stream().filter(c -> c.verdict() == Verdict.INVALID).count();
    return pass + " PASS, " + fail + " FAIL, " + invalid + " INVALID";
  }

  private static void agentRow(
      StringBuilder b, SoakSamples s, String name, ToLongFunction<AgentSample> f) {
    seriesRow(b, name, s.agentSeries(f));
  }

  private static void stackRow(
      StringBuilder b, SoakSamples s, String name, ToLongFunction<StackSample> f) {
    seriesRow(b, name, s.stackSeries(f));
  }

  private static void seriesRow(StringBuilder b, String name, List<Point> p) {
    if (p.isEmpty()) {
      b.append("| ").append(name).append(" | 0 | | | | | |\n");
      return;
    }
    b.append(
        String.format(
            Locale.ROOT,
            "| %s | %d | %d | %d | %d | %d | %s |%n",
            name,
            p.size(),
            Math.round(p.get(0).value()),
            Math.round(p.get(p.size() - 1).value()),
            Math.round(p.stream().mapToDouble(Point::value).min().orElse(Double.NaN)),
            Math.round(SeriesMath.max(p)),
            p.size() < 2 ? "" : String.format(Locale.ROOT, "%+.1f", SeriesMath.slopePerHour(p))));
  }

  private static void row(StringBuilder b, String key, String value) {
    b.append("| ").append(key).append(" | ").append(cell(value)).append(" |\n");
  }

  static String cell(String text) {
    return text == null ? "" : text.replace("|", "\\|").replace('\n', ' ');
  }

  private static String text(Instant t) {
    return t == null ? "" : t.toString();
  }

  /** A duration for the log. */
  static String text(Duration d) {
    return SoakOptions.format(d);
  }
}
