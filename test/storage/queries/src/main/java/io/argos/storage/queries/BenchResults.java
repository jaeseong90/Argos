/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.queries;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Everything one run produced, for {@link ReportWriter}. */
public final class BenchResults {

  private final Map<String, String> environment = new LinkedHashMap<>();
  private final List<VariantResult> variants = new ArrayList<>();
  private final List<ProcedureResult> procedures = new ArrayList<>();
  private final List<String> errors = new ArrayList<>();
  private Map<String, QueryLog.Entry> queryLog = Map.of();
  private Map<String, Measurement> stepRuns = Map.of();
  private Parameters parameters = Parameters.empty();
  private ProcedureResult.Table data;
  private MachineLoad load;
  private final Instant started = Instant.now();

  /** Adds an environment line (report header). */
  public BenchResults environment(String key, Object value) {
    environment.put(key, String.valueOf(value));
    return this;
  }

  /** Adds variant results. */
  public BenchResults variants(List<VariantResult> results) {
    variants.addAll(results);
    return this;
  }

  /** Adds procedure results. */
  public BenchResults procedures(List<ProcedureResult> results) {
    procedures.addAll(results);
    return this;
  }

  /** Records an error that stopped part of the run. */
  public BenchResults error(String message) {
    errors.add(message);
    return this;
  }

  /** The query_log records and step runs of the run. */
  public BenchResults log(Map<String, QueryLog.Entry> log, Map<String, Measurement> steps) {
    this.queryLog = Map.copyOf(log);
    this.stepRuns = Map.copyOf(steps);
    return this;
  }

  /** The shared parameters used. */
  public BenchResults parameters(Parameters value) {
    this.parameters = value;
    return this;
  }

  /** The table of the loaded data (sizes per table). */
  public BenchResults data(ProcedureResult.Table value) {
    this.data = value;
    return this;
  }

  /** The machine load sampler of the run (CPU load of every measurement), may be null. */
  public BenchResults load(MachineLoad value) {
    this.load = value;
    return this;
  }

  /** The machine load sampler, or null. */
  public MachineLoad load() {
    return load;
  }

  /** Machine CPU load in [0, 1] while a statement ran; NaN without a sampler. */
  public double cpuLoad(Measurement m) {
    return m.cpuLoad(load);
  }

  /**
   * Waits (at most 2 s) until the sampler has a sample after the end of the last measurement, so
   * that the CPU load of a short last query is known when the report is written.
   */
  public void settleLoad() {
    if (load == null) {
      return;
    }
    long last = Long.MIN_VALUE;
    for (VariantResult v : variants) {
      for (Measurement m : v.runs()) {
        last = Math.max(last, m.endNanos());
      }
    }
    for (Measurement m : stepRuns.values()) {
      last = Math.max(last, m.endNanos());
    }
    if (last != Long.MIN_VALUE) {
      load.awaitSampleAfter(last, Duration.ofSeconds(2));
    }
  }

  /** Environment lines. */
  public Map<String, String> environment() {
    return environment;
  }

  /** Variant results. */
  public List<VariantResult> variants() {
    return List.copyOf(variants);
  }

  /** Procedure results. */
  public List<ProcedureResult> procedures() {
    return List.copyOf(procedures);
  }

  /** Errors. */
  public List<String> errors() {
    return List.copyOf(errors);
  }

  /** query_log records by query id. */
  public Map<String, QueryLog.Entry> queryLog() {
    return queryLog;
  }

  /** Step measurements by query id. */
  public Map<String, Measurement> stepRuns() {
    return stepRuns;
  }

  /** Shared parameters. */
  public Parameters parameters() {
    return parameters;
  }

  /** Loaded data table, may be null. */
  public ProcedureResult.Table data() {
    return data;
  }

  /** Start of the run. */
  public Instant started() {
    return started;
  }

  /** True when no variant or procedure failed its criterion or errored. */
  public boolean allPassed() {
    return errors.isEmpty()
        && variants.stream().noneMatch(v -> bad(v.verdict()))
        && procedures.stream().noneMatch(p -> bad(p.verdict()));
  }

  private static boolean bad(Verdict v) {
    return v == Verdict.FAIL || v == Verdict.ERROR || v == Verdict.INVALID;
  }
}
