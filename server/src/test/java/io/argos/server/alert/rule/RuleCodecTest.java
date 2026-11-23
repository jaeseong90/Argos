/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.server.alert.metric.AlertMetric;
import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorBody;
import io.argos.server.api.error.ErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The rule JSON of 14 §4.13 and 12 §5.1: every field, defaults, validation errors, the canonical
 * round trip and the published JSON Schema resource.
 */
@Tag("req:F-S06")
@Tag("req:F-S25")
class RuleCodecTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** The example of 14 §4.13 with UUID channel ids. */
  static final String FULL =
      "{\"rule_id\":\"ignored\",\"name\":\"p95"
          + " latency\",\"enabled\":true,\"template_key\":\"p95_latency\","
          + "\"template_version\":1,\"target\":{\"app_id\":3,\"env\":\"prod\",\"type\":\"app\",\"ids\":[]},"
          + "\"kind\":\"HTTP\",\"metric\":\"response_time_p95\",\"window\":\"1m\",\"for\":\"5m\","
          + "\"evaluation_unit\":\"app\",\"condition\":{\"operator\":\">=\",\"warn\":3000,\"critical\":10000},"
          + "\"recover\":{\"threshold\":2000,\"for\":\"2m\"},\"min_samples\":20,\"no_data\":\"ignore\","
          + "\"warmup_grace\":\"3m\",\"active_time\":[{\"days\":[\"Mon\",\"Tue\",\"Wed\",\"Thu\",\"Fri\"],\"from\":\"00:00\",\"to\":\"24:00\"}],\"mute_schedules\":[{\"cron\":\"0"
          + " 2 * * *\",\"duration\":\"2h\",\"reason\":\"daily batch\"}],\"channels\":{\"warn\":"
          + "[\"6f1f0c3e-0000-4000-8000-000000000001\"],\"critical\":[\"6f1f0c3e-0000-4000-8000-000000000001\","
          + "\"6f1f0c3e-0000-4000-8000-000000000002\"]},\"renotify_interval\":\"30m\","
          + "\"runbook_url\":\"https://wiki.example/runbook/p95\",\"message_template\":\"{{app}}/{{env}}"
          + " {{service}} p95 {{value}} >= {{threshold}} for {{duration}} {{link}}\"}";

  @Test
  @DisplayName(
      "T-SV-032 the 14 §4.13 rule example parses with every 12 §5.1 field and round-trips through"
          + " the canonical stored JSON")
  void fullRule() {
    RuleSpec r = RuleCodec.parse(JSON.readTree(FULL));
    assertThat(r.name()).isEqualTo("p95 latency");
    assertThat(r.target().appId()).isEqualTo(3);
    assertThat(r.target().env()).isEqualTo("prod");
    assertThat(r.metric()).isEqualTo("response_time_p95");
    assertThat(r.window()).isEqualTo(Duration.ofMinutes(1));
    assertThat(r.forDuration()).isEqualTo(Duration.ofMinutes(5));
    assertThat(r.condition().warn()).isEqualTo(3000.0);
    assertThat(r.recover().threshold()).isEqualTo(2000.0);
    assertThat(r.recover().forDuration()).isEqualTo(Duration.ofMinutes(2));
    assertThat(r.activeTime()).hasSize(1);
    assertThat(r.activeTime().get(0).to()).isNull();
    assertThat(r.muteSchedules().get(0).duration()).isEqualTo(Duration.ofHours(2));
    assertThat(r.channels().critical()).hasSize(2);
    assertThat(r.channels().all()).hasSize(2);
    assertThat(r.templateVersion()).isEqualTo(1);
    RuleSpec again = RuleCodec.parseStored(RuleCodec.toJsonText(r));
    assertThat(again).isEqualTo(r);
    assertThat(RuleCodec.toJson(r).get("active_time").get(0).get("to").asString())
        .isEqualTo("24:00");
  }

  @Test
  @DisplayName(
      "T-SV-032 defaults of 12 §5.1: window 1m, for 0s, unit app, min_samples 20 for request"
          + " metrics (0 otherwise), no_data ignore, warm-up 3m, renotify 30m")
  void defaults() {
    RuleSpec r =
        parse(
            "{\"name\":\"e\",\"target\":{\"app_id\":1,\"type\":\"app\"},\"metric\":\"error_rate\","
                + "\"condition\":{\"operator\":\">=\",\"warn\":0.05}}");
    assertThat(r.window()).isEqualTo(Duration.ofMinutes(1));
    assertThat(r.forDuration()).isZero();
    assertThat(r.minSamples()).isEqualTo(20);
    assertThat(r.noData()).isEqualTo(RuleSpec.NoData.IGNORE);
    assertThat(r.warmupGrace()).isEqualTo(Duration.ofMinutes(3));
    assertThat(r.renotifyInterval()).isEqualTo(Duration.ofMinutes(30));
    RuleSpec heap =
        parse(
            "{\"name\":\"h\",\"target\":{\"app_id\":1,\"type\":\"app\"},\"metric\":\"jvm_heap_used_ratio\","
                + "\"evaluation_unit\":\"instance\",\"condition\":{\"operator\":\">=\",\"warn\":0.85}}");
    assertThat(heap.minSamples()).isZero();
  }

  @Test
  @DisplayName(
      "T-SV-032 invalid rules answer 400 invalid_request with one detail per field (unknown field,"
          + " metric, operator, missing threshold, window range, renotify, target, cron, runbook)")
  void validation() {
    ApiException e =
        assertThrows(
            "{\"name\":\"\",\"target\":{\"type\":\"service\",\"ids\":[\"x\"]},\"metric\":\"nope\","
                + "\"window\":\"1s\",\"renotify_interval\":\"1m\",\"condition\":{\"operator\":\"=>\"},"
                + "\"mute_schedules\":[{\"cron\":\"bad\",\"duration\":\"0s\"}],\"runbook_url\":\"javascript:x\","
                + "\"extra\":1}");
    assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
    assertThat(e.details())
        .extracting(ErrorBody.Detail::field)
        .contains(
            "extra",
            "name",
            "metric",
            "target.ids",
            "target.app_id",
            "window",
            "renotify_interval",
            "condition.operator",
            "condition",
            "mute_schedules[0].cron",
            "mute_schedules[0].duration",
            "runbook_url");
    assertThat(
            assertThrows(
                    "{\"name\":\"x\",\"target\":{\"app_id\":1,\"type\":\"ext_host\"},\"metric\":\"error_rate\","
                        + "\"condition\":{\"operator\":\">=\",\"warn\":1}}")
                .details())
        .extracting(ErrorBody.Detail::field)
        .containsExactly("target.type");
    assertThat(
            assertThrows(
                    "{\"name\":\"x\",\"target\":{\"app_id\":1,\"type\":\"server\"},\"metric\":\"argos_server_ingest_lag_seconds\","
                        + "\"condition\":{\"operator\":\">=\",\"warn\":30,\"critical\":10}}")
                .details())
        .extracting(ErrorBody.Detail::field)
        .containsExactly("target.app_id", "condition.critical");
  }

  @Test
  @DisplayName(
      "T-SV-032 the published JSON Schema alert/alert-rule.schema.json names exactly the fields the"
          + " server accepts and every metric of 12 §5.3")
  void schemaMatchesCodec() throws IOException {
    JsonNode schema;
    try (InputStream in = RuleCodec.class.getResourceAsStream("/alert/alert-rule.schema.json")) {
      assertThat(in).isNotNull();
      schema = JSON.readTree(in);
    }
    Set<String> fields = new HashSet<>(schema.get("properties").propertyNames());
    Set<String> expected = new HashSet<>(RuleCodec.FIELDS);
    expected.addAll(RuleCodec.READ_ONLY_FIELDS);
    assertThat(fields).isEqualTo(expected);
    List<String> metrics =
        schema.get("properties").get("metric").get("enum").values().stream()
            .map(JsonNode::asString)
            .toList();
    assertThat(metrics)
        .containsExactlyInAnyOrderElementsOf(
            Arrays.stream(AlertMetric.values()).map(AlertMetric::wire).toList());
  }

  @Test
  @DisplayName("T-SV-032 rule durations: 30s, 1m, 1h30m, 7d, 0; canonical text and human text")
  void durations() {
    assertThat(RuleDurations.parse("1h30m")).isEqualTo(Duration.ofMinutes(90));
    assertThat(RuleDurations.parse("0")).isZero();
    assertThat(RuleDurations.parse("5x")).isNull();
    assertThat(RuleDurations.format(Duration.ofMinutes(90))).isEqualTo("1h30m");
    assertThat(RuleDurations.format(Duration.ZERO)).isEqualTo("0s");
    assertThat(RuleDurations.human(Duration.ofSeconds(310))).isEqualTo("5m 10s");
    assertThat(RuleDurations.human(Duration.ofMinutes(62))).isEqualTo("1h 2m");
  }

  private static RuleSpec parse(String json) {
    return RuleCodec.parse(JSON.readTree(json));
  }

  private static ApiException assertThrows(String json) {
    ApiException[] out = new ApiException[1];
    assertThatThrownBy(() -> parse(json))
        .isInstanceOfSatisfying(ApiException.class, e -> out[0] = e);
    return out[0];
  }
}
