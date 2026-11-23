/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.alerts;

import io.argos.server.alert.AlertRuleService;
import io.argos.server.alert.RulePreviewService;
import io.argos.server.alert.templates.DefaultTemplates;
import io.argos.server.alert.templates.TemplateService;
import io.argos.server.api.Envelope;
import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import io.argos.server.api.openapi.ArgosAudit;
import io.argos.server.api.openapi.ArgosLimits;
import io.argos.server.api.openapi.ArgosSource;
import io.argos.server.api.query.QueryLimits;
import io.argos.server.audit.Actor;
import io.argos.server.audit.AuditEvent;
import io.argos.server.audit.AuditResult;
import io.argos.server.audit.AuditService;
import io.argos.server.audit.Target;
import io.argos.server.auth.ArgosPrincipal;
import io.argos.server.auth.ObjectAccessPolicy;
import io.argos.server.auth.RequiresRole;
import io.argos.server.auth.Role;
import io.argos.server.storage.query.lookup.AppDirectory;
import io.argos.server.web.security.RateLimitBucket;
import io.argos.server.web.security.RateLimited;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * Alert rules and default templates: {@code /api/v1/alerts/rules*} and {@code
 * /api/v1/alerts/templates*} (14 §4.13, 12 §5, F-S06, F-S24). Viewers read; operators create,
 * change, delete, enable, disable, clone, preview and apply templates (14 §7.1). Every change is
 * audited (16 §11.1: {@code RULE_CREATED}, {@code RULE_UPDATED}, {@code RULE_DELETED}, {@code
 * RULE_DISABLED}) and reloads the engine.
 */
@RestController
@RequestMapping("/api/v1/alerts")
public class AlertRulesController {

  /** Bytes a rule body may have (14 §7.2). */
  static final int MAX_RULE_BODY_BYTES = QueryLimits.MAX_JSON_BODY_BYTES;

  private final AlertRuleService rules;
  private final RulePreviewService previews;
  private final DefaultTemplates templates;
  private final AppDirectory apps;
  private final AuditService audit;

  public AlertRulesController(
      AlertRuleService rules,
      RulePreviewService previews,
      DefaultTemplates templates,
      AppDirectory apps,
      AuditService audit) {
    this.rules = rules;
    this.previews = previews;
    this.templates = templates;
    this.apps = apps;
    this.audit = audit;
  }

  /** The rules, newest first. */
  @GetMapping("/rules")
  @RequiresRole(Role.VIEWER)
  @Operation(
      operationId = "alerts_rules_list",
      summary = "Alert rules",
      description =
          "The rules that are not deleted, newest first, at most 1,000 (meta.truncated at the"
              + " limit). Without app every rule, server self rules included; env matches the"
              + " rules of that environment and those of every environment.")
  @ArgosLimits(rowsMax = 1_000)
  @ArgosSource({"postgres:alert_rules"})
  public Envelope<List<AlertRuleDtos.Rule>> list(
      @Parameter(description = "Application id") @RequestParam(value = "app", required = false)
          Integer app,
      @Parameter(description = "Environment") @RequestParam(value = "env", required = false)
          String env,
      @Parameter(description = "Only enabled (true) or only disabled (false) rules")
          @RequestParam(value = "enabled", required = false)
          Boolean enabled) {
    List<AlertRuleService.RuleView> all =
        rules.list(app, blankToNull(QueryLimits.text("env", env)), enabled);
    List<AlertRuleDtos.Rule> out = all.stream().map(AlertRuleDtos.Rule::of).toList();
    return Envelope.of(out, out.size() >= 1_000);
  }

  /** Creates a rule. */
  @PostMapping("/rules")
  @ResponseStatus(HttpStatus.CREATED)
  @RequiresRole(Role.OPERATOR)
  @ArgosAudit(AuditEvent.RULE_CREATED)
  @Operation(
      operationId = "alerts_rules_create",
      summary = "Create an alert rule",
      description =
          "400 invalid_request with one detail per invalid field (unknown fields included), 422"
              + " template_variable_forbidden for a message template variable outside the allowed"
              + " set (SEC-39). The application and the channels must exist.")
  public Envelope<AlertRuleDtos.Rule> create(
      @io.swagger.v3.oas.annotations.parameters.RequestBody(
              required = true,
              content =
                  @Content(schema = @Schema(implementation = AlertRuleDtos.RuleRequest.class)))
          @RequestBody(required = false)
          JsonNode body,
      HttpServletRequest request) {
    checkBody(body, request);
    ArgosPrincipal p = principal();
    AlertRuleService.RuleView created = rules.create(body, p.userId());
    record(AuditEvent.RULE_CREATED, request, created, null);
    return Envelope.of(AlertRuleDtos.Rule.of(created));
  }

  /** A rule with its history summary and template difference. */
  @GetMapping("/rules/{ruleId}")
  @RequiresRole(Role.VIEWER)
  @Operation(
      operationId = "alerts_rules_get",
      summary = "One alert rule",
      description =
          "The rule, its 20 newest versions (alert_rule_history) and, when its template has a"
              + " newer version than the rule was made from, the differing fields (F-S24).")
  @ArgosLimits(rowsMax = AlertRuleService.HISTORY_ROWS)
  @ArgosSource({"postgres:alert_rules", "postgres:alert_rule_history"})
  public Envelope<AlertRuleDtos.Detail> get(@PathVariable("ruleId") String ruleId) {
    return Envelope.of(AlertRuleDtos.Detail.of(rules.get(id(ruleId, "ruleId"))));
  }

  /** Replaces a rule. */
  @PutMapping("/rules/{ruleId}")
  @RequiresRole(Role.OPERATOR)
  @ArgosAudit(AuditEvent.RULE_UPDATED)
  @Operation(
      operationId = "alerts_rules_update",
      summary = "Replace an alert rule",
      description =
          "The whole rule (read-only fields such as rule_id and version are ignored). Channels"
              + " are chosen by id; a channel's URL never changes here.")
  public Envelope<AlertRuleDtos.Rule> update(
      @PathVariable("ruleId") String ruleId,
      @io.swagger.v3.oas.annotations.parameters.RequestBody(
              required = true,
              content =
                  @Content(schema = @Schema(implementation = AlertRuleDtos.RuleRequest.class)))
          @RequestBody(required = false)
          JsonNode body,
      HttpServletRequest request) {
    UUID id = id(ruleId, "ruleId");
    checkBody(body, request);
    AlertRuleService.RuleView updated = rules.update(id, body, principal().userId());
    record(AuditEvent.RULE_UPDATED, request, updated, null);
    return Envelope.of(AlertRuleDtos.Rule.of(updated));
  }

  /** Deletes a rule (kept for its history). */
  @DeleteMapping("/rules/{ruleId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @RequiresRole(Role.OPERATOR)
  @ArgosAudit(AuditEvent.RULE_DELETED)
  @Operation(
      operationId = "alerts_rules_delete",
      summary = "Delete an alert rule",
      description =
          "Soft delete: the rule leaves the lists and the engine (its active alerts resolve), its"
              + " events stay readable for their retention.")
  public void delete(@PathVariable("ruleId") String ruleId, HttpServletRequest request) {
    UUID id = id(ruleId, "ruleId");
    rules.delete(id, principal().userId());
    audit.record(
        AuditEvent.RULE_DELETED,
        actor(request),
        new Target("rule", id.toString(), null),
        AuditResult.success());
  }

  /** Enables a rule. */
  @PostMapping("/rules/{ruleId}/enable")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @RequiresRole(Role.OPERATOR)
  @ArgosAudit(AuditEvent.RULE_UPDATED)
  @Operation(operationId = "alerts_rules_enable", summary = "Enable an alert rule")
  public void enable(@PathVariable("ruleId") String ruleId, HttpServletRequest request) {
    AlertRuleService.RuleView v =
        rules.setEnabled(id(ruleId, "ruleId"), true, principal().userId());
    record(AuditEvent.RULE_UPDATED, request, v, Map.of("enabled", true));
  }

  /** Disables a rule. */
  @PostMapping("/rules/{ruleId}/disable")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @RequiresRole(Role.OPERATOR)
  @ArgosAudit(AuditEvent.RULE_DISABLED)
  @Operation(operationId = "alerts_rules_disable", summary = "Disable an alert rule")
  public void disable(@PathVariable("ruleId") String ruleId, HttpServletRequest request) {
    AlertRuleService.RuleView v =
        rules.setEnabled(id(ruleId, "ruleId"), false, principal().userId());
    record(AuditEvent.RULE_DISABLED, request, v, Map.of("enabled", false));
  }

  /** Copies a rule to another application and environment. */
  @PostMapping("/rules/{ruleId}/clone")
  @ResponseStatus(HttpStatus.CREATED)
  @RequiresRole(Role.OPERATOR)
  @ArgosAudit(AuditEvent.RULE_CREATED)
  @Operation(
      operationId = "alerts_rules_clone",
      summary = "Clone an alert rule",
      description =
          "A copy of the rule for another application and environment (not server rules).")
  public Envelope<AlertRuleDtos.Rule> cloneRule(
      @PathVariable("ruleId") String ruleId,
      @RequestBody(required = false) AlertRuleDtos.CloneRequest body,
      HttpServletRequest request) {
    UUID id = id(ruleId, "ruleId");
    if (body == null || body.app() == null) {
      throw new ApiException(ErrorCode.INVALID_REQUEST).detail("app", "required");
    }
    String env = blankToNull(QueryLimits.text("env", body.env()));
    AlertRuleService.RuleView copy = rules.cloneRule(id, body.app(), env, principal().userId());
    record(AuditEvent.RULE_CREATED, request, copy, Map.of("cloned_from", id.toString()));
    return Envelope.of(AlertRuleDtos.Rule.of(copy));
  }

  /** Replays a rule over the last days. */
  @PostMapping("/rules/preview")
  @RequiresRole(Role.OPERATOR)
  @RateLimited(RateLimitBucket.HEAVY)
  @ArgosAudit(none = "A preview reads the rollups and changes nothing")
  @Operation(
      operationId = "alerts_rules_preview",
      summary = "Preview an alert rule",
      description =
          "The rule's metric over the last days (default and at most 7) from the minute rollups,"
              + " in consecutive buckets of step_s (the rule window rounded up to whole minutes,"
              + " at least days × 1440 / 336 minutes, so short windows are smoothed), and the"
              + " firings its warn (or critical) threshold would have caused: a run of breaching"
              + " buckets (with at least min_samples requests for request metrics) lasting at"
              + " least for. Metrics outside the rollups (active transactions, server self"
              + " metrics, instance state, batch elapsed time) answer 400 with"
              + " metric/preview_unsupported; a service, instance or host rule needs one target"
              + " id. Heavy rate-limit bucket (14 §2.7).")
  @ArgosLimits(rangeMax = "7d", rowsMax = RulePreviewService.MAX_POINTS, timeoutS = 25)
  @ArgosSource({"instance_stat_1m", "service_stat_1m", "ext_stat_1m", "metric_1m"})
  public Envelope<AlertRuleDtos.Preview> preview(
      @io.swagger.v3.oas.annotations.parameters.RequestBody(
              required = true,
              content =
                  @Content(schema = @Schema(implementation = AlertRuleDtos.PreviewRequest.class)))
          @RequestBody(required = false)
          JsonNode body,
      HttpServletRequest request) {
    checkBody(body, request);
    for (String field : body.propertyNames()) {
      if (!field.equals("rule") && !field.equals("days")) {
        throw new ApiException(ErrorCode.INVALID_REQUEST, "The preview request is invalid")
            .detail(field, "unknown_field");
      }
    }
    JsonNode days = body.get("days");
    int d = RulePreviewService.MAX_DAYS;
    if (days != null && !days.isNull()) {
      if (!days.isIntegralNumber()) {
        throw new ApiException(ErrorCode.INVALID_REQUEST, "days is 1 to 7")
            .detail("days", "invalid_type");
      }
      d = days.asInt();
    }
    JsonNode rule = body.get("rule");
    if (rule == null || !rule.isObject()) {
      throw new ApiException(ErrorCode.INVALID_REQUEST, "The rule is missing")
          .detail("rule", "required");
    }
    RulePreviewService.Preview p = previews.preview(rule, d);
    return Envelope.of(
        new AlertRuleDtos.Preview(
            p.firings().stream()
                .map(
                    f ->
                        new AlertRuleDtos.PreviewFiring(
                            AlertViews.millis(f.start()),
                            AlertViews.millis(f.end()),
                            f.peakValue()))
                .toList(),
            p.firings().size(),
            p.maxDurationS(),
            p.step().toSeconds(),
            new AlertRuleDtos.PreviewSeries(p.ts(), p.values())));
  }

  /** The default templates. */
  @GetMapping("/templates")
  @RequiresRole(Role.VIEWER)
  @Operation(
      operationId = "alerts_templates_list",
      summary = "Default alert templates",
      description =
          "The 14 templates of 01 Appendix A with their version and the rules they create"
              + " (scope app: per application; server: once for the server).")
  @ArgosSource({"memory:templates"})
  public Envelope<List<AlertRuleDtos.Template>> templates() {
    return Envelope.of(templates.all().stream().map(AlertRuleDtos.Template::of).toList());
  }

  /** Applies templates to an application. */
  @PostMapping("/templates/apply")
  @RequiresRole(Role.OPERATOR)
  @ArgosAudit(AuditEvent.RULE_CREATED)
  @Operation(
      operationId = "alerts_templates_apply",
      summary = "Apply default templates",
      description =
          "Creates the application rules of the templates (default: every application"
              + " template) for the application; a template whose rule exists (deleted ones"
              + " included), a server template, an unknown key and, without keys, the batch"
              + " template of an application without BATCH transactions are skipped with the"
              + " reason.")
  public Envelope<AlertRuleDtos.Applied> apply(
      @RequestBody(required = false) AlertRuleDtos.ApplyRequest body, HttpServletRequest request) {
    if (body == null || body.app() == null) {
      throw new ApiException(ErrorCode.INVALID_REQUEST).detail("app", "required");
    }
    if (body.keys() != null) {
      QueryLimits.array("keys", body.keys(), QueryLimits.MAX_ARRAY_VALUES);
    }
    String env = blankToNull(QueryLimits.text("env", body.env()));
    if (env == null) {
      env = apps.app(body.app()).map(AppDirectory.AppInfo::env).orElse(null);
    }
    TemplateService.ApplyResult r =
        rules.applyTemplates(body.app(), env, body.keys(), principal().userId());
    Actor actor = actor(request);
    for (UUID created : r.created()) {
      audit.record(
          AuditEvent.RULE_CREATED,
          actor,
          new Target("rule", created.toString(), null),
          AuditResult.success("template"),
          null,
          Map.of("app_id", body.app()));
    }
    return Envelope.of(
        new AlertRuleDtos.Applied(
            r.created().stream().map(UUID::toString).toList(),
            r.skipped().stream()
                .map(s -> new AlertRuleDtos.Skipped(s.key(), s.reason()))
                .toList()));
  }

  private void record(
      AuditEvent event,
      HttpServletRequest request,
      AlertRuleService.RuleView rule,
      Map<String, ?> extra) {
    Map<String, Object> after = new LinkedHashMap<>();
    after.put("version", rule.version());
    after.put("metric", rule.spec().metric());
    if (rule.spec().target().appId() != null) {
      after.put("app_id", rule.spec().target().appId());
    }
    if (extra != null) {
      after.putAll(extra);
    }
    audit.record(
        event,
        actor(request),
        new Target("rule", rule.id().toString(), rule.spec().name()),
        AuditResult.success(),
        null,
        after);
  }

  private static void checkBody(JsonNode body, HttpServletRequest request) {
    if (request.getContentLengthLong() > MAX_RULE_BODY_BYTES) {
      throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE, "The body exceeds 256 KB")
          .detail("body", "too_large");
    }
    if (body == null || !body.isObject()) {
      throw new ApiException(ErrorCode.INVALID_REQUEST, "The body must be a JSON object")
          .detail("body", "required");
    }
  }

  static UUID id(String text, String field) {
    return ObjectAccessPolicy.requireUuidV4(text, field);
  }

  static ArgosPrincipal principal() {
    return ArgosPrincipal.current().orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
  }

  static Actor actor(HttpServletRequest request) {
    return Actor.user(principal(), request);
  }

  static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }
}
