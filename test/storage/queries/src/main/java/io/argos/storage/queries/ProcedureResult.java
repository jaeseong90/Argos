/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.queries;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Result of a procedure (Q11 cost, Q14..Q20, bloom variants): named metrics judged by the file's
 * criterion, tables and notes for the report.
 */
public final class ProcedureResult {

  private final String id;
  private final String title;
  private final Criterion criterion;
  private final Map<String, Double> metrics = new LinkedHashMap<>();
  private final List<Table> tables = new ArrayList<>();
  private final List<String> notes = new ArrayList<>();
  private Verdict verdict;
  private final List<String> violations = new ArrayList<>();

  /**
   * @param id {@code Q14} or a procedure name
   * @param title title
   * @param criterion criterion judged on {@link #metric}
   */
  public ProcedureResult(String id, String title, Criterion criterion) {
    this.id = id;
    this.title = title;
    this.criterion = criterion;
  }

  /** Records a metric. */
  public ProcedureResult metric(String name, double value) {
    metrics.put(name, value);
    return this;
  }

  /** Records an integer metric (counts, bytes; exact below 2^53). */
  public ProcedureResult metric(String name, long value) {
    return metric(name, (double) value);
  }

  /** Adds a table. */
  public ProcedureResult table(Table table) {
    tables.add(table);
    return this;
  }

  /** Adds a note. */
  public ProcedureResult note(String text) {
    notes.add(text);
    return this;
  }

  /** Judges the metrics against the criterion (call once all metrics are in). */
  public ProcedureResult judge() {
    verdict = criterion.evaluate(metrics);
    violations.clear();
    violations.addAll(criterion.violations(metrics));
    return this;
  }

  /** Marks the procedure as failed with an error. */
  public ProcedureResult error(String message) {
    verdict = Verdict.ERROR;
    violations.add(message);
    return this;
  }

  /** Marks the procedure as skipped. */
  public ProcedureResult skipped(String reason) {
    verdict = Verdict.SKIPPED;
    notes.add(reason);
    return this;
  }

  /** Id. */
  public String id() {
    return id;
  }

  /** Title. */
  public String title() {
    return title;
  }

  /** Criterion. */
  public Criterion criterion() {
    return criterion;
  }

  /** Metrics. */
  public Map<String, Double> metrics() {
    return metrics;
  }

  /** A metric or NaN. */
  public double metricValue(String name) {
    return metrics.getOrDefault(name, Double.NaN);
  }

  /** Tables. */
  public List<Table> tables() {
    return List.copyOf(tables);
  }

  /** Notes. */
  public List<String> notes() {
    return List.copyOf(notes);
  }

  /** Verdict (null before {@link #judge}). */
  public Verdict verdict() {
    return verdict;
  }

  /** Violations and errors. */
  public List<String> violations() {
    return List.copyOf(violations);
  }

  @Override
  public String toString() {
    return id + " " + verdict + " " + metrics + (violations.isEmpty() ? "" : " " + violations);
  }

  /**
   * A table of the report.
   *
   * @param title caption
   * @param headers column headers
   * @param rows cells
   */
  public record Table(String title, List<String> headers, List<List<String>> rows) {
    public Table {
      headers = List.copyOf(headers);
      rows = rows.stream().map(List::copyOf).toList();
    }
  }
}
