/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.generator;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parameters of the storage PoC generator. Defaults are the distributions and full scale of docs/13
 * §15 (and 18 §9); {@code scale} shrinks the time span of every table, never the rates or
 * cardinalities, so that a 1/100 data set has the same per-second, per-window and per-row shape as
 * the full one (short-range queries read the same amount of data as at full scale).
 *
 * <p>Every value is kept as text under its command line key ({@code --key=value}); the typed fields
 * are parsed once. {@link #with(String, String)} returns a copy with one value changed.
 */
public final class GeneratorConfig {

  /** One UTC day in milliseconds. */
  public static final long DAY_MS = 86_400_000L;

  /** Bulk (date-ordered large inserts) or replay (writer path at target TPS). */
  public enum Mode {
    BULK,
    REPLAY
  }

  /**
   * Which agent-clock instant decides the 10 s statistics window of an SQL execution or outgoing
   * call. {@code END} is the agent behaviour of docs/10 §9.2-§9.3 (recorded at step end, wall clock
   * window); {@code START} uses the transaction start, which makes per-range D-12 sums exact.
   */
  public enum StatWindow {
    END,
    START
  }

  /** Presets: name to scale. */
  public static final Map<String, Double> PROFILES =
      Map.of("tiny", 0.001, "ci", 0.01, "local", 0.05, "full", 1.0);

  private static final Map<String, String[]> KEYS = keys();

  private final Map<String, String> values;

  // --- typed values ----------------------------------------------------------------------------
  public final long seed;
  public final double scale;
  public final Mode mode;
  public final Instant start; // null: automatic
  public final double days;
  public final double profileDays;
  public final double rawDays;
  public final double tps;
  public final int apps;
  public final int prodApps;
  public final int instancesPerApp;
  public final int services;
  public final double serviceZipf;
  public final int sqls;
  public final double sqlZipf;
  public final int extHosts;
  public final int versions;
  public final double versionSwitchDay;
  public final double batchShare;
  public final double errorRate;
  public final int errorGroups;
  public final double latencyMedianMs;
  public final double latencySigma;
  public final double outlierRate;
  public final int outlierMinMs;
  public final int outlierMaxMs;
  public final double userIdShare;
  public final int users;
  public final int remoteIps;
  public final double requestIdShare;
  public final double sqlIdsMean;
  public final double extCallRate;
  public final double internalCallRate;
  public final long clockSkewMs;
  public final int skewedInstances;
  public final long skewedOffsetMs;
  public final double lateRate;
  public final int lateMinMinutes;
  public final int lateMaxMinutes;
  public final double duplicateRate;
  public final boolean forceDuplicate;
  public final int metricSeries;
  public final double profileNormalRate;
  public final double profileStepRate; // 0: capture by profileNormalRate only
  public final int activeSqls; // 0: the service's usual statements (13 §15 shape)
  public final int slowThresholdMs;
  public final StatWindow statWindow;
  public final long tickMs;
  public final int blockRows;
  public final List<Double> tpsSteps;
  public final Duration stepDuration;
  public final long flushIntervalMs;
  public final int flushRows;
  public final int writerRestarts;
  public final String runId; // "" = derived
  public final String target;
  public final String database;
  public final String tablePrefix;
  public final String applySchema;
  public final String dockerMemory;
  public final String dockerVolume;
  public final String out;
  public final String machineProfile;
  public final int monitorIntervalSeconds;

  private GeneratorConfig(Map<String, String> values) {
    this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    seed = parseLong("seed");
    scale = scaleValue(values);
    mode = Mode.valueOf(text("mode").toUpperCase(Locale.ROOT));
    String startText = text("start");
    start = startText.isEmpty() ? null : Instant.parse(startText);
    days = positive("days");
    profileDays = positive("profile-days");
    rawDays = positive("raw-days");
    tps = positive("tps");
    apps = intAtLeast("apps", 1);
    prodApps = intAtLeast("prod-apps", 0);
    instancesPerApp = intAtLeast("instances-per-app", 1);
    services = intAtLeast("services", 1);
    serviceZipf = nonNegative("service-zipf");
    sqls = intAtLeast("sqls", 1);
    sqlZipf = nonNegative("sql-zipf");
    extHosts = intAtLeast("ext-hosts", 1);
    versions = intAtLeast("versions", 1);
    versionSwitchDay = positive("version-switch-day");
    batchShare = fraction("batch-share");
    errorRate = fraction("error-rate");
    errorGroups = intAtLeast("error-groups", 1);
    latencyMedianMs = positive("latency-median-ms");
    latencySigma = nonNegative("latency-sigma");
    outlierRate = fraction("outlier-rate");
    outlierMinMs = intAtLeast("outlier-min-ms", 1);
    outlierMaxMs = intAtLeast("outlier-max-ms", outlierMinMs);
    userIdShare = fraction("user-id-share");
    users = intAtLeast("users", 1);
    remoteIps = intAtLeast("remote-ips", 1);
    requestIdShare = fraction("request-id-share");
    sqlIdsMean = nonNegative("sql-ids-mean");
    extCallRate = fraction("ext-call-rate");
    internalCallRate = fraction("internal-call-rate");
    clockSkewMs = parseLong("clock-skew-ms");
    skewedInstances = intAtLeast("skewed-instances", 0);
    skewedOffsetMs = parseLong("skewed-offset-ms");
    lateRate = fraction("late-rate");
    lateMinMinutes = intAtLeast("late-min-minutes", 1);
    lateMaxMinutes = intAtLeast("late-max-minutes", lateMinMinutes);
    duplicateRate = fraction("duplicate-rate");
    forceDuplicate = Boolean.parseBoolean(text("force-duplicate"));
    metricSeries = intAtLeast("metric-series", 0);
    profileNormalRate = fraction("profile-normal-rate");
    profileStepRate = nonNegative("profile-step-rate");
    activeSqls = intAtLeast("active-sqls", 0);
    slowThresholdMs = intAtLeast("slow-threshold-ms", 1);
    statWindow = StatWindow.valueOf(text("stat-window").toUpperCase(Locale.ROOT));
    tickMs = parseLong("tick-ms");
    blockRows = intAtLeast("block-rows", 1);
    tpsSteps = doubles("tps-steps");
    stepDuration = Duration.parse(text("step-duration"));
    flushIntervalMs = parseLong("flush-interval-ms");
    flushRows = intAtLeast("flush-rows", 1);
    writerRestarts = intAtLeast("writer-restarts", 0);
    runId = text("run-id");
    target = text("target");
    database = text("database");
    tablePrefix = text("table-prefix");
    applySchema = text("apply-schema");
    dockerMemory = text("docker-memory");
    dockerVolume = text("docker-volume");
    out = text("out");
    machineProfile = text("machine-profile");
    monitorIntervalSeconds = intAtLeast("monitor-interval-s", 1);
    if (prodApps > apps) {
      throw new IllegalArgumentException("prod-apps > apps");
    }
    if (lateMaxMinutes > 60) {
      throw new IllegalArgumentException("late-max-minutes above 60 is not supported");
    }
    if (skewedInstances > apps * instancesPerApp) {
      throw new IllegalArgumentException("skewed-instances > instances");
    }
    if (stepDuration.isNegative() || stepDuration.isZero()) {
      throw new IllegalArgumentException("step-duration must be positive");
    }
    if (flushIntervalMs < 1) {
      throw new IllegalArgumentException("flush-interval-ms must be positive");
    }
    if (tickMs < 10 || tickMs > 1000 || 1000 % tickMs != 0) {
      throw new IllegalArgumentException("tick-ms must divide 1000 and be at least 10: " + tickMs);
    }
    if ((long) activeSqls * apps > sqls) {
      // The active set of an instance is drawn from the statements of its application (sqls /
      // apps); a larger active-sqls would be cut silently and miss the intended load (C-62).
      throw new IllegalArgumentException(
          "active-sqls "
              + activeSqls
              + " exceeds the SQL statements per application (sqls "
              + sqls
              + " / apps "
              + apps
              + "): raise --sqls to at least active-sqls x apps");
    }
  }

  /** The defaults of docs/13 §15. */
  public static GeneratorConfig defaults() {
    Map<String, String> values = new LinkedHashMap<>();
    KEYS.forEach((k, v) -> values.put(k, v[0]));
    return new GeneratorConfig(values);
  }

  /**
   * Parses {@code --key=value} arguments over the defaults. The values are validated together, so
   * the order of the arguments does not matter (e.g. {@code --active-sqls} before {@code --sqls}).
   */
  public static GeneratorConfig parse(List<String> args) {
    Map<String, String> values = new LinkedHashMap<>(defaults().values);
    for (String arg : args) {
      if (!arg.startsWith("--") || arg.indexOf('=') < 3) {
        throw new IllegalArgumentException("Expected --key=value, got: " + arg);
      }
      int eq = arg.indexOf('=');
      String key = arg.substring(2, eq);
      String value = arg.substring(eq + 1);
      if (!KEYS.containsKey(key)) {
        throw new IllegalArgumentException("Unknown parameter: --" + key + "\n" + usage());
      }
      if (key.equals("profile") && !value.isEmpty() && !PROFILES.containsKey(value)) {
        throw new IllegalArgumentException("Unknown profile " + value + ", one of " + PROFILES);
      }
      values.put(key, value);
    }
    return new GeneratorConfig(values);
  }

  /** A copy with {@code key} set to {@code value}. */
  public GeneratorConfig with(String key, String value) {
    if (!KEYS.containsKey(key)) {
      throw new IllegalArgumentException("Unknown parameter: --" + key + "\n" + usage());
    }
    Map<String, String> copy = new LinkedHashMap<>(values);
    copy.put(key, value);
    if (key.equals("profile") && !value.isEmpty() && !PROFILES.containsKey(value)) {
      throw new IllegalArgumentException("Unknown profile " + value + ", one of " + PROFILES);
    }
    return new GeneratorConfig(copy);
  }

  /** All values by key (for the manifest). */
  public Map<String, String> values() {
    return values;
  }

  /** Text value of a key. */
  public String text(String key) {
    String value = values.get(key);
    if (value == null) {
      throw new IllegalArgumentException("Unknown parameter: " + key);
    }
    return value;
  }

  /** Time span of xlog (and of the statistics derived from transactions), in ms. */
  public long xlogSpanMs() {
    return Math.max(60_000L, roundToSecond(days * scale * DAY_MS));
  }

  /** Time span of profile_step at the end of the xlog span, in ms. */
  public long profileSpanMs() {
    return Math.min(xlogSpanMs(), roundToSecond(profileDays * scale * DAY_MS));
  }

  /** Time span of metric at the end of the xlog span, in ms. */
  public long metricSpanMs() {
    return Math.min(xlogSpanMs(), roundToSecond(rawDays * scale * DAY_MS));
  }

  /** Number of instances. */
  public int instances() {
    return apps * instancesPerApp;
  }

  /** Usage text with every key and its default. */
  public static String usage() {
    StringBuilder sb = new StringBuilder("Parameters (--key=value):\n");
    KEYS.forEach(
        (k, v) ->
            sb.append(
                String.format(Locale.ROOT, "  --%-22s %-14s %s%n", k, "[" + v[0] + "]", v[1])));
    return sb.toString();
  }

  private static long roundToSecond(double ms) {
    return Math.round(ms / 1000.0) * 1000L;
  }

  private static double scaleValue(Map<String, String> values) {
    String profile = values.get("profile");
    String scaleText = values.get("scale");
    double scale = Double.parseDouble(scaleText);
    if (!profile.isEmpty() && scaleText.equals(KEYS.get("scale")[0])) {
      Double preset = PROFILES.get(profile);
      if (preset == null) {
        throw new IllegalArgumentException("Unknown profile " + profile);
      }
      scale = preset;
    }
    if (!(scale > 0 && scale <= 1)) {
      throw new IllegalArgumentException("scale must be in (0, 1]: " + scale);
    }
    return scale;
  }

  private long parseLong(String key) {
    return Long.parseLong(text(key));
  }

  private double positive(String key) {
    double v = Double.parseDouble(text(key));
    if (!(v > 0) || Double.isInfinite(v)) {
      throw new IllegalArgumentException(key + " must be positive: " + v);
    }
    return v;
  }

  private double nonNegative(String key) {
    double v = Double.parseDouble(text(key));
    if (!(v >= 0) || Double.isInfinite(v)) {
      throw new IllegalArgumentException(key + " must be non-negative: " + v);
    }
    return v;
  }

  private double fraction(String key) {
    double v = Double.parseDouble(text(key));
    if (!(v >= 0 && v <= 1)) {
      throw new IllegalArgumentException(key + " must be in [0, 1]: " + v);
    }
    return v;
  }

  private int intAtLeast(String key, int min) {
    int v = Integer.parseInt(text(key));
    if (v < min) {
      throw new IllegalArgumentException(key + " must be at least " + min + ": " + v);
    }
    return v;
  }

  private List<Double> doubles(String key) {
    List<Double> list = new ArrayList<>();
    for (String part : splitComma(text(key))) {
      double v = Double.parseDouble(part.trim());
      if (!(v > 0)) {
        throw new IllegalArgumentException(key + " values must be positive");
      }
      list.add(v);
    }
    return List.copyOf(list);
  }

  /** Splits on commas, keeping empty parts. */
  static List<String> splitComma(String text) {
    List<String> parts = new ArrayList<>();
    int from = 0;
    while (true) {
      int comma = text.indexOf(',', from);
      if (comma < 0) {
        parts.add(text.substring(from));
        return parts;
      }
      parts.add(text.substring(from, comma));
      from = comma + 1;
    }
  }

  private static Map<String, String[]> keys() {
    Map<String, String[]> k = new LinkedHashMap<>();
    put(k, "seed", "42", "random seed (same seed and start: same data)");
    put(k, "profile", "", "preset scale: tiny 0.001, ci 0.01, local 0.05, full 1");
    put(k, "scale", "1", "fraction of the full time span (rates and cardinalities unchanged)");
    put(k, "mode", "bulk", "bulk (date-ordered large inserts) or replay (writer path)");
    put(k, "start", "", "first instant (ISO-8601 UTC); default: span ending before now");
    put(k, "days", "30", "xlog days at scale 1");
    put(k, "profile-days", "7", "profile_step days at scale 1 (end of the span)");
    put(k, "raw-days", "14", "metric days at scale 1 (end of the span)");
    put(k, "tps", "500", "transactions per second (bulk)");
    put(k, "apps", "10", "applications");
    put(k, "prod-apps", "7", "applications with env prod (the others: stg)");
    put(k, "instances-per-app", "5", "instances per application");
    put(k, "services", "100", "HTTP services, global Zipf ranks spread over the apps");
    put(k, "service-zipf", "1.1", "Zipf exponent of service popularity");
    put(k, "sqls", "2000", "SQL statements, evenly split over the apps");
    put(k, "sql-zipf", "1.0", "Zipf exponent of SQL popularity within an app");
    put(k, "ext-hosts", "20", "outgoing call hosts: one internal host per app + external hosts");
    put(k, "versions", "3", "application versions (switch on version-switch-day)");
    put(k, "version-switch-day", "15", "day of the version switch (1-based, scaled)");
    put(k, "batch-share", "0.03", "share of BATCH transactions");
    put(k, "error-rate", "0.01", "share of failed transactions");
    put(k, "error-groups", "50", "error groups, evenly split over the apps");
    put(k, "latency-median-ms", "80", "log-normal median of the response time");
    put(k, "latency-sigma", "1.0", "log-normal sigma of the response time");
    put(k, "outlier-rate", "0.01", "share of outliers");
    put(k, "outlier-min-ms", "2000", "outlier lower bound");
    put(k, "outlier-max-ms", "30000", "outlier upper bound");
    put(k, "user-id-share", "0.3", "share of transactions with a user id (hashed mode)");
    put(k, "users", "200000", "user population (Zipf 1.0)");
    put(k, "remote-ips", "10000", "distinct masked client addresses (Zipf 1.0)");
    put(k, "request-id-share", "0.5", "share of transactions with a request id");
    put(k, "sql-ids-mean", "3", "mean number of distinct SQL per transaction (sql_ids)");
    put(k, "ext-call-rate", "0.2", "share of HTTP transactions calling external hosts");
    put(k, "internal-call-rate", "0.08", "share of HTTP root transactions calling another app");
    put(k, "clock-skew-ms", "2000", "instance clock skew bound (+/-)");
    put(k, "skewed-instances", "2", "instances with a large positive clock skew");
    put(k, "skewed-offset-ms", "60000", "clock skew of those instances");
    put(k, "late-rate", "0.005", "share of agent batches that arrive late");
    put(k, "late-min-minutes", "1", "late arrival delay lower bound");
    put(k, "late-max-minutes", "30", "late arrival delay upper bound");
    put(k, "duplicate-rate", "0.001", "share of inserted blocks re-sent with the same token");
    put(k, "force-duplicate", "true", "re-send the first block of every table once");
    put(k, "metric-series", "100", "metric series per instance (5 s grid)");
    put(k, "profile-normal-rate", "0.1", "profile capture rate of normal transactions");
    put(
        k,
        "profile-step-rate",
        "0",
        "N-06 load: target profile_step rows/s at the highest TPS (lower TPS proportional);"
            + " adapts the normal capture rate, 0 = off");
    put(
        k,
        "active-sqls",
        "0",
        "N-06 load: active SQL per instance (13 §14: 200), drawn uniformly; 0 = off");
    put(k, "slow-threshold-ms", "1000", "profile capture threshold and STACK capture start");
    put(k, "stat-window", "end", "statistics window: end (agent) or start (exact per range)");
    put(k, "tick-ms", "1000", "simulation tick: rows are released at the end of their tick");
    put(k, "block-rows", "500000", "rows per insert in bulk mode");
    put(k, "tps-steps", "500,2000,5000", "replay: target TPS steps");
    put(k, "step-duration", "PT1H", "replay: duration of each TPS step (ISO-8601)");
    put(k, "flush-interval-ms", "2000", "replay: writer flush interval (12 §3.8)");
    put(k, "flush-rows", "50000", "replay: writer flush row count (12 §3.8)");
    put(k, "writer-restarts", "0", "replay: simulated writer crashes between insert and COMMIT");
    put(k, "run-id", "", "token namespace of this run (default: derived from seed and start)");
    put(k, "target", "none", "none | file:<dir> | docker | url:<http://host:8123/>");
    put(k, "database", "argos", "ClickHouse database ({db})");
    put(k, "table-prefix", "", "table prefix ({p})");
    put(k, "apply-schema", "auto", "apply V001 as argos_migrator: auto (docker only), true, false");
    put(k, "docker-memory", "5g", "docker target: container memory limit");
    put(k, "docker-volume", "", "docker target: named data volume (kept after the run)");
    put(k, "out", "build/storage-generator", "report directory (manifest, report, samples)");
    put(k, "machine-profile", "", "machine profile id recorded in the report (storageBench)");
    put(k, "monitor-interval-s", "10", "sampling interval of host load and ClickHouse state");
    return Collections.unmodifiableMap(k);
  }

  private static void put(Map<String, String[]> map, String key, String def, String help) {
    map.put(key, new String[] {def, help});
  }
}
