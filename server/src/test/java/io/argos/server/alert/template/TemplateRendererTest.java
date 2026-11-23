/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.server.alert.api.AlertNotification;
import io.argos.server.alert.rule.RuleCodec;
import io.argos.server.alert.rule.RuleSpec.TargetType;
import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Alert message templates (SEC-39): the allowed variable set is a snapshot (a public contract,
 * T-API-008), a template with any other reference is refused when the rule is saved, rendering
 * substitutes plain text only; the deep link follows 14 §6.
 */
@Tag("req:SEC-39")
class TemplateRendererTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  @Tag("req:O-R03")
  @DisplayName(
      "T-SEC-39 T-API-008 the allowed template variables equal the snapshot template-variables.txt"
          + " (app, env, instance, service, host, metric, value, threshold, severity, duration,"
          + " started_at, link, runbook, rule)")
  void variableSetSnapshot() throws IOException {
    List<String> snapshot;
    try (InputStream in =
        TemplateRendererTest.class.getResourceAsStream("template-variables.txt")) {
      assertThat(in).isNotNull();
      snapshot =
          Arrays.stream(new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n", -1))
              .map(String::strip)
              .filter(l -> !l.isEmpty() && !l.startsWith("#"))
              .toList();
    }
    assertThat(snapshot).isSorted();
    assertThat(new TreeSet<>(AlertNotification.VARIABLES)).containsExactlyElementsOf(snapshot);
  }

  @Test
  @DisplayName(
      "T-SEC-39 saving a rule whose message template references a forbidden variable answers 422"
          + " template_variable_forbidden (unknown name, dotted path, expression, raw collected"
          + " text)")
  void forbiddenVariableRefused() {
    for (String bad :
        List.of(
            "{{app}} {{sql}}",
            "{{ user_id }}",
            "{{app.name}}",
            "{{value | upper}}",
            "{{exception_message}}",
            "{{url}}",
            "{{}}")) {
      String rule =
          "{\"name\":\"r\",\"target\":{\"app_id\":1,\"type\":\"app\"},\"metric\":\"error_rate\","
              + "\"condition\":{\"operator\":\">=\",\"warn\":0.05},\"message_template\":"
              + JSON.writeValueAsString(bad)
              + "}";
      assertThatThrownBy(() -> RuleCodec.parse(JSON.readTree(rule)))
          .as(bad)
          .isInstanceOfSatisfying(
              ApiException.class,
              e -> {
                assertThat(e.errorCode()).isEqualTo(ErrorCode.TEMPLATE_VARIABLE_FORBIDDEN);
                assertThat(e.errorCode().status()).isEqualTo(422);
              });
    }
    assertThat(TemplateRenderer.forbiddenReferences("{{app}} {{sql}} {{ env }} {{x.y}}"))
        .containsExactly("sql", "x.y");
  }

  @Test
  @DisplayName(
      "T-SEC-39 a template of allowed variables is accepted; rendering substitutes plain text (no"
          + " evaluation of values that look like templates), missing values render empty")
  void renderPlainText() {
    String rule =
        "{\"name\":\"r\",\"target\":{\"app_id\":1,\"type\":\"app\"},\"metric\":\"error_rate\","
            + "\"condition\":{\"operator\":\">=\",\"warn\":0.05},\"message_template\":"
            + "\"{{app}}/{{ env }} {{metric}} {{value}} >= {{threshold}} for {{duration}} {{link}}"
            + " {{runbook}}\"}";
    assertThat(RuleCodec.parse(JSON.readTree(rule)).messageTemplate()).contains("{{ env }}");
    Map<String, String> v = new LinkedHashMap<>();
    v.put("app", "shop {{rule}}");
    v.put("env", "prod");
    v.put("metric", "error_rate");
    v.put("value", "25.0%");
    v.put("threshold", "5.0%");
    v.put("duration", "5m");
    v.put("link", "https://argos.example/x");
    assertThat(TemplateRenderer.render(RuleCodec.parse(JSON.readTree(rule)).messageTemplate(), v))
        .isEqualTo("shop {{rule}}/prod error_rate 25.0% >= 5.0% for 5m https://argos.example/x");
    assertThat(TemplateRenderer.render(null, Map.of("rule", "p95", "severity", "warn")))
        .startsWith("[warn] p95:");
  }

  @Test
  @DisplayName(
      "T-SEC-39 the deep link is"
          + " <public-url>/apps/{app}/dashboard?env=&from=<firing-15m>&to=<firing+15m>&f.instance=&alert=<event>"
          + " with absolute ISO times and no live key; server alerts link the history")
  void deepLink() {
    UUID event = UUID.fromString("00000000-0000-0000-0000-000000000001");
    Instant at = Instant.parse("2026-11-23T03:00:00.250Z");
    String link =
        DeepLinks.alert(
            "https://argos.example.com/", 7, "prod", TargetType.INSTANCE, 42L, at, event);
    assertThat(link)
        .isEqualTo(
            "https://argos.example.com/apps/7/dashboard?env=prod&from=2026-11-23T02%3A45%3A00Z"
                + "&to=2026-11-23T03%3A15%3A00Z&f.instance=42&alert="
                + event);
    assertThat(link).doesNotContain("live");
    assertThat(DeepLinks.alert("https://a.example", null, null, TargetType.SERVER, null, at, event))
        .startsWith("https://a.example/alerts/events?from=")
        .endsWith("&alert=" + event);
    assertThat(DeepLinks.alert("https://a.example", 3, "dev", TargetType.SERVICE, -1L, at, event))
        .as("64-bit service keys are unsigned decimal")
        .contains("&f.service=18446744073709551615&");
  }
}
