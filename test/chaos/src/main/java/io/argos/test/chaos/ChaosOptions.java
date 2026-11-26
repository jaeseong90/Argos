/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.chaos;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The settings of one chaos run, from the system properties the {@code chaosTest} task sets
 * (build.gradle.kts): {@code argos.chaos.profile}, {@code .scenarios}, {@code .rate}, {@code
 * .machineProfile}, {@code .out}, {@code .publishDir}, {@code .netemImage}, {@code .bootJar},
 * {@code .agentJar}, {@code .loadJar} and {@code argos.repositoryRoot}.
 *
 * @param profile {@code short} or {@code full}
 * @param scenarios the scenario IDs to run; empty runs every scenario
 * @param rate requests per second of the load generator
 * @param machineProfile {@code -Pbench.machine-profile}
 * @param out report directory ({@code build/reports/chaos})
 * @param publishDir {@code docs/bench} when the report is published, else null
 * @param netemImage an image with {@code tc} (iproute2) pinned by digest for the netem variant of
 *     T-CH-003, or null
 * @param bootJar the server's executable jar
 * @param agentJar the agent jar
 * @param loadJar the demo load generator jar
 * @param repositoryRoot the repository root (commit id of the report)
 */
public record ChaosOptions(
    ChaosProfile profile,
    Set<String> scenarios,
    int rate,
    String machineProfile,
    Path out,
    Path publishDir,
    String netemImage,
    Path bootJar,
    Path agentJar,
    Path loadJar,
    Path repositoryRoot) {

  /** A scenario ID: {@code T-CH-} and three digits. */
  static final Pattern SCENARIO_ID = Pattern.compile("T-CH-\\d{3}");

  /** An image reference pinned by digest. */
  static final Pattern PINNED_IMAGE = Pattern.compile("\\S+@sha256:[0-9a-f]{64}");

  /** Separators of {@code -Pchaos.scenarios}. */
  static final Pattern SEPARATORS = Pattern.compile("[,\\s]+");

  /** Copies and validates. */
  public ChaosOptions {
    scenarios = Set.copyOf(scenarios);
    if (rate < 1 || rate > 5_000) {
      throw new IllegalArgumentException("chaos.rate must be 1..5000, got " + rate);
    }
    if (netemImage != null && !PINNED_IMAGE.matcher(netemImage).matches()) {
      throw new IllegalArgumentException(
          "chaos.netem.image must be pinned by digest (<image>@sha256:<64 hex>), got '"
              + netemImage
              + "'");
    }
  }

  /** The options of the {@code chaosTest} task from {@code properties} (system properties). */
  public static ChaosOptions fromProperties(Map<String, String> properties) {
    ChaosProfile profile = ChaosProfile.parse(properties.get("argos.chaos.profile"));
    String rate = blankToNull(properties.get("argos.chaos.rate"));
    return new ChaosOptions(
        profile,
        scenarios(properties.get("argos.chaos.scenarios")),
        rate == null ? profile.defaultRate() : parseRate(rate),
        blankToEmpty(properties.get("argos.chaos.machineProfile")),
        path(properties, "argos.chaos.out", "build/reports/chaos"),
        optionalPath(properties.get("argos.chaos.publishDir")),
        blankToNull(properties.get("argos.chaos.netemImage")),
        optionalPath(properties.get("argos.chaos.bootJar")),
        optionalPath(properties.get("argos.chaos.agentJar")),
        optionalPath(properties.get("argos.chaos.loadJar")),
        path(properties, "argos.repositoryRoot", "."));
  }

  /** The options of the current JVM's system properties. */
  public static ChaosOptions fromSystemProperties() {
    Map<String, String> map = new java.util.HashMap<>();
    System.getProperties().forEach((k, v) -> map.put(String.valueOf(k), String.valueOf(v)));
    return fromProperties(map);
  }

  /**
   * {@code T-CH-001,T-CH-009} (commas or blanks, case-insensitive) as a set; blank is every
   * scenario.
   */
  static Set<String> scenarios(String value) {
    Set<String> ids = new LinkedHashSet<>();
    if (value == null || value.isBlank()) {
      return ids;
    }
    for (String item : SEPARATORS.splitAsStream(value.strip()).toList()) {
      if (item.isBlank()) {
        continue;
      }
      String id = item.strip().toUpperCase(Locale.ROOT);
      if (!SCENARIO_ID.matcher(id).matches()) {
        throw new IllegalArgumentException(
            "chaos.scenarios: expected IDs like T-CH-001, got '" + item + "'");
      }
      ids.add(id);
    }
    return ids;
  }

  /** Whether the run includes scenario {@code id}. */
  public boolean selected(String id) {
    return scenarios.isEmpty() || scenarios.contains(id);
  }

  /** Whether the timing and p99 verdicts may be PASS or FAIL ({@link Judge#valid}). */
  public boolean valid() {
    return Judge.valid(profile, machineProfile);
  }

  private static int parseRate(String value) {
    try {
      return Integer.parseInt(value.strip());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("chaos.rate must be a number, got '" + value + "'", e);
    }
  }

  private static Path path(Map<String, String> properties, String key, String fallback) {
    String value = blankToNull(properties.get(key));
    return Path.of(value == null ? fallback : value).toAbsolutePath();
  }

  private static Path optionalPath(String value) {
    String v = blankToNull(value);
    return v == null ? null : Path.of(v).toAbsolutePath();
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }

  private static String blankToEmpty(String value) {
    return value == null ? "" : value.strip();
  }
}
