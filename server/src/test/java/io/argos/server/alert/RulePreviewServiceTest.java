/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.server.alert.rule.RuleSpec;
import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** The firing detection and the input checks of the rule preview (14 §4.13, F-S06). */
@Tag("req:F-S06")
class RulePreviewServiceTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Instant T0 = Instant.parse("2026-11-20T00:00:00Z");
  private static final long STEP = 300_000;

  private static List<Instant> ts(int n) {
    List<Instant> out = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      out.add(T0.plusMillis(i * STEP));
    }
    return out;
  }

  private static List<Boolean> breach(List<Double> values, RuleSpec.Operator op, double t) {
    return values.stream().map(v -> v != null && op.test(v, t)).toList();
  }

  @Test
  @DisplayName(
      "a run of breaching buckets fires when it lasts at least for: start = run start + for, end"
          + " = run end, peak = the worst value")
  void runsLastingForFire() {
    List<Double> values = Arrays.asList(0.1, 0.6, 0.9, 0.7, 0.1, 0.8, null, 0.1);
    List<RulePreviewService.Firing> f =
        RulePreviewService.firings(
            ts(8),
            values,
            breach(values, RuleSpec.Operator.GTE, 0.5),
            STEP,
            Duration.ofMinutes(10),
            RuleSpec.Operator.GTE);
    // buckets 1..3 (15 minutes) fire; bucket 5 alone (5 minutes) is shorter than for
    assertThat(f).hasSize(1);
    assertThat(f.get(0).start()).isEqualTo(T0.plusMillis(STEP).plus(Duration.ofMinutes(10)));
    assertThat(f.get(0).end()).isEqualTo(T0.plusMillis(4 * STEP));
    assertThat(f.get(0).peakValue()).isEqualTo(0.9);
  }

  @Test
  @DisplayName(
      "for 0s: every breaching run fires at its start; a lower-is-worse peak is the minimum")
  void zeroForAndLowerIsWorse() {
    List<Double> values = Arrays.asList(0.95, 0.6, 0.5, 0.95, 0.4);
    List<RulePreviewService.Firing> f =
        RulePreviewService.firings(
            ts(5),
            values,
            breach(values, RuleSpec.Operator.LT, 0.7),
            STEP,
            Duration.ZERO,
            RuleSpec.Operator.LT);
    assertThat(f).hasSize(2);
    assertThat(f.get(0).start()).isEqualTo(T0.plusMillis(STEP));
    assertThat(f.get(0).peakValue()).isEqualTo(0.5);
    assertThat(f.get(1).start()).isEqualTo(T0.plusMillis(4 * STEP));
    assertThat(f.get(1).end()).isEqualTo(T0.plusMillis(5 * STEP));
  }

  private static ObjectNode rule(String metric, String type) {
    ObjectNode r = JSON.createObjectNode();
    r.put("name", "preview");
    ObjectNode t = r.putObject("target");
    t.put("app_id", 3);
    t.put("env", "prod");
    t.put("type", type);
    t.putArray("ids");
    r.put("metric", metric);
    ObjectNode c = r.putObject("condition");
    c.put("operator", ">=");
    c.put("warn", 1);
    return r;
  }

  private static RulePreviewService service() {
    return new RulePreviewService(
        () -> null, Clock.fixed(Instant.parse("2026-11-23T00:00:00Z"), ZoneOffset.UTC));
  }

  @Test
  @DisplayName(
      "days outside 1..7, a metric outside the rollups and a service rule without an id → 400;"
          + " no ClickHouse → 503 storage_unavailable")
  void inputChecks() {
    RulePreviewService s = service();
    assertThatThrownBy(() -> s.preview(rule("error_rate", "app"), 8))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
    assertThatThrownBy(() -> s.preview(rule("active_transactions", "app"), 7))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(e.details().toString()).contains("preview_unsupported"));
    assertThatThrownBy(() -> s.preview(rule("argos_server_ingest_lag_seconds", "app"), 7))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> s.preview(rule("response_time_p95", "service"), 7))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.details().toString()).contains("target.ids"));
    assertThatThrownBy(() -> s.preview(rule("error_rate", "app"), 7))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.STORAGE_UNAVAILABLE));
  }
}
