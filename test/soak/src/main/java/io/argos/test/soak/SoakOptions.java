/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.soak;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Options of one soak run (18 §11.2), from {@code --key=value} arguments (the {@code soakTest} task
 * passes the Gradle properties and the jars; {@code -Psoak.args} adds or overrides any of them).
 *
 * @param duration measured run time after the warm-up ({@code --duration=72h}, {@code 15m})
 * @param tps load generator rate on shop-boot3 ({@code --tps}, 18 §11.2: 500)
 * @param virtualThreads shop-boot3 with {@code SAMPLE_VT=true} (ADR-0010 C-232 (9))
 * @param machineProfile {@code -Pbench.machine-profile}
 * @param dedicatedProfiles machine profile ids (or prefixes ending in {@code *}) of the dedicated
 *     soak machine (18 §2.3 {@code argos-bench-app}); any other profile is a shared machine
 * @param bootJar the server's executable jar
 * @param agentJar the agent jar
 * @param shopJar {@code shop-boot3.jar}
 * @param batchJar {@code shop-batch.jar}
 * @param loadJar {@code load.jar} (sample-apps/load)
 * @param extStubJar {@code ext-stub.jar}
 * @param out report directory
 * @param publishDir {@code docs/bench} when the report is published, else null
 * @param clickHouseMemoryBytes ClickHouse container limit
 * @param postgresMemoryBytes PostgreSQL container limit
 * @param serverHeap server {@code -Xmx}
 * @param appHeap {@code -Xmx} of shop-boot3 and shop-batch
 * @param mix load generator mix ({@code bench}, 18 §7.2)
 */
public record SoakOptions(
    Duration duration,
    int tps,
    boolean virtualThreads,
    String machineProfile,
    List<String> dedicatedProfiles,
    Path bootJar,
    Path agentJar,
    Path shopJar,
    Path batchJar,
    Path loadJar,
    Path extStubJar,
    Path out,
    Path publishDir,
    long clickHouseMemoryBytes,
    long postgresMemoryBytes,
    String serverHeap,
    String appHeap,
    String mix) {

  /** The duration of 18 §11.2. */
  public static final Duration FULL_DURATION = Duration.ofDays(3);

  /** The load of 18 §11.2. */
  public static final int FULL_TPS = 500;

  /** Runs at least this long size the stack for the dedicated machine (16 GB, 18 §2.3). */
  static final Duration LONG_RUN = Duration.ofHours(24);

  private static final long MIB = 1024L * 1024;
  private static final long GIB = 1024 * MIB;
  private static final Pattern DURATION = Pattern.compile("(\\d+)(ms|s|m|h|d)");
  private static final Pattern SIZE = Pattern.compile("(\\d+)([kmg])", Pattern.CASE_INSENSITIVE);

  /** Validates and copies. */
  public SoakOptions {
    if (duration == null || duration.isNegative() || duration.isZero()) {
      throw new IllegalArgumentException("--duration must be positive");
    }
    if (tps <= 0) {
      throw new IllegalArgumentException("--tps must be positive: " + tps);
    }
    dedicatedProfiles = List.copyOf(dedicatedProfiles);
  }

  /** Parses {@code --key=value} arguments; unknown keys fail. */
  public static SoakOptions parse(String... args) {
    Map<String, String> v = new LinkedHashMap<>();
    for (String arg : args) {
      if (!arg.startsWith("--") || arg.indexOf('=') < 0) {
        throw new IllegalArgumentException("Expected --key=value: " + arg);
      }
      int eq = arg.indexOf('=');
      v.put(arg.substring(2, eq), arg.substring(eq + 1));
    }
    List<String> known =
        List.of(
            "duration",
            "tps",
            "vt",
            "machine-profile",
            "dedicated-profiles",
            "boot-jar",
            "agent-jar",
            "shop-boot3-jar",
            "shop-batch-jar",
            "load-jar",
            "ext-stub-jar",
            "out",
            "publish",
            "ch-memory",
            "pg-memory",
            "server-heap",
            "app-heap",
            "mix");
    for (String key : v.keySet()) {
      if (!known.contains(key)) {
        throw new IllegalArgumentException("Unknown option --" + key + " (known: " + known + ")");
      }
    }
    Duration duration = duration(v.getOrDefault("duration", "72h"));
    boolean longRun = duration.compareTo(LONG_RUN) >= 0;
    List<String> dedicated = new ArrayList<>();
    for (String p : v.getOrDefault("dedicated-profiles", "argos-bench-app*").split(",", -1)) {
      if (!p.isBlank()) {
        dedicated.add(p.strip());
      }
    }
    String publish = v.getOrDefault("publish", "");
    return new SoakOptions(
        duration,
        Integer.parseInt(v.getOrDefault("tps", Integer.toString(FULL_TPS)).strip()),
        Boolean.parseBoolean(v.getOrDefault("vt", "false").strip()),
        v.getOrDefault("machine-profile", "").strip(),
        dedicated,
        path(v.get("boot-jar")),
        path(v.get("agent-jar")),
        path(v.get("shop-boot3-jar")),
        path(v.get("shop-batch-jar")),
        path(v.get("load-jar")),
        path(v.get("ext-stub-jar")),
        Path.of(v.getOrDefault("out", "build/reports/soak")),
        publish.isBlank() ? null : Path.of(publish),
        size(v.getOrDefault("ch-memory", longRun ? "6g" : "3g")),
        size(v.getOrDefault("pg-memory", "1g")),
        v.getOrDefault("server-heap", longRun ? "2g" : "1g"),
        v.getOrDefault("app-heap", longRun ? "1g" : "512m"),
        v.getOrDefault("mix", "bench"));
  }

  /** Whether {@link #machineProfile} names the dedicated soak machine. */
  public boolean dedicatedMachine() {
    String id = machineProfile == null ? "" : machineProfile;
    if (id.isEmpty()) {
      return false;
    }
    for (String p : dedicatedProfiles) {
      if (p.endsWith("*") ? id.startsWith(p.substring(0, p.length() - 1)) : id.equals(p)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Why the time and resource verdicts of this run are INVALID (18 §11.2 needs 72 hours at 500 TPS
   * on the dedicated machine), or empty for a valid run.
   */
  public Optional<String> invalidReason() {
    List<String> reasons = new ArrayList<>();
    if (duration.compareTo(FULL_DURATION) < 0) {
      reasons.add("duration " + format(duration) + " < 72h");
    }
    if (tps < FULL_TPS) {
      reasons.add("load " + tps + " TPS < " + FULL_TPS + " TPS");
    }
    if (!dedicatedMachine()) {
      reasons.add(
          "machine profile '"
              + machineProfile
              + "' is not a dedicated soak machine ("
              + String.join(", ", dedicatedProfiles)
              + ")");
    }
    return reasons.isEmpty() ? Optional.empty() : Optional.of(String.join("; ", reasons));
  }

  /** Parses {@code 72h}, {@code 15m}, {@code 90s}, {@code 3d}, {@code 500ms}. */
  public static Duration duration(String text) {
    Matcher m = DURATION.matcher(text.strip().toLowerCase(Locale.ROOT));
    if (!m.matches()) {
      throw new IllegalArgumentException("Not a duration (72h, 15m, 90s, 3d): " + text);
    }
    long n = Long.parseLong(m.group(1));
    return switch (m.group(2)) {
      case "ms" -> Duration.ofMillis(n);
      case "s" -> Duration.ofSeconds(n);
      case "m" -> Duration.ofMinutes(n);
      case "h" -> Duration.ofHours(n);
      default -> Duration.ofDays(n);
    };
  }

  /** Parses {@code 512m}, {@code 3g}, {@code 1024k} into bytes. */
  public static long size(String text) {
    Matcher m = SIZE.matcher(text.strip());
    if (!m.matches()) {
      throw new IllegalArgumentException("Not a size (512m, 3g): " + text);
    }
    long n = Long.parseLong(m.group(1));
    return switch (m.group(2).toLowerCase(Locale.ROOT)) {
      case "k" -> n * 1024;
      case "m" -> n * MIB;
      default -> n * GIB;
    };
  }

  /** {@code 72h}, {@code 15m}, {@code 1h30m}, {@code 45s}. */
  public static String format(Duration d) {
    long s = d.toSeconds();
    if (s == 0) {
      return d.toMillis() + "ms";
    }
    StringBuilder b = new StringBuilder();
    if (s >= 3600) {
      b.append(s / 3600).append('h');
      s %= 3600;
    }
    if (s >= 60) {
      b.append(s / 60).append('m');
      s %= 60;
    }
    if (s > 0 || b.length() == 0) {
      b.append(s).append('s');
    }
    return b.toString();
  }

  private static Path path(String value) {
    return value == null || value.isBlank() ? null : Path.of(value);
  }
}
