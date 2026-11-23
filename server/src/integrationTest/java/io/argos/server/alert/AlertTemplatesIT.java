/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.proto.v1.TransactionRecord;
import io.argos.server.alert.rule.RuleCodec;
import io.argos.server.alert.rule.RuleSpec;
import io.argos.server.alert.templates.DefaultTemplates;
import io.argos.server.alert.templates.DefaultTemplates.Part;
import io.argos.server.alert.templates.DefaultTemplates.Scope;
import io.argos.server.alert.templates.DefaultTemplates.Template;
import io.argos.server.alert.templates.TemplateDiff;
import io.argos.server.alert.templates.TemplateService;
import io.argos.server.testing.fakeagent.AgentTokens;
import io.argos.server.testing.fakeagent.FakeAgent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The default rule templates over the whole server on real PostgreSQL and ClickHouse (F-S24, 12
 * §5.6): the server self rules once at the first start, "apply recommended rules" for an
 * application created without the API (SQL seed, the path of every registration), the batch
 * template at the first BATCH transaction, the 14 template keys, the apply API's skips and the
 * difference of a stored rule to a newer template version.
 */
@Tag("integration")
@Tag("req:F-S24")
class AlertTemplatesIT {

  private static AlertStack stack;

  @BeforeAll
  static void start() {
    stack = AlertStack.start(null);
  }

  @AfterAll
  static void stop() {
    if (stack != null) {
      stack.close();
    }
  }

  private static Set<String> parts(String where, Object... args) throws Exception {
    Set<String> out = new TreeSet<>();
    for (List<Object> r :
        stack.rows(
            "SELECT template_key, spec->>'metric' FROM argos.alert_rules WHERE " + where, args)) {
      out.add(r.get(0) + "/" + r.get(1));
    }
    return out;
  }

  @Test
  @DisplayName(
      "T-SV-033 the server self rules (disk, ingest lag, channel failure, certificate and token"
          + " expiry, server drops) are created once at the first start and not again after a"
          + " restart")
  void serverRulesOnce() throws Exception {
    Set<String> expected =
        Set.of(
            "collection/argos_server_dropped_total",
            "server_disk/argos_server_disk_usage_ratio",
            "ingest_lag/argos_server_ingest_lag_seconds",
            "channel_failure/argos_notification_failures_total",
            "cert_token_expiry/argos_server_tls_cert_expiry_days",
            "cert_token_expiry/agent_token_expiry_days");
    assertThat(parts("app_id IS NULL")).isEqualTo(expected);
    assertThat(
            stack.rows(
                "SELECT value::text FROM argos.settings WHERE key = 'alert_server_rules_applied'"))
        .containsExactly(List.of("true"));
    stack.restartServer();
    assertThat(stack.rows("SELECT count(*) FROM argos.alert_rules WHERE app_id IS NULL"))
        .containsExactly(List.of(6L));
    assertThat(
            stack.rows(
                "SELECT count(*) FROM argos.alert_rule_history h JOIN argos.alert_rules r"
                    + " ON r.id = h.rule_id WHERE r.app_id IS NULL AND h.version = 1"))
        .containsExactly(List.of(6L));
  }

  @Test
  @DisplayName(
      "T-SV-033 apply recommended rules: an application created without the API gets the 9"
          + " application templates (10 rules) and recommended_rules_applied = true; the batch"
          + " template follows its first BATCH transaction; 14 template keys in all; no duplicates"
          + " on a second run or an explicit apply")
  void recommendedRules() throws Exception {
    AgentTokens.IssuedToken token = stack.app("tmpl-shop");
    int appId = token.appId();
    assertThat(stack.rows("SELECT recommended_rules_applied FROM argos.apps WHERE id = ?", appId))
        .containsExactly(List.of(false));
    stack.engine().housekeepingNow();
    Set<String> app = parts("app_id = ? AND deleted_at IS NULL", appId);
    assertThat(app)
        .containsExactlyInAnyOrder(
            "instance_down/instance_state",
            "error_rate/error_rate",
            "p95_latency/response_time_p95",
            "active_transactions/active_ratio_to_max_threads",
            "connection_pool/jdbc_pool_waiting",
            "connection_pool/jdbc_pool_usage_ratio",
            "heap/jvm_heap_used_ratio",
            "gc_pause/jvm_gc_pause_ratio",
            "ext_timeout/ext_call_timeout_ratio",
            "collection/argos_agent_dropped_total");
    assertThat(stack.rows("SELECT recommended_rules_applied FROM argos.apps WHERE id = ?", appId))
        .containsExactly(List.of(true));
    assertThat(stack.rows("SELECT DISTINCT env FROM argos.alert_rules WHERE app_id = ?", appId))
        .containsExactly(List.of("prod"));
    stack.engine().housekeepingNow();
    assertThat(stack.rows("SELECT count(*) FROM argos.alert_rules WHERE app_id = ?", appId))
        .containsExactly(List.of(10L));

    try (FakeAgent a = stack.agent(token, "tmpl-shop", "batch-host:1")) {
      assertThat(a.connect().accepted()).isTrue();
      long before = stack.xlogPoints();
      long now = a.clock().nowMs();
      TransactionRecord tx =
          a.records().batch("nightly-settlement", now - 5_000, 5_000_000, false).build();
      assertThat(a.sendTransactions(a.batches().transactions(List.of(tx))).ok()).isTrue();
      Awaitility.await().atMost(30, TimeUnit.SECONDS).until(() -> stack.xlogPoints() > before);
      Awaitility.await()
          .atMost(30, TimeUnit.SECONDS)
          .until(
              () -> {
                stack.engine().evaluateNow();
                return parts("app_id = ?", appId).contains("batch_delay/batch_elapsed_seconds");
              });
    }
    Set<String> keys = new HashSet<>();
    for (List<Object> r :
        stack.rows(
            "SELECT DISTINCT template_key FROM argos.alert_rules WHERE (app_id = ? OR app_id IS"
                + " NULL) AND template_key IS NOT NULL",
            appId)) {
      keys.add((String) r.get(0));
    }
    assertThat(keys).hasSize(14);
    assertThat(keys)
        .containsExactlyInAnyOrderElementsOf(
            DefaultTemplates.standard().all().stream().map(Template::key).toList());

    TemplateService.ApplyResult again =
        stack.server.bean(AlertRuleService.class).applyTemplates(appId, "prod", null, null);
    assertThat(again.created()).isEmpty();
    assertThat(again.skipped())
        .filteredOn(s -> !s.reason().equals("server_scope"))
        .allMatch(s -> s.reason().equals("exists"));
    assertThat(again.skipped()).extracting(TemplateService.Skipped::key).contains("error_rate");
  }

  @Test
  @DisplayName(
      "T-SV-033 template difference: a stored rule made from template version 1 has no difference"
          + " to the current templates and shows the changed fields against a version 2 of its"
          + " template")
  void templateDifference() throws Exception {
    AgentTokens.IssuedToken token = stack.app("tmpl-diff");
    stack.engine().housekeepingNow();
    UUID ruleId = stack.templateRule(token.appId(), "p95_latency");
    AlertRuleService rules = stack.server.bean(AlertRuleService.class);
    AlertRuleService.RuleDetail detail = rules.get(ruleId);
    assertThat(detail.rule().spec().templateVersion()).isEqualTo(1);
    assertThat(detail.templateDiff()).isEmpty();
    assertThat(detail.history()).extracting(h -> h.version()).containsExactly(1);

    Template current = DefaultTemplates.standard().get("p95_latency").orElseThrow();
    RuleSpec base = DefaultTemplates.instantiate(current, current.parts().get(0), 1, "prod");
    RuleSpec v2 =
        RuleCodec.parse(RuleCodec.toJson(base).put("window", "2m").put("min_samples", 30));
    List<Template> bumped = new ArrayList<>();
    for (Template t : DefaultTemplates.standard().all()) {
      bumped.add(
          t.key().equals("p95_latency")
              ? new Template(
                  t.key(), t.name(), 2, t.appliesWhen(), List.of(new Part(Scope.APP, v2)))
              : t);
    }
    TemplateDiff.Diff diff =
        TemplateDiff.of(detail.rule().spec(), new DefaultTemplates(bumped)).orElseThrow();
    assertThat(diff.templateVersion()).isEqualTo(2);
    assertThat(diff.changes())
        .extracting(TemplateDiff.Change::field)
        .containsExactly("window", "min_samples");
    assertThat(Duration.ofMinutes(1)).isEqualTo(detail.rule().spec().window());
  }
}
