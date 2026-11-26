/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.load;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The parameters of one server load run: the 18 §8 generator parameters ({@code --instances},
 * {@code --tps}, {@code --steps-per-tx}, {@code --profile-rate}, {@code --stats-rows}, {@code
 * --metrics}, {@code --duration}, {@code --late-arrival}, {@code --duplicate}, {@code
 * --clock-skew}, {@code --attack}), their profile defaults ({@link LoadProfile}), and the stack and
 * report settings.
 *
 * <p>{@code --steps-per-tx} is the number of steps of one captured profile. 18 §8's default (15 at
 * a 0.12 capture rate, 9,000 step rows/s at 5,000 TPS) contradicts N-06 (50,000 profile rows/s);
 * until the PM decides (WO-29 PM question 1) the N-06 rate governs: without {@code --steps-per-tx}
 * the steps per profile are derived from {@code --step-rows} (the profile's N-06 proportion) as
 * {@code round(step-rows / (tps x profile-rate))}, 83 at full scale.
 *
 * @param profile the run profile
 * @param machineProfile {@code -Pbench.machine-profile}
 * @param bootJar the server boot jar
 * @param out report directory
 * @param publish where to copy the report ({@code docs/bench/server-load.md}), or null
 * @param instances fake agent instances
 * @param apps apps (name, env) the instances are spread over; env alternates prod/stg
 * @param tps transactions per second in total
 * @param services services per app (Zipf s = 1.1)
 * @param errorRate share of failed transactions
 * @param profileRate share of transactions with a captured profile
 * @param stepsPerTx steps per captured profile (0: derived from {@code stepRowsPerSecond})
 * @param stepRowsPerSecond target profile step rows per second (used when {@code stepsPerTx} is 0)
 * @param statRowsPerSecond statistics rows ({@code SqlStat} + {@code ExtStat}) per second in total
 * @param metricsPerInstance metric series per instance per 5 s
 * @param warmUp warm-up before the measured window
 * @param duration the measured window
 * @param drainTimeout how long the end check waits for the last rows
 * @param lateArrival share of transactions sent late
 * @param lateMin shortest delay of a late transaction
 * @param lateMax longest delay of a late transaction
 * @param duplicate share of transaction batches sent twice (same seq)
 * @param clockSkew the largest skew of an ordinary instance (uniform in plus or minus)
 * @param skewedInstances instances whose clock is far ahead
 * @param skewOutlier the skew of those instances
 * @param attacks the attack scenarios of 18 §8 to run under load (step A2)
 * @param users virtual dashboard users
 * @param userInterval polling interval of each user
 * @param sampleInterval resource sampling interval
 * @param clickHouseMemoryBytes ClickHouse container memory limit
 * @param postgresMemoryBytes PostgreSQL container memory limit
 * @param serverHeap server {@code -Xms}/{@code -Xmx}
 * @param maxDirectMemory server {@code -XX:MaxDirectMemorySize}
 * @param seed random seed of the generator
 */
public record LoadOptions(
    LoadProfile profile,
    String machineProfile,
    Path bootJar,
    Path out,
    Path publish,
    int instances,
    int apps,
    double tps,
    int services,
    double errorRate,
    double profileRate,
    int stepsPerTx,
    double stepRowsPerSecond,
    double statRowsPerSecond,
    int metricsPerInstance,
    Duration warmUp,
    Duration duration,
    Duration drainTimeout,
    double lateArrival,
    Duration lateMin,
    Duration lateMax,
    double duplicate,
    Duration clockSkew,
    int skewedInstances,
    Duration skewOutlier,
    List<String> attacks,
    int users,
    Duration userInterval,
    Duration sampleInterval,
    long clickHouseMemoryBytes,
    long postgresMemoryBytes,
    String serverHeap,
    String maxDirectMemory,
    long seed) {

  /** The attack scenarios of 18 §8. */
  public static final Set<String> ATTACKS =
      Set.of("gzip-bomb", "batch-overflow", "dict-flood", "instance-flood", "unauth-flood");

  /** Every attack, in the order of 18 §8: the default of {@code --attack} ({@code all}). */
  public static final List<String> ALL_ATTACKS =
      List.of("gzip-bomb", "batch-overflow", "dict-flood", "instance-flood", "unauth-flood");

  /** The per-instance statistics quota of a 10 s window (C-157 (3)). */
  public static final int STATS_QUOTA_PER_10S = 6_000;

  /** At most this many {@code ExtStat} rows per {@code StatBatch} (11 §4.4). */
  public static final int MAX_EXT_PER_BATCH = 1_000;

  /** At most this many {@code SqlStat} rows per {@code StatBatch} (11 §4.4). */
  public static final int MAX_SQL_PER_BATCH = 5_000;

  private static final Pattern DURATION = Pattern.compile("^(\\d+(?:\\.\\d+)?)(ms|s|m|h)$");
  private static final Pattern SIZE = Pattern.compile("^(\\d+(?:\\.\\d+)?)([kmg])$");

  /** Validates and copies. */
  public LoadOptions {
    attacks = List.copyOf(attacks);
    if (instances < 1 || apps < 1 || apps > instances) {
      throw new IllegalArgumentException(
          "--instances must be >= 1 and --apps between 1 and --instances");
    }
    if (tps <= 0 || services < 1 || users < 0) {
      throw new IllegalArgumentException("--tps and --services must be positive");
    }
    for (double share : new double[] {errorRate, profileRate, lateArrival, duplicate}) {
      if (share < 0 || share > 1) {
        throw new IllegalArgumentException("shares must be within [0, 1]");
      }
    }
    for (String a : attacks) {
      if (!ATTACKS.contains(a)) {
        throw new IllegalArgumentException("Unknown --attack '" + a + "' " + ATTACKS);
      }
    }
    if (lateMax.compareTo(lateMin) < 0) {
      throw new IllegalArgumentException("--late-max below --late-min");
    }
  }

  /** The defaults of {@code profile}. */
  public static LoadOptions defaults(LoadProfile profile) {
    return new LoadOptions(
        profile,
        "",
        null,
        Path.of("build", "reports", "server-load"),
        null,
        profile.instances(),
        profile.apps(),
        profile.tps(),
        100,
        0.01,
        0.12,
        0,
        profile.stepRowsPerSecond(),
        profile.statRowsPerSecond(),
        100,
        profile.warmUp(),
        profile.duration(),
        profile.drainTimeout(),
        0.005,
        profile.lateMin(),
        profile.lateMax(),
        0.001,
        Duration.ofSeconds(2),
        Math.min(2, profile.instances() / 4),
        Duration.ofSeconds(60),
        ALL_ATTACKS,
        10,
        Duration.ofSeconds(5),
        Duration.ofSeconds(5),
        profile.clickHouseMemoryBytes(),
        profile.postgresMemoryBytes(),
        profile.serverHeap(),
        "512m",
        42);
  }

  /**
   * Parses {@code --key=value} (or {@code --key value}) arguments; {@code --profile} selects the
   * defaults, every other key overrides one of them.
   */
  public static LoadOptions parse(List<String> args) {
    Map<String, String> values = new LinkedHashMap<>();
    for (int i = 0; i < args.size(); i++) {
      String arg = args.get(i);
      if (!arg.startsWith("--")) {
        throw new IllegalArgumentException("Unexpected argument '" + arg + "'");
      }
      String body = arg.substring(2);
      int eq = body.indexOf('=');
      if (eq >= 0) {
        values.put(body.substring(0, eq), body.substring(eq + 1));
      } else if (i + 1 < args.size() && !args.get(i + 1).startsWith("--")) {
        values.put(body, args.get(++i));
      } else {
        values.put(body, "true");
      }
    }
    LoadProfile profile = LoadProfile.of(values.getOrDefault("profile", "smoke"));
    LoadOptions d = defaults(profile);
    Map<String, String> v = new LinkedHashMap<>(values);
    v.remove("profile");
    LoadOptions o =
        new LoadOptions(
            profile,
            take(v, "machine-profile", d.machineProfile()),
            pathOrNull(take(v, "boot-jar", null)),
            Path.of(take(v, "out", d.out().toString())),
            pathOrNull(take(v, "publish", null)),
            Integer.parseInt(take(v, "instances", Integer.toString(d.instances()))),
            Integer.parseInt(take(v, "apps", Integer.toString(d.apps()))),
            Double.parseDouble(take(v, "tps", Double.toString(d.tps()))),
            Integer.parseInt(take(v, "services", Integer.toString(d.services()))),
            Double.parseDouble(take(v, "error-rate", Double.toString(d.errorRate()))),
            Double.parseDouble(take(v, "profile-rate", Double.toString(d.profileRate()))),
            Integer.parseInt(take(v, "steps-per-tx", "0")),
            Double.parseDouble(take(v, "step-rows", Double.toString(d.stepRowsPerSecond()))),
            parseRate(take(v, "stats-rows", Double.toString(d.statRowsPerSecond()))),
            parseMetrics(take(v, "metrics", Integer.toString(d.metricsPerInstance()))),
            duration(take(v, "warm-up", null), d.warmUp()),
            duration(take(v, "duration", null), d.duration()),
            duration(take(v, "drain-timeout", null), d.drainTimeout()),
            share(take(v, "late-arrival", null), d.lateArrival()),
            duration(take(v, "late-min", null), d.lateMin()),
            duration(take(v, "late-max", null), d.lateMax()),
            share(take(v, "duplicate", null), d.duplicate()),
            duration(take(v, "clock-skew", null), d.clockSkew()),
            Integer.parseInt(take(v, "skewed-instances", Integer.toString(d.skewedInstances()))),
            duration(take(v, "skew-outlier", null), d.skewOutlier()),
            attacks(take(v, "attack", "all")),
            Integer.parseInt(take(v, "users", Integer.toString(d.users()))),
            duration(take(v, "user-interval", null), d.userInterval()),
            duration(take(v, "sample-interval", null), d.sampleInterval()),
            size(take(v, "ch-memory", null), d.clickHouseMemoryBytes()),
            size(take(v, "pg-memory", null), d.postgresMemoryBytes()),
            take(v, "server-heap", d.serverHeap()),
            take(v, "max-direct-memory", d.maxDirectMemory()),
            Long.parseLong(take(v, "seed", Long.toString(d.seed()))));
    if (!v.isEmpty()) {
      throw new IllegalArgumentException("Unknown options " + v.keySet());
    }
    return o;
  }

  /** Steps per captured profile: {@code stepsPerTx}, or derived from the step row target. */
  public int stepsPerProfile() {
    if (stepsPerTx > 0) {
      return stepsPerTx;
    }
    double profiles = tps * profileRate;
    return profiles <= 0 ? 0 : (int) Math.max(1, Math.round(stepRowsPerSecond / profiles));
  }

  /** The step rows per second the generator aims at. */
  public double plannedStepRowsPerSecond() {
    return tps * profileRate * stepsPerProfile();
  }

  /** Statistics rows of one instance per 10 s window. */
  public int statRowsPerInstanceWindow() {
    return (int) Math.round(statRowsPerSecond * 10 / instances);
  }

  /** {@code ExtStat} rows of one instance per window: 10 %, at most 1,000. */
  public int extRowsPerWindow() {
    return Math.min(MAX_EXT_PER_BATCH, statRowsPerInstanceWindow() / 10);
  }

  /** {@code SqlStat} rows of one instance per window: the rest, at most 5,000. */
  public int sqlRowsPerWindow() {
    return Math.min(MAX_SQL_PER_BATCH, statRowsPerInstanceWindow() - extRowsPerWindow());
  }

  /** Whether the statistics rows of one instance fit the 6,000 rows / 10 s quota (C-157 (3)). */
  public boolean statsWithinQuota() {
    return sqlRowsPerWindow() + extRowsPerWindow() <= STATS_QUOTA_PER_10S;
  }

  /**
   * Whether this run is N-06 at full scale: profile {@code full} and no override below the N-06
   * rates, duration or instance count.
   */
  public boolean fullScale() {
    LoadProfile f = LoadProfile.FULL;
    return profile == f
        && tps >= f.tps()
        && plannedStepRowsPerSecond() >= f.stepRowsPerSecond() * 0.99
        && statRowsPerSecond >= f.statRowsPerSecond()
        && duration.compareTo(f.duration()) >= 0
        && instances >= f.instances()
        && users >= 10;
  }

  /** The env of app {@code index}: prod for even, stg for odd indexes (18 §8). */
  public static String envOf(int appIndex) {
    return appIndex % 2 == 0 ? "prod" : "stg";
  }

  /** The command line equivalent (for the report). */
  public List<String> describe() {
    List<String> lines = new ArrayList<>();
    lines.add("--instances=" + instances + " (apps " + apps + ", env prod/stg)");
    lines.add(String.format(Locale.ROOT, "--tps=%.0f", tps));
    lines.add(
        String.format(
            Locale.ROOT,
            "--profile-rate=%.2f --steps-per-tx=%d (%s; %.0f step rows/s)",
            profileRate,
            stepsPerProfile(),
            stepsPerTx > 0 ? "given" : "derived from the N-06 step rate",
            plannedStepRowsPerSecond()));
    lines.add(
        String.format(
            Locale.ROOT,
            "--stats-rows=%.0f/s (%d SqlStat + %d ExtStat per instance and 10 s window)",
            statRowsPerSecond,
            sqlRowsPerWindow(),
            extRowsPerWindow()));
    lines.add("--metrics=" + metricsPerInstance + " series per instance and 5 s");
    lines.add("--warm-up=" + warmUp + " --duration=" + duration);
    lines.add(
        String.format(
            Locale.ROOT,
            "--late-arrival=%.3f (%s..%s) --duplicate=%.3f --clock-skew=+-%s (%d instance(s) %s)",
            lateArrival,
            lateMin,
            lateMax,
            duplicate,
            clockSkew,
            skewedInstances,
            skewOutlier));
    lines.add(
        String.format(
            Locale.ROOT,
            "--services=%d (Zipf s=1.1) --error-rate=%.3f, elapsed log-normal (median 80 ms,"
                + " sigma 1.0) + 1%% outliers 2-30 s",
            services,
            errorRate));
    lines.add("--attack=" + (attacks.isEmpty() ? "(none)" : String.join(",", attacks)));
    lines.add("--users=" + users + " every " + userInterval);
    return lines;
  }

  private static String take(Map<String, String> values, String key, String fallback) {
    String v = values.remove(key);
    return v == null ? fallback : v;
  }

  private static Path pathOrNull(String value) {
    return value == null || value.isBlank() ? null : Path.of(value);
  }

  /** {@code 10000} or {@code 10000/s}. */
  static double parseRate(String value) {
    String v = value.strip();
    return Double.parseDouble(v.endsWith("/s") ? v.substring(0, v.length() - 2) : v);
  }

  /** {@code 100} or {@code 100/instance/5s}. */
  static int parseMetrics(String value) {
    String v = value.strip();
    int slash = v.indexOf('/');
    return Integer.parseInt(slash < 0 ? v : v.substring(0, slash));
  }

  /** {@code 0.5%} or {@code 0.005}. */
  static double share(String value, double fallback) {
    if (value == null) {
      return fallback;
    }
    String v = value.strip();
    return v.endsWith("%")
        ? Double.parseDouble(v.substring(0, v.length() - 1)) / 100
        : Double.parseDouble(v);
  }

  /** {@code 500ms}, {@code 90s}, {@code 5m}, {@code 1h}, or an ISO-8601 duration. */
  static Duration duration(String value, Duration fallback) {
    if (value == null) {
      return fallback;
    }
    String v = value.strip().toLowerCase(Locale.ROOT);
    if (v.startsWith("+")) {
      v = v.substring(1);
    }
    Matcher m = DURATION.matcher(v);
    if (!m.matches()) {
      return Duration.parse(value.strip().toUpperCase(Locale.ROOT));
    }
    double amount = Double.parseDouble(m.group(1));
    double millis =
        switch (m.group(2)) {
          case "ms" -> amount;
          case "s" -> amount * 1_000;
          case "m" -> amount * 60_000;
          default -> amount * 3_600_000;
        };
    return Duration.ofMillis(Math.round(millis));
  }

  /** {@code 512m}, {@code 4g}, {@code 7.5g} in bytes. */
  static long size(String value, long fallback) {
    if (value == null) {
      return fallback;
    }
    Matcher m = SIZE.matcher(value.strip().toLowerCase(Locale.ROOT));
    if (!m.matches()) {
      return Long.parseLong(value.strip());
    }
    double amount = Double.parseDouble(m.group(1));
    long unit =
        switch (m.group(2)) {
          case "k" -> 1024L;
          case "m" -> 1024L * 1024;
          default -> 1024L * 1024 * 1024;
        };
    return Math.round(amount * unit);
  }

  private static List<String> attacks(String value) {
    List<String> list = new ArrayList<>();
    if (value.strip().equals("all")) {
      return ALL_ATTACKS;
    }
    for (String a : value.split(",", -1)) {
      if (!a.isBlank() && !a.strip().equals("none")) {
        list.add(a.strip());
      }
    }
    return list;
  }
}
