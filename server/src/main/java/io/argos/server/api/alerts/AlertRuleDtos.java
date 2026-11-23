/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.alerts;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.argos.server.alert.AlertRuleService;
import io.argos.server.alert.rule.RuleCodec;
import io.argos.server.alert.rule.RuleSpec;
import io.argos.server.alert.templates.DefaultTemplates;
import io.argos.server.alert.templates.TemplateDiff;
import io.argos.server.api.openapi.ArgosUntrusted;
import io.argos.server.storage.postgres.alert.rule.AlertRuleRepository.HistoryRow;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Bodies of {@code /alerts/rules*} and {@code /alerts/templates*} (14 §4.13 rule schema). A rule
 * request is read as JSON and validated by {@link RuleCodec} (unknown fields and wrong types answer
 * {@code 400 invalid_request} with one detail per field); the {@code *Request} records here only
 * document it. Responses carry the canonical rule JSON ({@link RuleCodec#toJson}) with the
 * read-only fields, which a client may send back unchanged ({@code RuleCodec.READ_ONLY_FIELDS}).
 * Request and response records are separate (a component schema is either a request or a response).
 */
final class AlertRuleDtos {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private AlertRuleDtos() {}

  // --- requests (documentation of the JSON RuleCodec reads) -----------------------------------

  /** {@code target} of a rule request. */
  @Schema(name = "AlertRuleTargetRequest")
  record TargetRequest(
      @Schema(description = "The application; null only for server rules") @JsonProperty("app_id")
          @Nullable Integer appId,
      @Schema(description = "The environment, or null for every environment of the application")
          @Nullable String env,
      @Schema(
              requiredMode = RequiredMode.REQUIRED,
              allowableValues = {"app", "instance", "service", "ext_host", "server"})
          String type,
      @Schema(description = "Instance ids, service or host keys (decimal); empty: all, at most 100")
          @Nullable List<String> ids) {}

  /** {@code condition} of a rule request. */
  @Schema(name = "AlertRuleConditionRequest")
  record ConditionRequest(
      @Schema(
              requiredMode = RequiredMode.REQUIRED,
              allowableValues = {">", ">=", "<", "<=", "==", "!="})
          String operator,
      @Nullable Double warn,
      @Nullable Double critical) {}

  /** {@code recover} of a rule request. */
  @Schema(name = "AlertRuleRecoverRequest")
  record RecoverRequest(
      @Nullable Double threshold,
      @Schema(description = "Duration such as 2m; null: the condition false for for/2")
          @JsonProperty("for")
          @Nullable String forDuration) {}

  /** One {@code active_time} slot of a rule request. */
  @Schema(name = "AlertRuleActiveTimeRequest")
  record ActiveTimeRequest(
      @Schema(requiredMode = RequiredMode.REQUIRED, description = "Mon, Tue, ... Sun")
          List<String> days,
      @Schema(requiredMode = RequiredMode.REQUIRED, description = "HH:mm") String from,
      @Schema(requiredMode = RequiredMode.REQUIRED, description = "HH:mm or 24:00") String to) {}

  /** One {@code mute_schedules} entry of a rule request. */
  @Schema(name = "AlertRuleMuteScheduleRequest")
  record MuteScheduleRequest(
      @Schema(requiredMode = RequiredMode.REQUIRED, description = "5-field cron") String cron,
      @Schema(requiredMode = RequiredMode.REQUIRED, description = "Duration such as 2h")
          String duration,
      @Nullable String reason) {}

  /** {@code channels} of a rule request. */
  @Schema(name = "AlertRuleChannelsRequest")
  record ChannelsRequest(@Nullable List<String> warn, @Nullable List<String> critical) {}

  /**
   * A rule as {@code POST} and {@code PUT /alerts/rules} take it (14 §4.13). Durations are texts
   * such as {@code 30s}, {@code 5m}, {@code 1h}. Template variables of {@code message_template}:
   * app, env, instance, service, host, metric, value, threshold, severity, duration, started_at,
   * link, runbook, rule; any other answers {@code 422 template_variable_forbidden} (SEC-39).
   */
  @Schema(name = "AlertRuleRequest")
  record RuleRequest(
      @Schema(requiredMode = RequiredMode.REQUIRED, maxLength = 128) String name,
      @Nullable Boolean enabled,
      @JsonProperty("template_key") @Nullable String templateKey,
      @JsonProperty("template_version") @Nullable Integer templateVersion,
      @Schema(requiredMode = RequiredMode.REQUIRED) TargetRequest target,
      @Schema(allowableValues = {"HTTP", "BATCH"}) @Nullable String kind,
      @Schema(requiredMode = RequiredMode.REQUIRED, description = "An F-S31 metric") String metric,
      @Nullable String window,
      @JsonProperty("for") @Nullable String forDuration,
      @Schema(allowableValues = {"app", "instance"}) @JsonProperty("evaluation_unit")
          @Nullable String evaluationUnit,
      @Schema(requiredMode = RequiredMode.REQUIRED) ConditionRequest condition,
      @Nullable RecoverRequest recover,
      @JsonProperty("min_samples") @Nullable Integer minSamples,
      @Schema(allowableValues = {"ignore", "warn", "critical"}) @JsonProperty("no_data")
          @Nullable String noData,
      @JsonProperty("warmup_grace") @Nullable String warmupGrace,
      @JsonProperty("active_time") @Nullable List<ActiveTimeRequest> activeTime,
      @JsonProperty("mute_schedules") @Nullable List<MuteScheduleRequest> muteSchedules,
      @Nullable ChannelsRequest channels,
      @JsonProperty("renotify_interval") @Nullable String renotifyInterval,
      @JsonProperty("runbook_url") @Nullable String runbookUrl,
      @Schema(maxLength = 2048) @JsonProperty("message_template")
          @Nullable String messageTemplate) {}

  /** {@code POST /alerts/rules/preview}. */
  @Schema(name = "AlertRulePreviewRequest")
  record PreviewRequest(
      @Schema(requiredMode = RequiredMode.REQUIRED, description = "The rule (need not be saved)")
          RuleRequest rule,
      @Schema(minimum = "1", maximum = "7", description = "Days back from now (default 7)")
          @Nullable Integer days) {}

  /** {@code POST /alerts/rules/{ruleId}/clone}. */
  @Schema(name = "AlertRuleCloneRequest")
  record CloneRequest(
      @Schema(requiredMode = RequiredMode.REQUIRED) Integer app,
      @Schema(description = "The environment, or null: every environment") @Nullable String env) {}

  /** {@code POST /alerts/templates/apply}. */
  @Schema(name = "AlertTemplatesApplyRequest")
  record ApplyRequest(
      @Schema(requiredMode = RequiredMode.REQUIRED) Integer app,
      @Schema(description = "The environment; default: the application's") @Nullable String env,
      @Schema(description = "Template keys; default: every application template")
          @Nullable List<String> keys) {}

  // --- responses ------------------------------------------------------------------------------

  /** {@code target} of a rule. */
  @Schema(name = "AlertRuleTarget")
  record Target(
      @JsonProperty("app_id") @Nullable Integer appId,
      @ArgosUntrusted @Nullable String env,
      @Schema(allowableValues = {"app", "instance", "service", "ext_host", "server"}) String type,
      List<String> ids) {}

  /** {@code condition} of a rule. */
  @Schema(name = "AlertRuleCondition")
  record Condition(
      @Schema(allowableValues = {">", ">=", "<", "<=", "==", "!="}) String operator,
      @Nullable Double warn,
      @Nullable Double critical) {}

  /** {@code recover} of a rule. */
  @Schema(name = "AlertRuleRecover")
  record Recover(@Nullable Double threshold, @JsonProperty("for") @Nullable String forDuration) {}

  /** One {@code active_time} slot. */
  @Schema(name = "AlertRuleActiveTime")
  record ActiveTime(List<String> days, String from, String to) {}

  /** One {@code mute_schedules} entry. */
  @Schema(name = "AlertRuleMuteSchedule")
  record MuteSchedule(String cron, String duration, @ArgosUntrusted @Nullable String reason) {}

  /** {@code channels} of a rule. */
  @Schema(name = "AlertRuleChannels")
  record Channels(List<String> warn, List<String> critical) {}

  /** A rule (14 §4.13 rule schema) with its id and version. */
  @Schema(name = "AlertRule")
  record Rule(
      @JsonProperty("rule_id") String ruleId,
      int version,
      @JsonProperty("created_by") @Nullable String createdBy,
      @JsonProperty("created_at") Instant createdAt,
      @JsonProperty("updated_at") Instant updatedAt,
      @ArgosUntrusted String name,
      boolean enabled,
      @JsonProperty("template_key") @Nullable String templateKey,
      @JsonProperty("template_version") @Nullable Integer templateVersion,
      Target target,
      @Schema(allowableValues = {"HTTP", "BATCH"}) String kind,
      @Schema(
              description =
                  "An F-S31 metric: one of the AlertRuleRef.metric values (the closed list there;"
                      + " here a plain string so that the 14 §4.13 contract stays compatible)")
          String metric,
      String window,
      @JsonProperty("for") String forDuration,
      @Schema(allowableValues = {"app", "instance"}) @JsonProperty("evaluation_unit")
          String evaluationUnit,
      Condition condition,
      @Nullable Recover recover,
      @JsonProperty("min_samples") int minSamples,
      @Schema(allowableValues = {"ignore", "warn", "critical"}) @JsonProperty("no_data")
          String noData,
      @JsonProperty("warmup_grace") String warmupGrace,
      @JsonProperty("active_time") List<ActiveTime> activeTime,
      @JsonProperty("mute_schedules") List<MuteSchedule> muteSchedules,
      Channels channels,
      @JsonProperty("renotify_interval") String renotifyInterval,
      @JsonProperty("runbook_url") @ArgosUntrusted @Nullable String runbookUrl,
      @JsonProperty("message_template") @ArgosUntrusted @Nullable String messageTemplate) {

    static Rule of(AlertRuleService.RuleView v) {
      ObjectNode o = RuleCodec.toJson(v.spec());
      o.put("rule_id", v.id().toString());
      o.put("version", v.version());
      if (v.createdBy() == null) {
        o.putNull("created_by");
      } else {
        o.put("created_by", v.createdBy().toString());
      }
      o.put("created_at", AlertViews.millis(v.createdAt()).toString());
      o.put("updated_at", AlertViews.millis(v.updatedAt()).toString());
      return JSON.treeToValue(o, Rule.class);
    }
  }

  /** The rule fields of a template part (no id). */
  @Schema(name = "AlertRuleDefaults")
  record Defaults(
      @ArgosUntrusted String name,
      boolean enabled,
      @JsonProperty("template_key") @Nullable String templateKey,
      @JsonProperty("template_version") @Nullable Integer templateVersion,
      Target target,
      @Schema(allowableValues = {"HTTP", "BATCH"}) String kind,
      String metric,
      String window,
      @JsonProperty("for") String forDuration,
      @Schema(allowableValues = {"app", "instance"}) @JsonProperty("evaluation_unit")
          String evaluationUnit,
      Condition condition,
      @Nullable Recover recover,
      @JsonProperty("min_samples") int minSamples,
      @Schema(allowableValues = {"ignore", "warn", "critical"}) @JsonProperty("no_data")
          String noData,
      @JsonProperty("warmup_grace") String warmupGrace,
      @JsonProperty("active_time") List<ActiveTime> activeTime,
      @JsonProperty("mute_schedules") List<MuteSchedule> muteSchedules,
      Channels channels,
      @JsonProperty("renotify_interval") String renotifyInterval,
      @JsonProperty("runbook_url") @Nullable String runbookUrl,
      @JsonProperty("message_template") @Nullable String messageTemplate) {

    /**
     * The rule of a template part with the template's key and version, as a rule made from it
     * records them (ADR-0010 C-228 (6); {@link DefaultTemplates#instantiate}).
     */
    static Defaults of(DefaultTemplates.Template t, RuleSpec spec) {
      ObjectNode o = RuleCodec.toJson(spec);
      o.put("template_key", t.key());
      o.put("template_version", t.version());
      return JSON.treeToValue(o, Defaults.class);
    }
  }

  /**
   * One version of a rule's history.
   *
   * @param version the version
   * @param changedBy the user id, null for the server
   * @param changedAt the time of the change
   * @param changedByName that user's name (WO-38), null for the server or a deleted user
   */
  @Schema(name = "AlertRuleHistoryEntry")
  record HistoryEntry(
      int version,
      @JsonProperty("changed_by") @Nullable String changedBy,
      @JsonProperty("changed_at") @Nullable Instant changedAt,
      @Schema(description = "The user's name; null for the server or a deleted user")
          @JsonProperty("changed_by_name")
          @ArgosUntrusted
          @Nullable String changedByName) {

    static HistoryEntry of(HistoryRow h) {
      return new HistoryEntry(
          h.version(),
          h.changedBy() == null ? null : h.changedBy().toString(),
          AlertViews.millis(h.changedAt()),
          h.changedByName());
    }
  }

  /** One field that differs from the template. */
  @Schema(name = "AlertTemplateDiffChange")
  record DiffChange(
      String field,
      @Schema(description = "The rule's value (JSON)") @Nullable Object rule,
      @Schema(description = "The template's value (JSON)") @Nullable Object template) {}

  /** The difference of a rule to the current version of its template. */
  @Schema(name = "AlertTemplateDiff")
  record Diff(
      @JsonProperty("template_key") String templateKey,
      @JsonProperty("rule_version") int ruleVersion,
      @JsonProperty("template_version") int templateVersion,
      List<DiffChange> changes) {

    static Diff of(TemplateDiff.Diff d) {
      return new Diff(
          d.templateKey(),
          d.ruleVersion(),
          d.templateVersion(),
          d.changes().stream()
              .map(c -> new DiffChange(c.field(), plain(c.rule()), plain(c.template())))
              .toList());
    }
  }

  /** {@code GET /alerts/rules/{ruleId}}: the rule, its newest versions and its template diff. */
  @Schema(name = "AlertRuleDetail")
  record Detail(
      Rule rule,
      List<HistoryEntry> history,
      @JsonProperty("template_diff") @Nullable Diff templateDiff) {

    static Detail of(AlertRuleService.RuleDetail d) {
      return new Detail(
          Rule.of(d.rule()),
          d.history().stream().map(HistoryEntry::of).toList(),
          d.templateDiff().map(Diff::of).orElse(null));
    }
  }

  /** One part (rule) of a template. */
  @Schema(name = "AlertTemplatePart")
  record TemplatePart(@Schema(allowableValues = {"app", "server"}) String scope, Defaults rule) {}

  /** A default template (01 Appendix A). */
  @Schema(name = "AlertTemplate")
  record Template(
      String key,
      String name,
      int version,
      @Schema(allowableValues = {"always", "batch", "server"}) @JsonProperty("applies_when")
          String appliesWhen,
      @Schema(description = "The rules the template creates (two for a template of two metrics)")
          List<TemplatePart> defaults) {

    static Template of(DefaultTemplates.Template t) {
      return new Template(
          t.key(),
          t.name(),
          t.version(),
          t.appliesWhen(),
          t.parts().stream()
              .map(
                  p ->
                      new TemplatePart(
                          p.scope().name().toLowerCase(java.util.Locale.ROOT),
                          Defaults.of(t, p.spec())))
              .toList());
    }
  }

  /** A template that an apply did not create. */
  @Schema(name = "AlertTemplateSkipped")
  record Skipped(
      String key,
      @Schema(
              allowableValues = {
                "exists",
                "server_scope",
                "no_batch_transactions",
                "unknown_template"
              })
          String reason) {}

  /** {@code POST /alerts/templates/apply}. */
  @Schema(name = "AlertTemplatesApplied")
  record Applied(List<String> created, List<Skipped> skipped) {}

  /** One would-be firing of a preview. */
  @Schema(name = "AlertPreviewFiring")
  record PreviewFiring(
      @JsonProperty("ts_start") Instant tsStart,
      @JsonProperty("ts_end") Instant tsEnd,
      @JsonProperty("peak_value") double peakValue) {}

  /** The metric per bucket of a preview. */
  @Schema(name = "AlertPreviewSeries")
  record PreviewSeries(List<Instant> ts, List<@Nullable Double> value) {}

  /** {@code POST /alerts/rules/preview}. */
  @Schema(name = "AlertRulePreview")
  record Preview(
      List<PreviewFiring> firings,
      int count,
      @JsonProperty("max_duration_s") long maxDurationS,
      @JsonProperty("step_s") long stepS,
      PreviewSeries series) {}

  private static Object plain(JsonNode node) {
    return node == null || node.isNull() ? null : JSON.treeToValue(node, Object.class);
  }
}
