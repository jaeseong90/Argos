/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.generator.report;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Renders the manifest document as a short Markdown report (report.md of a run). */
public final class ReportWriter {

  private ReportWriter() {}

  /** Markdown for a manifest document produced by the generator. */
  public static String markdown(Map<String, Object> d) {
    StringBuilder sb = new StringBuilder();
    sb.append("# Storage generator run ").append(d.get("runId")).append("\n\n");
    sb.append("| Item | Value |\n|---|---|\n");
    row(sb, "Mode", d.get("mode"));
    row(sb, "Seed / scale", d.get("seed") + " / " + d.get("scale"));
    row(sb, "Target", d.get("target"));
    row(sb, "Machine profile", d.get("machineProfile"));
    Map<String, Object> range = map(d.get("range"));
    row(sb, "Range", range.get("start") + " .. " + range.get("end"));
    row(sb, "profile_step from", range.get("profileFrom"));
    row(sb, "metric from", range.get("metricFrom"));
    row(
        sb,
        "Version switch / deploy",
        range.get("versionSwitchAt") + " / " + range.get("deployAt"));
    Map<String, Object> host = map(d.get("hostLoad"));
    row(
        sb,
        "Host CPU avg / max",
        pct(host.get("systemCpuAvg")) + " / " + pct(host.get("systemCpuMax")));
    row(sb, "Generator CPU avg", pct(host.get("generatorCpuAvg")));
    sb.append('\n');

    Map<String, Object> generated = map(d.get("generated"));
    Map<String, Object> insert = map(d.get("insert"));
    Map<String, Object> tables = map(generated.get("tables"));
    Map<String, Object> clickhouse = d.containsKey("clickhouse") ? map(d.get("clickhouse")) : null;
    Map<String, Object> storage = clickhouse == null ? Map.of() : map(clickhouse.get("storage"));
    Map<String, Object> simulation = map(d.get("simulation"));
    Map<String, Object> perSecond = map(simulation.get("rowsPerSimulatedSecond"));
    sb.append("## Tables\n\n");
    sb.append(
        "| Table | Rows generated | Rows/simulated s | Blocks | Re-sent | Rows/s while inserting"
            + " | Insert p95 ms | Compression | Bytes/row (data) | Bytes/row (disk) | Active parts"
            + " |\n");
    sb.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
    for (Map.Entry<String, Object> e : tables.entrySet()) {
      Map<String, Object> g = map(e.getValue());
      Map<String, Object> i = map(insert.get(e.getKey()));
      Map<String, Object> s =
          storage.containsKey(e.getKey()) ? map(storage.get(e.getKey())) : Map.of();
      sb.append("| ")
          .append(e.getKey())
          .append(" | ")
          .append(num(g.get("rows")))
          .append(" | ")
          .append(num(perSecond.get(e.getKey())))
          .append(" | ")
          .append(num(i.get("blocks")))
          .append(" | ")
          .append(num(i.get("duplicateSends")))
          .append(" | ")
          .append(num(i.get("rowsPerSecondWhileInserting")))
          .append(" | ")
          .append(num(map(i.get("insertMs")).get("p95")))
          .append(" | ")
          .append(dec(s.get("compressionRatio")))
          .append(" | ")
          .append(dec(s.get("bytesPerRow")))
          .append(" | ")
          .append(dec(s.get("diskBytesPerRow")))
          .append(" | ")
          .append(num(s.get("activeParts")))
          .append(" |\n");
    }
    sb.append(
        "\nBytes/row (data) = data_compressed_bytes / rows (the compression basis of 13 §15);"
            + " Bytes/row (disk) = bytes_on_disk / rows, with skip indexes, marks and primary"
            + " index (the capacity basis of 13 §14, N-19).\n");
    if (!simulation.isEmpty()) {
      sb.append("\nSimulation: tick ")
          .append(num(simulation.get("tickMs")))
          .append(" ms, ")
          .append(num(simulation.get("instances")))
          .append(" instances, active SQL per instance ")
          .append(num(simulation.get("activeSqlsPerInstance")))
          .append(" (0 = usual statements), statistics rows/simulated s ")
          .append(num(perSecond.get("statistics")))
          .append(", all rows/simulated s ")
          .append(num(perSecond.get("total")));
      if (simulation.containsKey("profileStepsPerSecondAchieved")) {
        sb.append(", profile_step rows/s target ")
            .append(num(simulation.get("profileStepsPerSecondTarget")))
            .append(" achieved ")
            .append(num(simulation.get("profileStepsPerSecondAchieved")))
            .append(" (normal capture rate mean ")
            .append(dec(simulation.get("normalCaptureRateMean")))
            .append(")");
      }
      sb.append(".\n");
    }
    Map<String, Object> total = map(insert.get("total"));
    sb.append("\nTotal: ")
        .append(num(total.get("rows")))
        .append(" rows in ")
        .append(dec(((Number) total.get("elapsedMs")).doubleValue() / 1000))
        .append(" s = ")
        .append(num(total.get("rowsPerSecond")))
        .append(" rows/s, ")
        .append(dec(total.get("bodyMiBPerSecond")))
        .append(" MiB/s RowBinary.\n\n");
    if (clickhouse != null) {
      sb.append("Rollup tables after the load:\n\n");
      sb.append("| Table | Rows | Compression | Bytes/row (data) | Bytes/row (disk) |\n");
      sb.append("|---|---:|---:|---:|---:|\n");
      for (Map.Entry<String, Object> e : storage.entrySet()) {
        if (tables.containsKey(e.getKey())) {
          continue;
        }
        Map<String, Object> s = map(e.getValue());
        sb.append("| ")
            .append(e.getKey())
            .append(" | ")
            .append(num(s.get("rows")))
            .append(" | ")
            .append(dec(s.get("compressionRatio")))
            .append(" | ")
            .append(dec(s.get("bytesPerRow")))
            .append(" | ")
            .append(dec(s.get("diskBytesPerRow")))
            .append(" |\n");
      }
      sb.append("\nRaw counts match the generator counters: ")
          .append(clickhouse.get("rawCountsMatchManifest"))
          .append("\n\n");
      Map<String, Object> monitor = map(clickhouse.get("monitor"));
      sb.append("ClickHouse during the run: max active parts per partition ")
          .append(num(monitor.get("maxActivePartsPerPartition")))
          .append(", peak MemoryTracking ")
          .append(mib(monitor.get("peakMemoryTrackingBytes")))
          .append(" MiB, max merges ")
          .append(num(monitor.get("maxMergesRunning")));
      Object last = monitor.get("last");
      if (last != null) {
        sb.append(", DelayedInserts ").append(num(map(last).get("delayedInserts")));
      }
      sb.append(".\n\n");
    }
    Map<String, Object> d12 = map(generated.get("d12Totals"));
    sb.append("## D-12 totals\n\n| Sum | xlog | statistics |\n|---|---:|---:|\n");
    sb.append("| SQL executions | ")
        .append(num(d12.get("xlogSqlCount")))
        .append(" | ")
        .append(num(d12.get("sqlStatCount")))
        .append(" |\n");
    sb.append("| SQL time (ms) | ")
        .append(num(d12.get("xlogSqlTimeMs")))
        .append(" | ")
        .append(num(d12.get("sqlStatSumMs")))
        .append(" |\n");
    sb.append("| Outgoing calls | ")
        .append(num(d12.get("xlogExtCount")))
        .append(" | ")
        .append(num(d12.get("extStatCount")))
        .append(" |\n\n");
    sb.append("Per-day values (by xlog ts and by statistics window ts) are in manifest.json.\n\n");
    if (insert.containsKey("ingestLagMs")) {
      sb.append("## Ingest lag (receive to ack)\n\n");
      Map<String, Object> lag = map(insert.get("ingestLagMs"));
      sb.append("All rows: p50 ")
          .append(num(lag.get("p50")))
          .append(" ms, p99 ")
          .append(num(lag.get("p99")))
          .append(" ms, max ")
          .append(num(lag.get("max")))
          .append(" ms.\n\n");
      Object steps = insert.get("ingestLagMsPerStep");
      Map<String, Object> replay = d.containsKey("replay") ? map(d.get("replay")) : Map.of();
      Object tps = replay.get("tpsSteps");
      if (steps instanceof List<?> list && tps instanceof List<?> tpsList) {
        sb.append("| TPS step | Rows | p50 ms | p99 ms | max ms |\n|---:|---:|---:|---:|---:|\n");
        for (int i = 0; i < list.size(); i++) {
          Map<String, Object> s = map(list.get(i));
          sb.append("| ")
              .append(i < tpsList.size() ? tpsList.get(i) : "?")
              .append(" | ")
              .append(num(s.get("count")))
              .append(" | ")
              .append(num(s.get("p50")))
              .append(" | ")
              .append(num(s.get("p99")))
              .append(" | ")
              .append(num(s.get("max")))
              .append(" |\n");
        }
        sb.append('\n');
      }
    }
    return sb.toString();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object o) {
    return o instanceof Map<?, ?> ? (Map<String, Object>) o : Map.of();
  }

  private static void row(StringBuilder sb, String key, Object value) {
    sb.append("| ")
        .append(key)
        .append(" | ")
        .append(value == null || value.equals("") ? "-" : value)
        .append(" |\n");
  }

  private static String num(Object o) {
    if (o instanceof Number n) {
      return String.format(Locale.ROOT, "%,d", Math.round(n.doubleValue()));
    }
    return "-";
  }

  private static String dec(Object o) {
    if (o instanceof Number n) {
      return String.format(Locale.ROOT, "%.2f", n.doubleValue());
    }
    return "-";
  }

  private static String pct(Object o) {
    if (o instanceof Number n && n.doubleValue() >= 0) {
      return String.format(Locale.ROOT, "%.0f%%", n.doubleValue() * 100);
    }
    return "-";
  }

  private static String mib(Object o) {
    if (o instanceof Number n) {
      return String.format(Locale.ROOT, "%,.0f", n.doubleValue() / 1048576.0);
    }
    return "-";
  }
}
