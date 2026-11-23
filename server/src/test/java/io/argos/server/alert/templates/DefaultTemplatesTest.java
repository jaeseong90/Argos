/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert.templates;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.alert.metric.AlertMetric;
import io.argos.server.alert.rule.RuleSpec;
import io.argos.server.alert.templates.DefaultTemplates.Part;
import io.argos.server.alert.templates.DefaultTemplates.Scope;
import io.argos.server.alert.templates.DefaultTemplates.Template;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The 14 default templates of 01 Appendix A as valid rules, and the template difference. */
@Tag("req:F-S24")
class DefaultTemplatesTest {

  @Test
  @DisplayName(
      "T-SV-033 14 templates in Appendix A order; 10 for applications (batch only with BATCH"
          + " transactions), 4 server self rules; every part is a valid rule with the 3m warm-up")
  void fourteenTemplates() {
    List<Template> all = DefaultTemplates.standard().all();
    assertThat(all)
        .extracting(Template::key)
        .containsExactly(
            "instance_down",
            "error_rate",
            "p95_latency",
            "active_transactions",
            "connection_pool",
            "heap",
            "gc_pause",
            "ext_timeout",
            "batch_delay",
            "collection",
            "server_disk",
            "ingest_lag",
            "channel_failure",
            "cert_token_expiry");
    assertThat(all.stream().filter(t -> t.appliesWhen().equals("server"))).hasSize(4);
    assertThat(all.stream().filter(t -> t.appliesWhen().equals("batch"))).hasSize(1);
    for (Template t : all) {
      assertThat(t.version()).isEqualTo(1);
      for (Part p : t.parts()) {
        RuleSpec r = DefaultTemplates.instantiate(t, p, p.scope() == Scope.APP ? 5 : null, "prod");
        assertThat(r.templateKey()).isEqualTo(t.key());
        assertThat(r.templateVersion()).isEqualTo(1);
        assertThat(r.warmupGrace()).isEqualTo(Duration.ofMinutes(3));
        assertThat(AlertMetric.of(r.metric())).isNotNull();
        if (AlertMetric.of(r.metric()).request()) {
          assertThat(r.minSamples()).as(t.key()).isPositive();
        }
      }
    }
  }

  @Test
  @DisplayName(
      "T-SV-033 Appendix A thresholds: error rate 5%/20% 1m/5m min 20 recover 2% 2m; p95 3s/10s; GC"
          + " 10%/30%; ext timeouts 10%/50% min 10; ingest lag 30s recover 5s; channel failure"
          + " critical at once")
  void thresholds() {
    Map<String, RuleSpec> byMetric = new HashMap<>();
    for (Template t : DefaultTemplates.standard().all()) {
      for (Part p : t.parts()) {
        byMetric.put(p.spec().metric(), p.spec());
      }
    }
    RuleSpec err = byMetric.get("error_rate");
    assertThat(err.condition().warn()).isEqualTo(0.05);
    assertThat(err.condition().critical()).isEqualTo(0.20);
    assertThat(err.window()).isEqualTo(Duration.ofMinutes(1));
    assertThat(err.forDuration()).isEqualTo(Duration.ofMinutes(5));
    assertThat(err.minSamples()).isEqualTo(20);
    assertThat(err.recover().threshold()).isEqualTo(0.02);
    assertThat(err.recover().forDuration()).isEqualTo(Duration.ofMinutes(2));
    assertThat(byMetric.get("response_time_p95").condition().critical()).isEqualTo(10_000.0);
    assertThat(byMetric.get("jvm_gc_pause_ratio").condition().warn()).isEqualTo(0.10);
    assertThat(byMetric.get("ext_call_timeout_ratio").minSamples()).isEqualTo(10);
    assertThat(byMetric.get("argos_server_ingest_lag_seconds").recover().threshold())
        .isEqualTo(5.0);
    RuleSpec channel = byMetric.get("argos_notification_failures_total");
    assertThat(channel.condition().critical()).isEqualTo(1.0);
    assertThat(channel.forDuration()).isZero();
    assertThat(byMetric.get("instance_state").evaluationUnit())
        .isEqualTo(RuleSpec.EvaluationUnit.INSTANCE);
    assertThat(byMetric.get("batch_elapsed_seconds").condition().warn()).isEqualTo(1800.0);
  }

  @Test
  @DisplayName(
      "T-SV-033 template difference: a rule from version 1 shows the changed fields of version 2"
          + " (for, condition), none at the same version, never for a rule without a template")
  void templateDiff() {
    Template v1 = DefaultTemplates.standard().get("error_rate").orElseThrow();
    RuleSpec rule = DefaultTemplates.instantiate(v1, v1.parts().get(0), 5, "prod");
    assertThat(TemplateDiff.of(rule, DefaultTemplates.standard())).isEmpty();

    RuleSpec base = v1.parts().get(0).spec();
    RuleSpec changed =
        new RuleSpec(
            base.name(),
            base.enabled(),
            null,
            null,
            base.target(),
            base.kind(),
            base.metric(),
            base.window(),
            Duration.ofMinutes(3),
            base.evaluationUnit(),
            new RuleSpec.Condition(RuleSpec.Operator.GTE, 0.03, 0.20),
            base.recover(),
            base.minSamples(),
            base.noData(),
            base.warmupGrace(),
            base.activeTime(),
            base.muteSchedules(),
            base.channels(),
            base.renotifyInterval(),
            base.runbookUrl(),
            base.messageTemplate());
    List<Template> bumped = new ArrayList<>();
    for (Template t : DefaultTemplates.standard().all()) {
      bumped.add(
          t.key().equals("error_rate")
              ? new Template(
                  t.key(), t.name(), 2, t.appliesWhen(), List.of(new Part(Scope.APP, changed)))
              : t);
    }
    TemplateDiff.Diff diff = TemplateDiff.of(rule, new DefaultTemplates(bumped)).orElseThrow();
    assertThat(diff.ruleVersion()).isEqualTo(1);
    assertThat(diff.templateVersion()).isEqualTo(2);
    assertThat(diff.changes())
        .extracting(TemplateDiff.Change::field)
        .containsExactly("for", "condition");
    TemplateDiff.Change cond =
        diff.changes().stream()
            .collect(Collectors.toMap(TemplateDiff.Change::field, c -> c))
            .get("condition");
    assertThat(cond.rule().get("warn").doubleValue()).isEqualTo(0.05);
    assertThat(cond.template().get("warn").doubleValue()).isEqualTo(0.03);

    RuleSpec own =
        new RuleSpec(
            "mine",
            true,
            null,
            null,
            rule.target(),
            rule.kind(),
            rule.metric(),
            rule.window(),
            rule.forDuration(),
            rule.evaluationUnit(),
            rule.condition(),
            rule.recover(),
            rule.minSamples(),
            rule.noData(),
            rule.warmupGrace(),
            rule.activeTime(),
            rule.muteSchedules(),
            rule.channels(),
            rule.renotifyInterval(),
            null,
            null);
    assertThat(TemplateDiff.of(own, new DefaultTemplates(bumped))).isEmpty();
  }
}
