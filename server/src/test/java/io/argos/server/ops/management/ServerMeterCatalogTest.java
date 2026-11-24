/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.management;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.ops.management.SelfMeterCatalog.Entry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The server meter list (T-SV-047 unit part; 12 §9.4, F-S20): the catalog has exactly the names of
 * 12 §9.4 in its order, every implemented name follows the naming rule and becomes the documented
 * Prometheus name, every intended difference names the meter that carries it and the decision, and
 * the sources register every catalogued Micrometer name.
 */
@Tag("req:F-S20")
class ServerMeterCatalogTest {

  private static final Path ROOT = Path.of(System.getProperty("argos.repositoryRoot", ".."));

  /**
   * Catalogued names of WO-28 lanes that are not registered yet in this tree (the lane registers
   * them in its own step). The test fails as soon as one is registered, so the list only shrinks;
   * it must be empty when WO-28 is integrated.
   */
  private static final Set<String> PENDING = Set.of();

  /** Files that only name meters (the catalog, the recorder's allowlist, alert lookups). */
  private static final Set<String> NOT_REGISTERING =
      Set.of(
          "SelfMeterCatalog.java",
          "SelfMetricsRecorder.java",
          "SelfProvider.java",
          "DefaultTemplates.java",
          "AlertViews.java",
          "ServerMeters.java");

  @Test
  @DisplayName("T-SV-047 the catalog lists exactly the meter names of 12 §9.4, in its order")
  void catalogIsTwelveNineFour() throws IOException {
    List<String> documented = section94Names();
    assertThat(documented).as("12 §9.4 parsed").hasSizeGreaterThan(40);
    assertThat(SelfMeterCatalog.DOCUMENTED.stream().map(Entry::prometheus).toList())
        .containsExactlyElementsOf(documented);
  }

  @Test
  @DisplayName(
      "T-SV-047 every implemented name follows the naming rule (02 §7) and is exposed as the"
          + " documented Prometheus name; every mapping names its meter and decision")
  void namesAndMappings() {
    for (Entry e : all()) {
      if (e.mapped()) {
        assertThat(e.mappedTo()).as(e.prometheus()).isNotBlank();
        assertThat(e.note()).as(e.prometheus()).isNotBlank();
        continue;
      }
      Meter.Id id = new Meter.Id(e.micrometer(), Tags.empty(), null, null, e.type());
      assertThat(MeterNames.violation(id)).as(e.micrometer()).isEmpty();
      assertThat(SelfMeterCatalog.prometheusName(e.micrometer(), e.type()))
          .as(e.micrometer())
          .isEqualTo(e.prometheus());
    }
    assertThat(SelfMeterCatalog.DOCUMENTED.stream().filter(Entry::mapped).map(Entry::prometheus))
        .containsExactlyInAnyOrder(
            "argos_commands_total", "argos_config_changes_total", "argos_auth_failures_total");
  }

  @Test
  @DisplayName(
      "T-SV-047 the conversion equals the Prometheus registry's exposition (counter _total, timer"
          + " _seconds, gauge as is)")
  void conversionMatchesRegistry() {
    PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    Counter.builder("argos.collector.dropped").tag("reason", "quota").register(registry);
    Gauge.builder("argos.sessions.active", () -> 1).register(registry);
    Gauge.builder(MeterNames.TLS_CERT_EXPIRY_SECONDS, () -> 2)
        .tag("listener", "grpc")
        .register(registry);
    Timer.builder("argos.ingest.lag").register(registry).record(Duration.ofMillis(5));
    String scrape = registry.scrape();
    for (String name :
        List.of(
            SelfMeterCatalog.prometheusName("argos.collector.dropped", Meter.Type.COUNTER)
                + "{reason=\"quota\"}",
            SelfMeterCatalog.prometheusName("argos.sessions.active", Meter.Type.GAUGE),
            SelfMeterCatalog.prometheusName(MeterNames.TLS_CERT_EXPIRY_SECONDS, Meter.Type.GAUGE)
                + "{listener=\"grpc\"}",
            SelfMeterCatalog.prometheusName("argos.ingest.lag", Meter.Type.TIMER) + "_count")) {
      assertThat(scrape).as(name).containsPattern("(?m)^" + Pattern.quote(name) + " ");
    }
  }

  @Test
  @DisplayName(
      "T-SV-047 every catalogued Micrometer name is registered by the server sources (pending WO-28"
          + " lane names are listed and must not be registered yet)")
  void sourcesRegisterEveryName() throws IOException {
    String sources = mainSources();
    Set<String> missing = new TreeSet<>();
    Set<String> nowRegistered = new TreeSet<>();
    for (Entry e : all()) {
      if (e.mapped()) {
        continue;
      }
      boolean found = registered(sources, e.micrometer());
      if (PENDING.contains(e.micrometer())) {
        if (found) {
          nowRegistered.add(e.micrometer());
        }
      } else if (!found) {
        missing.add(e.micrometer());
      }
    }
    assertThat(missing).as("catalogued but not registered").isEmpty();
    assertThat(nowRegistered).as("registered now: remove them from PENDING").isEmpty();
  }

  private static boolean registered(String sources, String micrometer) {
    if (sources.contains("\"" + micrometer + "\"")) {
      return true;
    }
    // A MeterNames constant used outside MeterNames.
    Matcher m =
        Pattern.compile("public static final String (\\w+) = \"" + Pattern.quote(micrometer) + "\"")
            .matcher(sources);
    while (m.find()) {
      if (sources.contains("MeterNames." + m.group(1))) {
        return true;
      }
    }
    return false;
  }

  private static List<Entry> all() {
    List<Entry> out = new ArrayList<>(SelfMeterCatalog.DOCUMENTED);
    out.addAll(SelfMeterCatalog.ADDED);
    return out;
  }

  /** Every main source of the server except the files that only name meters. */
  private static String mainSources() throws IOException {
    Path main = ROOT.resolve("server/src/main/java");
    StringBuilder out = new StringBuilder();
    try (Stream<Path> files = Files.walk(main)) {
      for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
        if (NOT_REGISTERING.contains(f.getFileName().toString())) {
          continue;
        }
        out.append(Files.readString(f, StandardCharsets.UTF_8)).append('\n');
      }
    }
    return out.toString();
  }

  /** The {@code argos_*} names of 12 §9.4, in order of first appearance. */
  static List<String> section94Names() throws IOException {
    List<String> lines =
        Files.readAllLines(ROOT.resolve("docs/12-server-design.md"), StandardCharsets.UTF_8);
    StringBuilder section = new StringBuilder();
    boolean in = false;
    for (String line : lines) {
      if (line.startsWith("### 9.4")) {
        in = true;
        continue;
      }
      if (in && line.startsWith("#")) {
        break;
      }
      if (in) {
        section.append(line).append('\n');
      }
    }
    // Names only up to the sentence after the list (the metric table note has no names).
    Set<String> names = new LinkedHashSet<>();
    Matcher m = Pattern.compile("`(argos_[a-z0-9_]+)").matcher(section);
    while (m.find()) {
      names.add(m.group(1));
    }
    return new ArrayList<>(names);
  }
}
