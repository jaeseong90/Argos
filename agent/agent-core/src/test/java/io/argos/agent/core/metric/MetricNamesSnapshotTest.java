/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.metric;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.proto.v1.MetricBatch;
import io.argos.proto.v1.MetricKind;
import io.argos.proto.v1.MetricSeries;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * T-API-009, agent part (O-R03, 10 §11, 13 §3.3): the agent's metric names are a public contract.
 * Every series family the collector emits (base name, label keys, kind) must be in the snapshot
 * {@code src/test/resources/metrics/metric-names.txt}, and every snapshot line must be emitted by
 * the full scenario ({@link CollectorScenario}) unless listed in {@link #NOT_IN_SCENARIO} with the
 * reason. The snapshot follows the OpenMetrics conventions (snake_case, {@code _total} on counters
 * only, the unit as the last word), and the names the server's dashboard reads ({@code
 * metric_keys.raw.sql}) are among them.
 */
@Tag("req:O-R03")
@Tag("req:T-API-009")
class MetricNamesSnapshotTest {
  /** Snapshot lines the in-process scenario cannot produce, with the reason. */
  static final Map<String, String> NOT_IN_SCENARIO = notInScenario();

  /** Names whose unit is not the last word, defined before WO-16 (10 §11 "에이전트 자기"). */
  static final Set<String> UNIT_EXCEPTIONS =
      new HashSet<String>(
          Arrays.asList(
              "argos_agent_queue_bytes_estimated",
              "argos_agent_heap_bytes_estimated",
              "argos_agent_dictionary_bytes_estimated"));

  static final Set<String> UNITS =
      new HashSet<String>(Arrays.asList("bytes", "seconds", "ratio", "cores", "ms"));

  /** Names of the server's dashboard query that the agent does not send (generator and server). */
  static final Set<String> SERVER_ONLY =
      new HashSet<String>(
          Arrays.asList(
              // The storage generator's names before 10 §11 (process_cpu_usage_ratio and
              // system_cpu_usage_ratio are the agent's).
              "process_cpu_usage",
              "system_cpu_usage",
              // The server's gauge of the active snapshots (ADR-0010 C-178 (1)).
              "argos_active_transactions"));

  private static Map<String, String> notInScenario() {
    Map<String, String> out = new LinkedHashMap<String, String>();
    out.put(
        "gauge argos_agent_clock_offset_ms{}",
        "known only after a HeartbeatAck of a session (TransportCounters; MetricsE2eTest)");
    out.put(
        "counter argos_agent_stats_overflow_total{kind}",
        "a CounterSource of the statistics aggregator (WO-16 lane B, StatsWindowTest)");
    out.put(
        "counter argos_agent_stats_windows_dropped_total{}",
        "a CounterSource of the statistics aggregator, windows cut by the bound of 8 (C-229 (7),"
            + " StatsAggregatorTest)");
    return Collections.unmodifiableMap(out);
  }

  static List<String> snapshot() throws Exception {
    List<String> lines = new ArrayList<String>();
    try (InputStream in =
            MetricNamesSnapshotTest.class.getResourceAsStream("/metrics/metric-names.txt");
        BufferedReader reader =
            new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        line = line.trim();
        if (!line.isEmpty() && !line.startsWith("#")) {
          lines.add(line);
        }
      }
    }
    return lines;
  }

  /** {@code <kind> <base>{<keys>}} of a series. */
  static String family(MetricSeries s) {
    String name = s.getName();
    int brace = name.indexOf('{');
    String base = brace < 0 ? name : name.substring(0, brace);
    List<String> keys = new ArrayList<String>();
    if (brace >= 0) {
      Matcher m = Pattern.compile("([a-z_][a-z0-9_]*)=\"(?:[^\"\\\\]|\\\\.)*\"").matcher(name);
      m.region(brace + 1, name.length() - 1);
      while (m.find()) {
        keys.add(m.group(1));
      }
    }
    String kind = s.getKind() == MetricKind.COUNTER ? "counter" : "gauge";
    return kind + " " + base + "{" + String.join(",", keys) + "}";
  }

  static Set<String> emitted() throws Exception {
    Set<String> families = new TreeSet<String>();
    try (CollectorScenario scenario = new CollectorScenario()) {
      for (int i = 0; i < 2; i++) {
        MetricBatch batch = scenario.collector.collect(5_000L * (i + 1), 1_000_000_000L * (i + 1));
        for (MetricSeries s : batch.getSeriesList()) {
          families.add(family(s));
        }
      }
    }
    return families;
  }

  @Test
  @DisplayName(
      "T-API-009 agent metric names: every emitted family (name, label keys, kind) is in"
          + " metric-names.txt and every snapshot line is emitted by the full scenario")
  void snapshotMatches() throws Exception {
    List<String> snapshot = snapshot();
    assertThat(new LinkedHashSet<String>(snapshot)).as("no duplicate line").hasSameSizeAs(snapshot);
    Set<String> emitted = emitted();
    assertThat(emitted)
        .as("emitted families missing from metric-names.txt (a new name is an API change)")
        .isSubsetOf(snapshot);
    Set<String> expected = new TreeSet<String>(snapshot);
    expected.removeAll(NOT_IN_SCENARIO.keySet());
    assertThat(emitted).as("snapshot lines the scenario no longer emits").containsAll(expected);
    assertThat(snapshot).containsAll(NOT_IN_SCENARIO.keySet());
  }

  @Test
  @DisplayName(
      "T-API-009 OpenMetrics conventions: snake_case names and label keys, _total exactly on"
          + " counters, the unit (bytes, seconds, ratio, cores, ms) as the last word, <= 256 B")
  void conventions() throws Exception {
    Pattern snake = Pattern.compile("[a-z][a-z0-9]*(_[a-z0-9]+)*");
    for (String line : snapshot()) {
      String[] parts = line.split(" ", 2);
      assertThat(parts[0]).as(line).isIn("gauge", "counter");
      String family = parts[1];
      String base = family.substring(0, family.indexOf('{'));
      String keys = family.substring(family.indexOf('{') + 1, family.length() - 1);
      assertThat(base).as(line).matches(snake);
      for (String key : keys.isEmpty() ? new String[0] : keys.split(",", -1)) {
        assertThat(key).as(line).matches(snake);
      }
      assertThat(base.endsWith("_total"))
          .as("%s: _total iff counter", line)
          .isEqualTo(parts[0].equals("counter"));
      String stem = base.endsWith("_total") ? base.substring(0, base.length() - 6) : base;
      List<String> words = Arrays.asList(stem.split("_", -1));
      for (int i = 0; i < words.size() - 1; i++) {
        if (UNITS.contains(words.get(i)) && !UNIT_EXCEPTIONS.contains(base)) {
          assertThat(words.get(i)).as("%s: the unit must be the last word", line).isNull();
        }
      }
      assertThat(base.length()).isLessThan(MetricRegistry.MAX_NAME_BYTES);
    }
  }

  @Test
  @DisplayName(
      "T-API-009 cross-check with the server: every metric name of the dashboard query"
          + " (metric_keys.raw.sql) is an agent family or a documented server-only name")
  void serverDashboardNames() throws Exception {
    Path sql = findUp("server/src/main/resources/sql/dashboard/metric_keys.raw.sql");
    Assumptions.assumeTrue(sql != null, "server sources not found from the working directory");
    String text = new String(Files.readAllBytes(sql), StandardCharsets.UTF_8);
    Set<String> referenced = new TreeSet<String>();
    Matcher starts = Pattern.compile("startsWith\\(name, '([a-z_]+)'\\)").matcher(text);
    while (starts.find()) {
      referenced.add(starts.group(1));
    }
    Matcher in = Pattern.compile("name (?:IN \\(([^)]*)\\)|= '([a-z_]+)')").matcher(text);
    while (in.find()) {
      String list = in.group(1) != null ? in.group(1) : "'" + in.group(2) + "'";
      for (String item : list.split(",", -1)) {
        referenced.add(item.trim().replace("'", ""));
      }
    }
    assertThat(referenced).contains("jvm_gc_pause_seconds_total", "process_cpu_usage_ratio");
    Set<String> bases = new HashSet<String>();
    for (String line : snapshot()) {
      String family = line.split(" ", 2)[1];
      bases.add(family.substring(0, family.indexOf('{')));
    }
    for (String name : referenced) {
      if (!SERVER_ONLY.contains(name)) {
        assertThat(bases).as("server dashboard name %s", name).contains(name);
      }
    }
  }

  private static Path findUp(String relative) {
    Path dir = Paths.get("").toAbsolutePath();
    for (int i = 0; i < 6 && dir != null; i++, dir = dir.getParent()) {
      Path candidate = dir.resolve(relative);
      if (Files.isRegularFile(candidate)) {
        return candidate;
      }
    }
    return null;
  }
}
