/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.load;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The JSON writer and the summary built from the counters. */
class SummaryTest {

  @Test
  @DisplayName("JSON: escaping, numbers (six significant digits, no exponent), NaN as null")
  void json() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("text", "a\"b\\c\n\t" + (char) 1 + "한");
    m.put("rate", 0.0004);
    m.put("big", 1234.56789);
    m.put("whole", 1000.0);
    m.put("long", 42L);
    m.put("nan", Double.NaN);
    m.put("none", null);
    m.put("flag", true);
    m.put("list", List.of(1, "x"));
    m.put("empty", Map.of());
    assertThat(Json.write(m))
        .isEqualTo(
            """
            {
              "text": "a\\"b\\\\c\\n\\t\\u0001한",
              "rate": 0.0004,
              "big": 1234.57,
              "whole": 1000,
              "long": 42,
              "nan": null,
              "none": null,
              "flag": true,
              "list": [
                1,
                "x"
              ],
              "empty": {}
            }
            """);
  }

  @Test
  @DisplayName("Summary: achieved rate, unexpected rate (incl. dropped), 5xx rate, route shares")
  void summary() {
    LoadConfig config =
        LoadConfig.from(Map.of(), "--rate", "100", "--mix", "bench", "--duration", "10s");
    LoadStats stats = new LoadStats(config.mix());
    // orders.get (route 0): 8 answered 200, 1 dropped; error-500 (route 5): 2 answered 500,
    // 1 answered 200 (unexpected); pay (route 2): 1 timeout.
    for (int i = 0; i < 9; i++) {
      stats.scheduled(0);
    }
    for (int i = 0; i < 8; i++) {
      stats.sent(0, 0);
      stats.completed(0, 200, 2_000_000L);
    }
    stats.dropped(0);
    for (int i = 0; i < 3; i++) {
      stats.scheduled(5);
      stats.sent(5, 0);
    }
    stats.completed(5, 500, 4_000_000L);
    stats.completed(5, 500, 4_000_000L);
    stats.completed(5, 200, 4_000_000L);
    stats.scheduled(2);
    stats.sent(2, 0);
    stats.failed(2, Outcome.TIMEOUT);

    FixedRateGenerator.Result result = new FixedRateGenerator.Result(2_000_000_000L, 13, 0);
    Map<String, Object> machine = Map.of("availableProcessors", 12);
    Map<String, Object> summary =
        Summary.of(config, "1.0", "2026-10-23T00:00:00Z", result, stats.snapshot(), machine);

    Map<?, ?> requests = (Map<?, ?>) summary.get("requests");
    assertThat(requests.get("sent")).isEqualTo(12L);
    assertThat(requests.get("dropped")).isEqualTo(1L);
    assertThat(requests.get("answered")).isEqualTo(11L);
    assertThat(requests.get("expected")).isEqualTo(10L);
    assertThat((Double) requests.get("achievedRate")).isCloseTo(6.0, within(1e-9));
    // unexpected: 1 wrong status + 1 timeout + 1 dropped of 13 due.
    assertThat((Double) requests.get("unexpectedRate")).isCloseTo(3.0 / 13, within(1e-9));
    // 5xx or no answer among the 12 finished requests: 2 x 500 + 1 timeout.
    assertThat((Double) requests.get("http5xxRate")).isCloseTo(3.0 / 12, within(1e-9));
    assertThat(summary.get("machine")).isEqualTo(machine);

    List<?> routes = (List<?>) summary.get("routes");
    Map<?, ?> orders = (Map<?, ?>) routes.get(0);
    assertThat(orders.get("route")).isEqualTo("orders.get");
    assertThat((Double) orders.get("sharePercent")).isCloseTo(100.0 * 8 / 12, within(1e-9));
    Map<?, ?> errors = (Map<?, ?>) routes.get(5);
    assertThat(errors.get("statuses")).isEqualTo(Map.of("200", 1L, "500", 2L));
    assertThat(((Map<?, ?>) errors.get("latencyMs")).get("p50")).isEqualTo(4.0);

    String line = Summary.finalLine(summary);
    assertThat(line).contains("sent=12", "achieved=6.000/s", "dropped=1", "p50=");
    assertThat(Json.write(summary)).contains("\"tool\": \"argos-demo-load\"", "\"mix\": \"bench\"");
  }

  @Test
  @DisplayName("Warm-up: listed apart as warmupExcluded, outside every rate of the window")
  void warmup() {
    LoadConfig config =
        LoadConfig.from(Map.of(), "--rate", "10", "--warmup", "5s", "--duration", "10s");
    LoadStats warmup = new LoadStats(config.mix());
    for (int i = 0; i < 3; i++) {
      warmup.scheduled(0);
      warmup.dropped(0);
    }
    LoadStats measured = new LoadStats(config.mix());
    measured.scheduled(0);
    measured.sent(0, 0);
    measured.completed(0, 200, 1_000_000L);
    FixedRateGenerator.Result result = new FixedRateGenerator.Result(10_000_000_000L, 1, 0);

    Map<String, Object> summary =
        Summary.of(config, "1", "now", result, measured.snapshot(), warmup.snapshot(), Map.of());

    assertThat(((Map<?, ?>) summary.get("config")).get("warmupSeconds")).isEqualTo(5.0);
    Map<?, ?> excluded = (Map<?, ?>) summary.get("warmupExcluded");
    assertThat(excluded.get("dropped")).isEqualTo(3L);
    Map<?, ?> requests = (Map<?, ?>) summary.get("requests");
    assertThat(requests.get("dropped")).isEqualTo(0L);
    assertThat((Double) requests.get("unexpectedRate")).isZero();
    // No warm-up configured: no section.
    LoadConfig plain = LoadConfig.from(Map.of(), "--duration", "10s");
    assertThat(
            Summary.of(plain, "1", "now", result, measured.snapshot(), warmup.snapshot(), Map.of()))
        .doesNotContainKey("warmupExcluded");
  }

  @Test
  @DisplayName("Progress line: interval rate and interval percentiles")
  void progressLine() {
    Mix mix = Mix.bench();
    LoadStats stats = new LoadStats(mix);
    LoadStats.Snapshot before = stats.snapshot();
    for (int i = 0; i < 50; i++) {
      stats.scheduled(0);
      stats.sent(0, 0);
      stats.completed(0, 200, 3_000_000L);
    }
    String line = Summary.progressLine(20, 10, stats.snapshot(), before, 3);
    assertThat(line)
        .startsWith("[demo-load] t=20s rate=5.0/s sent=50 expected=50 unexpected=0 5xx=0")
        .contains("inFlight=3", "p50=3.0ms");
  }
}
