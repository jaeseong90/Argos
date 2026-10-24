/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.queries;

import io.argos.storage.clickhouse.Account;
import io.argos.storage.clickhouse.ClickHouseException;
import io.argos.storage.clickhouse.ClickHouseHttp;
import io.argos.storage.queries.QueryFile.Kind;
import io.argos.storage.queries.QueryFile.Section;
import io.argos.storage.schema.SchemaParameters;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs the statements of the query files against a {@link Server}.
 *
 * <ul>
 *   <li>Timing variants: one cold run (after the {@link ColdStrategy}) and {@code warmRuns} warm
 *       runs; the criterion is judged on the median client wall time of the warm runs (13 §15, 18
 *       §9: cold 1 + warm 3 median).
 *   <li>Consistency variants: one run; the criterion is judged on the result rows.
 *   <li>Steps: one run, for the procedures.
 * </ul>
 *
 * Every statement runs with the account of its section (default: the file's), the file and section
 * settings as request parameters (argos_reader accepts only lower limits, users.d/argos.xml), its
 * typed parameters as {@code param_<name>}, and a query id {@code <runId>-<label>-<phase>} that
 * {@link #collectQueryLog} later finds in system.query_log. The client timeout is the statement's
 * max_execution_time plus a slack, so a hung server cannot block the run.
 */
public final class QueryRunner {

  private final Server server;
  private final SchemaParameters schema;
  private final Options options;
  private final MachineLoad load;
  private final List<VariantResult> results = new ArrayList<>();
  private final Map<String, Measurement> stepRuns = new LinkedHashMap<>();
  private final Map<String, QueryLog.Entry> serverLog = new LinkedHashMap<>();
  private final Map<String, String> notApplicable = new ConcurrentHashMap<>();
  private int sequence;

  /**
   * Runner options.
   *
   * @param warmRuns warm runs per timing variant (13 §15: 3)
   * @param cold how the cold run is prepared
   * @param timeoutSlack client timeout = max_execution_time + slack
   * @param runId prefix of the query ids of this run
   * @param queryLogWait how long to wait for system.query_log
   */
  public record Options(
      int warmRuns, ColdStrategy cold, Duration timeoutSlack, String runId, Duration queryLogWait) {

    /** Defaults: 3 warm runs, no cache emptying, 30 s slack, random run id, 60 s log wait. */
    public static Options defaults() {
      return new Options(
          3,
          ColdStrategy.NONE,
          Duration.ofSeconds(30),
          "wo03-" + UUID.randomUUID().toString().substring(0, 8),
          Duration.ofSeconds(60));
    }

    /** Copy with another cold strategy. */
    public Options withCold(ColdStrategy value) {
      return new Options(warmRuns, value, timeoutSlack, runId, queryLogWait);
    }

    /** Copy with another number of warm runs. */
    public Options withWarmRuns(int value) {
      return new Options(value, cold, timeoutSlack, runId, queryLogWait);
    }
  }

  /**
   * @param server the server
   * @param schema database and table prefix of the data
   * @param options options
   * @param load machine load sampler (may be null)
   */
  public QueryRunner(Server server, SchemaParameters schema, Options options, MachineLoad load) {
    this.server = server;
    this.schema = schema;
    this.options = options;
    this.load = load;
  }

  /** The server. */
  public Server server() {
    return server;
  }

  /** Schema parameters (database, prefix). */
  public SchemaParameters schema() {
    return schema;
  }

  /** Options. */
  public Options options() {
    return options;
  }

  /** The machine load sampler, or null. */
  public MachineLoad load() {
    return load;
  }

  /**
   * Machine CPU load in [0, 1] while {@code m} ran; waits (at most 2 s) for the sample after its
   * end. NaN without a sampler.
   */
  public double cpuLoad(Measurement m) {
    if (load == null) {
      return Double.NaN;
    }
    load.awaitSampleAfter(m.endNanos(), Duration.ofSeconds(2));
    return m.cpuLoad(load);
  }

  /** Machine CPU load in [0, 1] between two {@link System#nanoTime()} values (see above). */
  public double cpuLoad(long startNanos, long endNanos) {
    if (load == null) {
      return Double.NaN;
    }
    load.awaitSampleAfter(endNanos, Duration.ofSeconds(2));
    return load.mean(startNanos, endNanos);
  }

  /**
   * Parameters of optional probes ({@code @optional}) that found no value in the data, with the
   * reason; the variants that need them are not applicable.
   */
  public Map<String, String> notApplicable() {
    return Map.copyOf(notApplicable);
  }

  /** Every variant result produced so far. */
  public List<VariantResult> results() {
    return List.copyOf(results);
  }

  /** Textual placeholder values: the schema's plus {@code extra} (procedure table names). */
  public Map<String, String> textual(Map<String, String> extra) {
    Map<String, String> values = new LinkedHashMap<>(schema.placeholderValues());
    values.putAll(extra);
    return values;
  }

  /** Parameters every file may use: database and prefix as values (system table filters). */
  public Parameters baseParameters() {
    return Parameters.of(Map.of("database", schema.database(), "prefix", schema.tablePrefix()));
  }

  /**
   * Runs the probes of a file in order and returns {@code base} plus their outputs.
   *
   * @throws IllegalStateException when a probe fails, returns no row or other columns than declared
   */
  public Parameters resolve(QueryFile file, Parameters base) {
    return resolve(file, base, null);
  }

  /**
   * Runs the probes of a file in order; a probe that fails or finds no row is reported in {@code
   * problems} and skipped (its parameters stay unset, the variants that need them are skipped).
   * With {@code problems} null the first failure throws.
   */
  public Parameters resolve(QueryFile file, Parameters base, List<String> problems) {
    Parameters params = base;
    for (Section probe : file.probes()) {
      try {
        params = params.with(probe(file, probe, params));
      } catch (IllegalStateException | Parameters.MissingParameterException e) {
        if (problems == null) {
          throw e;
        }
        problems.add(e.getMessage());
      }
    }
    return params;
  }

  private Map<String, String> probe(QueryFile file, Section probe, Parameters params) {
    String sql =
        SqlTemplate.render(probe.sql(), textual(Map.of())) + "\nFORMAT TabSeparatedWithNames";
    Measurement m =
        execute(
            accountOf(file, probe),
            sql,
            settingsOf(file, probe),
            params,
            file.id() + "/" + probe.name(),
            "probe",
            true);
    if (m.failed()) {
      throw new IllegalStateException(
          "Probe " + file.id() + "/" + probe.name() + " failed: " + m.errorMessage());
    }
    List<List<String>> rows = Tsv.rawRows(m.body());
    if (rows.size() != 2) {
      throw new IllegalStateException(
          "Probe " + file.id() + "/" + probe.name() + " returned " + (rows.size() - 1) + " rows");
    }
    if (!rows.get(0).equals(probe.outputs())) {
      throw new IllegalStateException(
          "Probe "
              + file.id()
              + "/"
              + probe.name()
              + " columns "
              + rows.get(0)
              + " != "
              + probe.outputs());
    }
    Map<String, String> values = new LinkedHashMap<>();
    for (int i = 0; i < probe.outputs().size(); i++) {
      String value = rows.get(1).get(i);
      if (value.equals("\\N") && probe.optional() != null) {
        // The data has no case for this probe (e.g. no transaction of a skewed clock): the
        // variants that need the parameter are not applicable.
        notApplicable.put(probe.outputs().get(i), probe.optional());
        continue;
      }
      if (value.equals("\\N")) {
        // A scalar subquery that found no row: the data does not cover the probe's window.
        throw new IllegalStateException(
            "Probe "
                + file.id()
                + "/"
                + probe.name()
                + " found no value for "
                + probe.outputs().get(i));
      }
      values.put(probe.outputs().get(i), value);
    }
    return values;
  }

  /** Runs every variant of a timing or consistency file. */
  public List<VariantResult> runAll(QueryFile file, Parameters params) {
    List<VariantResult> out = new ArrayList<>();
    for (Section v : file.variants()) {
      out.add(run(file, v, params));
    }
    return out;
  }

  /** Runs one variant (cold + warm runs, or a single run for a consistency check). */
  public VariantResult run(QueryFile file, Section variant, Parameters params) {
    Criterion criterion = variant.criterion() != null ? variant.criterion() : file.criterion();
    String label = file.id() + "/" + variant.name();
    Parameters all = params.with(variant.params());
    String sql = SqlTemplate.render(variant.sql(), textual(Map.of()));
    try {
      all.bind(sql);
    } catch (Parameters.MissingParameterException e) {
      Map<String, String> reasons = new TreeMap<>();
      for (String name : e.names()) {
        String reason = notApplicable.get(name);
        if (reason != null) {
          reasons.put(name, reason);
        }
      }
      VariantResult r =
          reasons.keySet().equals(Set.copyOf(e.names()))
              ? VariantResult.notApplicable(
                  file.id(), variant.name(), file.title(), criterion, reasons.toString())
              : VariantResult.skipped(
                  file.id(), variant.name(), file.title(), criterion, e.getMessage());
      results.add(r);
      return r;
    }
    boolean consistency = file.kind() == Kind.CONSISTENCY;
    List<Measurement> runs = new ArrayList<>();
    Account account = accountOf(file, variant);
    Map<String, String> settings = settingsOf(file, variant);
    if (consistency) {
      runs.add(execute(account, sql, settings, all, label, "once", true));
    } else {
      options.cold().prepare(server);
      runs.add(execute(account, sql, settings, all, label, "cold", false));
      for (int i = 1; i <= options.warmRuns(); i++) {
        runs.add(execute(account, sql, settings, all, label, "warm" + i, i == options.warmRuns()));
      }
    }
    VariantResult r =
        new VariantResult(
            file.id(), variant.name(), file.title(), criterion, variant.expect(), runs, null);
    results.add(r);
    return r;
  }

  /**
   * Runs a step once.
   *
   * @param file the file of the step
   * @param step step name
   * @param params parameters
   * @param extraTextual table names supplied by the procedure
   */
  public Measurement step(
      QueryFile file, String step, Parameters params, Map<String, String> extraTextual) {
    Section s = file.step(step);
    String sql = SqlTemplate.render(s.sql(), textual(extraTextual));
    Measurement m =
        execute(
            accountOf(file, s),
            sql,
            settingsOf(file, s),
            params,
            file.id() + "/" + step,
            "once",
            true);
    stepRuns.put(m.queryId(), m);
    return m;
  }

  /** Runs a step and throws when it fails. */
  public Measurement stepOk(
      QueryFile file, String step, Parameters params, Map<String, String> extraTextual) {
    Measurement m = step(file, step, params, extraTextual);
    if (m.failed()) {
      throw new IllegalStateException(
          m.label() + " failed: " + m.errorName() + " " + m.errorMessage());
    }
    return m;
  }

  /**
   * Runs a statement that is not in a query file (DDL rendered from deploy/clickhouse by a
   * procedure, writer inserts) with an assigned query id; throws when it fails.
   */
  public Measurement statement(
      Account account, String label, String sql, Map<String, String> settings) {
    Measurement m = execute(account, sql, settings, Parameters.empty(), label, "once", true);
    stepRuns.put(m.queryId(), m);
    if (m.failed()) {
      throw new IllegalStateException(label + " failed: " + m.errorName() + " " + m.errorMessage());
    }
    return m;
  }

  /**
   * Executes one statement and measures it. Typed parameters are bound from {@code params}; the
   * statement fails as a measurement (not an exception) when ClickHouse returns an error.
   */
  public Measurement execute(
      Account account,
      String sql,
      Map<String, String> settings,
      Parameters params,
      String label,
      String phase,
      boolean keepBody) {
    String queryId = queryId(label, phase);
    Map<String, String> request = new LinkedHashMap<>(settings);
    request.putAll(params.bind(sql));
    request.put("query_id", queryId);
    ClickHouseHttp client = server.client(account).withRequestTimeout(timeout(settings));
    long start = System.nanoTime();
    String body = null;
    String errorName = null;
    String errorMessage = null;
    try {
      body = client.execute(sql, request).body();
    } catch (ClickHouseException e) {
      errorName = e.errorName().isEmpty() ? "CODE_" + e.code() : e.errorName();
      errorMessage = firstLine(e.getMessage());
    } catch (RuntimeException e) {
      errorName = e.getClass().getSimpleName();
      errorMessage = firstLine(String.valueOf(e.getMessage()));
    }
    long end = System.nanoTime();
    return new Measurement(
        queryId,
        label,
        phase,
        account.user(),
        (end - start) / 1e6,
        body == null ? 0 : Tsv.count(body),
        body == null ? 0 : body.getBytes(StandardCharsets.UTF_8).length,
        errorName,
        errorMessage,
        start,
        end,
        keepBody ? body : null);
  }

  /**
   * Waits for system.query_log and attaches the records to every result; returns all records found
   * (variants and steps) by query id.
   */
  public Map<String, QueryLog.Entry> collectQueryLog() {
    List<String> ids = new ArrayList<>();
    for (VariantResult r : results) {
      for (Measurement m : r.runs()) {
        if (!serverLog.containsKey(m.queryId())) {
          ids.add(m.queryId());
        }
      }
    }
    for (String id : stepRuns.keySet()) {
      if (!serverLog.containsKey(id)) {
        ids.add(id);
      }
    }
    serverLog.putAll(collect(ids));
    for (VariantResult r : results) {
      r.attach(serverLog);
    }
    return Map.copyOf(serverLog);
  }

  /** Collects the query_log records of some query ids. */
  public Map<String, QueryLog.Entry> collect(Collection<String> ids) {
    return QueryLog.collect(server.client(Account.READER), ids, options.queryLogWait());
  }

  /** Every step measurement so far by query id. */
  public Map<String, Measurement> stepRuns() {
    return Map.copyOf(stepRuns);
  }

  private Duration timeout(Map<String, String> settings) {
    long seconds = 10;
    String value = settings.get("max_execution_time");
    if (value != null && value.matches("\\d+")) {
      seconds = Long.parseLong(value);
    }
    return Duration.ofSeconds(seconds).plus(options.timeoutSlack());
  }

  private synchronized String queryId(String label, String phase) {
    sequence++;
    String raw = options.runId() + "-" + label + "-" + phase + "-" + sequence;
    return raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "-");
  }

  private static Account accountOf(QueryFile file, Section section) {
    return section.account() != null ? section.account() : file.account();
  }

  private static Map<String, String> settingsOf(QueryFile file, Section section) {
    Map<String, String> settings = new LinkedHashMap<>(file.settings());
    settings.putAll(section.settings());
    if (accountOf(file, section) == Account.READER) {
      // A statement that the profile does not limit enough must not raise anything: only the
      // limits of the reader profile may be passed, and only lower (users.d/argos.xml).
      String max = settings.get("max_execution_time");
      if (max != null && Long.parseLong(max) > 10) {
        settings.put("max_execution_time", "10");
      }
    }
    return settings;
  }

  private static String firstLine(String message) {
    int nl = message.indexOf('\n');
    return nl < 0 ? message : message.substring(0, nl);
  }
}
