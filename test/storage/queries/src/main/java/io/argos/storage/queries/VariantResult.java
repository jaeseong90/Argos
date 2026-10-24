/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.queries;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Result of one variant: its runs (cold first, then warm), the metrics derived from them, the
 * verdict against the criterion and the expectation, and the server-side records of
 * system.query_log once collected.
 *
 * <p>Metrics: {@code cold_ms} (wall time of the cold run), {@code warm_ms} (median wall time of the
 * warm runs; for a single-run check the time of that run), {@code rows} (result rows of the last
 * run), {@code server_warm_ms} (median query_duration_ms of the warm runs, after collection).
 */
public final class VariantResult {

  private final String fileId;
  private final String variant;
  private final String title;
  private final Criterion criterion;
  private final Criterion expect;
  private final List<Measurement> runs;
  private final Map<String, Double> metrics = new LinkedHashMap<>();
  private final Map<String, QueryLog.Entry> serverRuns = new LinkedHashMap<>();
  private final String note;
  private Verdict verdict;
  private final List<String> violations = new ArrayList<>();

  VariantResult(
      String fileId,
      String variant,
      String title,
      Criterion criterion,
      Criterion expect,
      List<Measurement> runs,
      String note) {
    this.fileId = fileId;
    this.variant = variant;
    this.title = title;
    this.criterion = criterion;
    this.expect = expect;
    this.runs = List.copyOf(runs);
    this.note = note;
    judge();
  }

  /** A variant that was not run. */
  static VariantResult skipped(
      String fileId, String variant, String title, Criterion criterion, String reason) {
    return new VariantResult(fileId, variant, title, criterion, null, List.of(), reason);
  }

  /** A variant that does not apply to the data (an optional probe found no value). */
  static VariantResult notApplicable(
      String fileId, String variant, String title, Criterion criterion, String reason) {
    VariantResult r =
        new VariantResult(
            fileId, variant, title, criterion, null, List.of(), "not applicable: " + reason);
    r.verdict = Verdict.NOT_APPLICABLE;
    return r;
  }

  private void judge() {
    if (runs.isEmpty()) {
      verdict = Verdict.SKIPPED;
      return;
    }
    if (runs.stream().anyMatch(Measurement::failed)) {
      verdict = Verdict.ERROR;
      runs.stream()
          .filter(Measurement::failed)
          .findFirst()
          .ifPresent(m -> violations.add(m.errorName() + ": " + m.errorMessage()));
      return;
    }
    Measurement first = runs.get(0);
    List<Double> warm = new ArrayList<>();
    for (Measurement m : runs) {
      if (m.phase().startsWith("warm")) {
        warm.add(m.wallMs());
      }
    }
    metrics.put("cold_ms", first.wallMs());
    metrics.put("warm_ms", warm.isEmpty() ? first.wallMs() : Stats.median(warm));
    metrics.put("rows", (double) runs.get(runs.size() - 1).rows());
    if (expect != null && expect.evaluate(metrics) != Verdict.PASS) {
      verdict = Verdict.INVALID;
      violations.add(
          "expectation " + expect + " not met: rows = " + runs.get(runs.size() - 1).rows());
      return;
    }
    verdict = criterion.evaluate(metrics);
    violations.addAll(criterion.violations(metrics));
  }

  /** Attaches the system.query_log records of the runs. */
  void attach(Map<String, QueryLog.Entry> log) {
    List<Double> warm = new ArrayList<>();
    for (Measurement m : runs) {
      QueryLog.Entry e = log.get(m.queryId());
      if (e != null) {
        serverRuns.put(m.queryId(), e);
        if (m.phase().startsWith("warm") || runs.size() == 1) {
          warm.add((double) e.durationMs());
        }
      }
    }
    if (!warm.isEmpty()) {
      metrics.put("server_warm_ms", Stats.median(warm));
    }
  }

  /** {@code Q01}. */
  public String fileId() {
    return fileId;
  }

  /** Variant name. */
  public String variant() {
    return variant;
  }

  /** {@code Q01/1h}. */
  public String label() {
    return fileId + "/" + variant;
  }

  /** Title of the file. */
  public String title() {
    return title;
  }

  /** The criterion applied. */
  public Criterion criterion() {
    return criterion;
  }

  /** Runs, cold first. */
  public List<Measurement> runs() {
    return runs;
  }

  /** Derived metrics. */
  public Map<String, Double> metrics() {
    return metrics;
  }

  /** Metric value or NaN. */
  public double metric(String name) {
    return metrics.getOrDefault(name, Double.NaN);
  }

  /** query_log records by query id (after collection). */
  public Map<String, QueryLog.Entry> serverRuns() {
    return serverRuns;
  }

  /** The record of the last run, or null. */
  public QueryLog.Entry lastServerRun() {
    if (runs.isEmpty()) {
      return null;
    }
    return serverRuns.get(runs.get(runs.size() - 1).queryId());
  }

  /** Verdict. */
  public Verdict verdict() {
    return verdict;
  }

  /** Violated terms, errors, failed expectations. */
  public List<String> violations() {
    return List.copyOf(violations);
  }

  /** Why the variant was skipped, or a remark; may be null. */
  public String note() {
    return note;
  }

  /** Result rows of the last run, unescaped (when the body was kept). */
  public List<List<String>> lastRows() {
    if (runs.isEmpty() || runs.get(runs.size() - 1).body() == null) {
      return List.of();
    }
    return Tsv.rows(runs.get(runs.size() - 1).body());
  }

  /** Mean machine CPU load over the runs (computed from the sampler when the report is written). */
  public double cpuLoad(MachineLoad load) {
    return runs.stream()
        .mapToDouble(m -> m.cpuLoad(load))
        .filter(v -> !Double.isNaN(v))
        .average()
        .orElse(Double.NaN);
  }

  @Override
  public String toString() {
    return label() + " " + verdict + " " + metrics + (violations.isEmpty() ? "" : " " + violations);
  }
}
