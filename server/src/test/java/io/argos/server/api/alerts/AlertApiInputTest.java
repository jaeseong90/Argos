/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.alerts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.server.alert.AlertRuleService;
import io.argos.server.alert.engine.ActiveAlert;
import io.argos.server.alert.rule.RuleCodec;
import io.argos.server.alert.templates.DefaultTemplates;
import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Input checks of the alert API (14 §4.13, §7.2) and the active alert item of the firing panels
 * (WO-25 plan §3.1, ADR-0010 C-212 (2)).
 */
@Tag("req:F-S26")
@Tag("req:C-212")
class AlertApiInputTest {

  @Test
  @DisplayName(
      "a note or reason: blank is none, at most 512 characters and 1 KB of UTF-8 (400"
          + " limit_exceeded beyond)")
  void noteLimits() {
    assertThat(AlertEventsController.text("note", "  ")).isNull();
    assertThat(AlertEventsController.text("note", "x".repeat(512))).hasSize(512);
    assertThatThrownBy(() -> AlertEventsController.text("note", "x".repeat(513)))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.LIMIT_EXCEEDED));
    // 342 three-byte characters: 1,026 bytes
    assertThatThrownBy(() -> AlertEventsController.text("note", "가".repeat(342)))
        .isInstanceOf(ApiException.class);
    assertThat(AlertEventsController.text("note", "가".repeat(341))).hasSize(341);
  }

  @Test
  @DisplayName(
      "severity and state filters are case-insensitive and closed sets (400 filter_invalid)")
  void filters() {
    assertThat(AlertEventsController.severity(" CRITICAL ")).isEqualTo("critical");
    assertThat(AlertEventsController.severity(null)).isNull();
    assertThatThrownBy(() -> AlertEventsController.severity("info"))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.FILTER_INVALID));
    assertThat(AlertEventsController.state("acked", Set.of("FIRING", "ACKED"))).isEqualTo("ACKED");
    assertThatThrownBy(() -> AlertEventsController.state("OK", Set.of("FIRING", "ACKED")))
        .isInstanceOf(ApiException.class);
  }

  @Test
  @DisplayName(
      "an active alert item: event id, app, target, rule, duration up to now, acknowledgement by"
          + " name, the engine's deep link")
  void activeAlertItem() {
    UUID event = UUID.randomUUID();
    UUID rule = UUID.randomUUID();
    UUID user = UUID.randomUUID();
    Instant fired = Instant.parse("2026-11-23T00:00:00.123456Z");
    ActiveAlert a =
        new ActiveAlert(
            event,
            fired,
            7,
            "shop",
            "prod",
            "service",
            "18446744073709551615",
            "GET /orders",
            rule,
            "p95",
            "response_time_p95",
            "critical",
            "ACKED",
            3200.0,
            3000.0,
            5,
            user,
            "ops",
            fired.plusSeconds(30),
            "on it",
            "https://argos.example/apps/7/dashboard?alert=" + event);
    AlertViews.AlertEventItem item = AlertViews.AlertEventItem.of(a, fired.plusSeconds(125));
    assertThat(item.eventId()).isEqualTo(event.toString());
    assertThat(item.ts()).isEqualTo(Instant.parse("2026-11-23T00:00:00.123Z"));
    assertThat(item.app()).isEqualTo(new AlertViews.AlertApp(7, "shop"));
    assertThat(item.target())
        .isEqualTo(new AlertViews.AlertTarget("service", "18446744073709551615", "GET /orders"));
    assertThat(item.rule())
        .isEqualTo(new AlertViews.AlertRuleRef(rule.toString(), "p95", "response_time_p95", "ms"));
    assertThat(item.durationS()).isEqualTo(125);
    assertThat(item.ackedBy()).isEqualTo("ops");
    assertThat(item.note()).isEqualTo("on it");
    assertThat(item.deeplink()).endsWith("alert=" + event);

    ActiveAlert server =
        new ActiveAlert(
            event,
            fired,
            null,
            null,
            null,
            "server",
            null,
            "ops-hook",
            rule,
            "channel failure",
            "argos_notification_failures_total",
            "warn",
            "FIRING",
            null,
            null,
            0,
            null,
            null,
            null,
            null,
            "/alerts/events?alert=x");
    AlertViews.AlertEventItem self = AlertViews.AlertEventItem.of(server, fired);
    assertThat(self.app()).isNull();
    assertThat(self.ackedBy()).isNull();
    assertThat(self.durationS()).isZero();
    assertThat(self.rule().unit()).isEqualTo("count");
  }

  @Test
  @DisplayName(
      "T-SV-033 the rule response carries the canonical rule JSON with rule_id, version and times;"
          + " GET /alerts/templates maps the 14 templates with their parts")
  void ruleAndTemplateViews() {
    ObjectNode json = JsonMapper.builder().build().createObjectNode();
    json.put("name", "p95");
    ObjectNode t = json.putObject("target");
    t.put("app_id", 3);
    t.put("env", "prod");
    t.put("type", "app");
    t.putArray("ids");
    json.put("metric", "response_time_p95");
    json.put("window", "1m");
    json.put("for", "5m");
    ObjectNode c = json.putObject("condition");
    c.put("operator", ">=");
    c.put("warn", 3000);
    c.put("critical", 10000);
    UUID id = UUID.randomUUID();
    AlertRuleService.RuleView v =
        new AlertRuleService.RuleView(
            id,
            RuleCodec.parse(json),
            2,
            null,
            Instant.parse("2026-11-23T00:00:00.123456Z"),
            Instant.parse("2026-11-23T00:05:00Z"));
    AlertRuleDtos.Rule r = AlertRuleDtos.Rule.of(v);
    assertThat(r.ruleId()).isEqualTo(id.toString());
    assertThat(r.version()).isEqualTo(2);
    assertThat(r.createdBy()).isNull();
    assertThat(r.createdAt()).isEqualTo(Instant.parse("2026-11-23T00:00:00.123Z"));
    assertThat(r.forDuration()).isEqualTo("5m");
    assertThat(r.window()).isEqualTo("1m");
    assertThat(r.target().appId()).isEqualTo(3);
    assertThat(r.target().ids()).isEmpty();
    assertThat(r.condition().critical()).isEqualTo(10000.0);
    assertThat(r.minSamples()).isEqualTo(20);
    assertThat(r.channels().warn()).isEmpty();

    List<AlertRuleDtos.Template> templates =
        DefaultTemplates.standard().all().stream().map(AlertRuleDtos.Template::of).toList();
    assertThat(templates).hasSize(14);
    assertThat(templates)
        .allSatisfy(
            tpl -> {
              assertThat(tpl.defaults()).isNotEmpty();
              assertThat(tpl.defaults())
                  .allSatisfy(p -> assertThat(p.rule().metric()).isNotBlank());
            });
    assertThat(templates.stream().filter(tpl -> tpl.appliesWhen().equals("server")).count())
        .isEqualTo(4);
  }
}
