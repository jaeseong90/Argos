/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert;

import io.argos.server.alert.engine.AlertEngine;
import io.argos.server.alert.rule.RuleCodec;
import io.argos.server.alert.rule.RuleSpec;
import io.argos.server.alert.templates.TemplateDiff;
import io.argos.server.alert.templates.TemplateService;
import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import io.argos.server.storage.postgres.alert.rule.AlertRuleRepository;
import io.argos.server.storage.postgres.alert.rule.AlertRuleRepository.AlertRuleRow;
import io.argos.server.storage.postgres.alert.rule.AlertRuleRepository.HistoryRow;
import io.argos.server.storage.postgres.alert.rule.AlertRuleRepository.NewRule;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * Alert rule management behind the REST API of 14 §4.13 (WO-25; controllers are step A2's): create,
 * read, update, delete (soft), enable, disable, clone, the templates and their apply. Every change
 * validates the rule ({@link RuleCodec}: {@code 400 invalid_request}, {@code 422
 * template_variable_forbidden}), checks that the application and the channels exist ({@code 400}),
 * writes the history and reloads the engine.
 */
public class AlertRuleService {

  /** History rows a rule read returns. */
  public static final int HISTORY_ROWS = 20;

  private static final Logger LOG = LoggerFactory.getLogger(AlertRuleService.class);

  private final AlertRuleRepository rules;
  private final TemplateService templates;
  private final AlertEngine engine;

  public AlertRuleService(
      AlertRuleRepository rules, TemplateService templates, AlertEngine engine) {
    this.rules = rules;
    this.templates = templates;
    this.engine = engine;
  }

  /**
   * One rule as the API returns it.
   *
   * @param id the rule id
   * @param spec the rule
   * @param version its version
   * @param createdBy the creating user, null for the server
   * @param createdAt creation time
   * @param updatedAt last change
   */
  public record RuleView(
      UUID id, RuleSpec spec, int version, UUID createdBy, Instant createdAt, Instant updatedAt) {}

  /**
   * A rule with its history summary and template difference ({@code GET /alerts/rules/{id}}).
   *
   * @param rule the rule
   * @param history the newest versions
   * @param templateDiff the difference to its template, if its template version is older
   */
  public record RuleDetail(
      RuleView rule, List<HistoryRow> history, Optional<TemplateDiff.Diff> templateDiff) {}

  /** The rules of an application (or all), newest first, at most 1,000. */
  public List<RuleView> list(Integer appId, String env, Boolean enabled) {
    List<RuleView> out = new ArrayList<>();
    for (AlertRuleRow row : rules.list(appId, env, enabled)) {
      view(row).ifPresent(out::add);
    }
    return out;
  }

  /** One rule with its history and template difference. */
  public RuleDetail get(UUID id) {
    AlertRuleRow row = rules.find(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    RuleView v = view(row).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    return new RuleDetail(
        v, rules.history(id, HISTORY_ROWS), TemplateDiff.of(v.spec(), templates.templates()));
  }

  /** Creates a rule from its JSON. */
  public RuleView create(JsonNode body, UUID actor) {
    RuleSpec spec = RuleCodec.parse(body);
    checkReferences(spec);
    UUID id = UUID.randomUUID();
    rules.insert(newRule(id, spec), actor);
    reload();
    return rules.find(id).flatMap(this::view).orElseThrow();
  }

  /** Replaces a rule (channels are chosen by id; channel targets never change here, 14 §4.13). */
  public RuleView update(UUID id, JsonNode body, UUID actor) {
    if (rules.find(id).isEmpty()) {
      throw new ApiException(ErrorCode.NOT_FOUND);
    }
    RuleSpec spec = RuleCodec.parse(body);
    checkReferences(spec);
    if (rules.update(newRule(id, spec), actor).isEmpty()) {
      throw new ApiException(ErrorCode.NOT_FOUND);
    }
    reload();
    return rules.find(id).flatMap(this::view).orElseThrow();
  }

  /** Deletes a rule (kept for its history; its active alerts resolve). */
  public void delete(UUID id, UUID actor) {
    if (!rules.softDelete(id, actor)) {
      throw new ApiException(ErrorCode.NOT_FOUND);
    }
    reload();
  }

  /** Enables or disables a rule. */
  public RuleView setEnabled(UUID id, boolean enabled, UUID actor) {
    AlertRuleRow row = rules.find(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    RuleSpec spec = view(row).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)).spec();
    if (spec.enabled() != enabled) {
      rules.update(newRule(id, spec.withEnabled(enabled)), actor);
      reload();
    }
    return rules.find(id).flatMap(this::view).orElseThrow();
  }

  /** Copies a rule to another application and environment ({@code POST .../clone}). */
  public RuleView cloneRule(UUID id, int appId, String env, UUID actor) {
    AlertRuleRow row = rules.find(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    RuleSpec spec = view(row).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)).spec();
    if (spec.target().type() == RuleSpec.TargetType.SERVER) {
      throw new ApiException(ErrorCode.INVALID_REQUEST, "A server rule cannot be cloned")
          .detail("rule_id", "server_rule");
    }
    RuleSpec copy =
        RuleCodec.parse(
            RuleCodec.toJson(
                spec.withTarget(
                    new RuleSpec.Target(appId, env, spec.target().type(), spec.target().ids()))));
    checkReferences(copy);
    UUID newId = UUID.randomUUID();
    rules.insert(newRule(newId, copy), actor);
    reload();
    return rules.find(newId).flatMap(this::view).orElseThrow();
  }

  /** Applies templates to an application ({@code POST /alerts/templates/apply}). */
  public TemplateService.ApplyResult applyTemplates(
      int appId, String env, List<String> keys, UUID actor) {
    if (!rules.appExists(appId)) {
      throw new ApiException(ErrorCode.INVALID_REQUEST, "The application does not exist")
          .detail("app", "not_found");
    }
    TemplateService.ApplyResult r =
        templates.apply(appId, env, keys, engine.sawBatch(appId), actor);
    if (!r.created().isEmpty()) {
      reload();
    }
    return r;
  }

  private void checkReferences(RuleSpec spec) {
    Integer appId = spec.target().appId();
    if (appId != null && !rules.appExists(appId)) {
      throw new ApiException(ErrorCode.INVALID_REQUEST, "The alert rule is invalid")
          .detail("target.app_id", "not_found");
    }
    List<UUID> channels = spec.channels().all();
    Set<UUID> existing = rules.existingChannels(channels);
    if (existing.size() != channels.size()) {
      throw new ApiException(ErrorCode.INVALID_REQUEST, "The alert rule is invalid")
          .detail("channels", "channel_not_found");
    }
  }

  private void reload() {
    try {
      engine.reload();
    } catch (IllegalStateException e) {
      // Not running (stopping): the next start loads the rules.
      LOG.debug("The alert engine was not reloaded: {}", e.getMessage());
    }
  }

  private Optional<RuleView> view(AlertRuleRow row) {
    try {
      return Optional.of(
          new RuleView(
              row.id(),
              RuleCodec.parseStored(row.specJson()),
              row.version(),
              row.createdBy(),
              row.createdAt(),
              row.updatedAt()));
    } catch (RuntimeException e) {
      LOG.warn("Alert rule {} has an invalid stored definition", row.id());
      return Optional.empty();
    }
  }

  private static NewRule newRule(UUID id, RuleSpec spec) {
    return new NewRule(
        id,
        spec.target().appId(),
        spec.target().env(),
        spec.name(),
        spec.templateKey(),
        spec.enabled(),
        RuleCodec.toJsonText(spec));
  }
}
