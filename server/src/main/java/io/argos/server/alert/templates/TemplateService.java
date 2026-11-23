/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert.templates;

import io.argos.server.alert.rule.RuleCodec;
import io.argos.server.alert.rule.RuleSpec;
import io.argos.server.alert.templates.DefaultTemplates.Part;
import io.argos.server.alert.templates.DefaultTemplates.Scope;
import io.argos.server.alert.templates.DefaultTemplates.Template;
import io.argos.server.storage.postgres.alert.rule.AlertRuleRepository;
import io.argos.server.storage.postgres.alert.rule.AlertRuleRepository.NewRule;
import io.argos.server.storage.postgres.alert.rule.AlertSetupRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates rules from the default templates (F-S24, 12 §5.6; WO-25):
 *
 * <ul>
 *   <li>{@link #apply}: the application-scope templates for one application ({@code POST
 *       /alerts/templates/apply}; the batch template only when the application has BATCH
 *       transactions or it is asked for by key);
 *   <li>{@link #applyRecommended}: "apply recommended rules" for every application whose {@code
 *       apps.recommended_rules_applied} is false (the agent's automatic registration, an approval
 *       from the queue, an application created by SQL), marking it applied; the engine runs it at
 *       its start and every minute when {@code argos.alert.recommended-rules.enabled};
 *   <li>{@link #applyServerRules}: the server self rules once, at the first start ({@code settings}
 *       flag);
 *   <li>{@link #applyBatch}: the batch template when an application's first BATCH transaction is
 *       seen.
 * </ul>
 *
 * A template part whose rule exists for the application (deleted ones included) is skipped.
 */
public final class TemplateService {

  private static final Logger LOG = LoggerFactory.getLogger(TemplateService.class);

  private final AlertRuleRepository rules;
  private final AlertSetupRepository setup;
  private final DefaultTemplates templates;

  public TemplateService(
      AlertRuleRepository rules, AlertSetupRepository setup, DefaultTemplates templates) {
    this.rules = rules;
    this.setup = setup;
    this.templates = templates;
  }

  /** The templates. */
  public DefaultTemplates templates() {
    return templates;
  }

  /**
   * One skipped template.
   *
   * @param key the template key
   * @param reason {@code exists}, {@code server_scope}, {@code no_batch_transactions} or {@code
   *     unknown_template}
   */
  public record Skipped(String key, String reason) {}

  /**
   * The outcome of an apply.
   *
   * @param created the new rules
   * @param skipped the templates (or parts) not created
   */
  public record ApplyResult(List<UUID> created, List<Skipped> skipped) {

    /** Copies. */
    public ApplyResult {
      created = List.copyOf(created);
      skipped = List.copyOf(skipped);
    }
  }

  /**
   * Creates the application-scope template rules for one application.
   *
   * @param keys the templates, or null for every application template
   * @param hasBatch whether the application has BATCH transactions (the batch template's condition)
   * @param actor the user, or null (the server)
   */
  public ApplyResult apply(int appId, String env, List<String> keys, boolean hasBatch, UUID actor) {
    List<UUID> created = new ArrayList<>();
    List<Skipped> skipped = new ArrayList<>();
    List<Template> chosen = new ArrayList<>();
    if (keys == null) {
      chosen.addAll(templates.all());
    } else {
      for (String k : keys) {
        templates
            .get(k)
            .ifPresentOrElse(chosen::add, () -> skipped.add(new Skipped(k, "unknown_template")));
      }
    }
    for (Template t : chosen) {
      boolean appPart = t.parts().stream().anyMatch(p -> p.scope() == Scope.APP);
      if (!appPart) {
        skipped.add(new Skipped(t.key(), "server_scope"));
        continue;
      }
      if (t.appliesWhen().equals("batch") && keys == null && !hasBatch) {
        skipped.add(new Skipped(t.key(), "no_batch_transactions"));
        continue;
      }
      boolean any = false;
      for (Part p : t.parts()) {
        if (p.scope() != Scope.APP) {
          continue;
        }
        if (rules.templateRuleExists(appId, t.key(), p.spec().metric())) {
          continue;
        }
        created.add(create(t, p, appId, env, actor));
        any = true;
      }
      if (!any) {
        skipped.add(new Skipped(t.key(), "exists"));
      }
    }
    return new ApplyResult(created, skipped);
  }

  /**
   * Applies the recommended rules to the applications not marked yet (at most 100 per call).
   *
   * @param hasBatch whether an application has BATCH transactions
   * @return the rules created
   */
  public int applyRecommended(java.util.function.IntPredicate hasBatch) {
    int n = 0;
    for (AlertSetupRepository.PendingApp app : setup.appsWithoutRecommendedRules()) {
      if (!setup.claimRecommended(app.appId())) {
        continue;
      }
      try {
        n += apply(app.appId(), app.env(), null, hasBatch.test(app.appId()), null).created().size();
      } catch (RuntimeException e) {
        setup.unclaimRecommended(app.appId());
        LOG.warn(
            "The recommended alert rules of application {} could not be created; retried in a"
                + " minute: {}",
            app.appId(),
            e.toString());
      }
    }
    return n;
  }

  /**
   * Creates the server self rules unless they were created before (by this or another server).
   *
   * @return the rules created
   */
  public int applyServerRules() {
    if (!setup.claimServerRules()) {
      return 0;
    }
    int n = 0;
    try {
      for (Template t : templates.all()) {
        for (Part p : t.parts()) {
          if (p.scope() == Scope.SERVER
              && !rules.templateRuleExists(null, t.key(), p.spec().metric())) {
            create(t, p, null, null, null);
            n++;
          }
        }
      }
    } catch (RuntimeException e) {
      setup.unclaimServerRules();
      throw e;
    }
    return n;
  }

  /**
   * Creates the batch template rule of an application unless one exists (12 §5.6: at the first
   * BATCH transaction).
   *
   * @return whether a rule was created
   */
  public boolean applyBatch(int appId, String env) {
    Template t = templates.get(DefaultTemplates.BATCH_DELAY).orElse(null);
    if (t == null) {
      return false;
    }
    boolean created = false;
    for (Part p : t.parts()) {
      if (!rules.templateRuleExists(appId, t.key(), p.spec().metric())) {
        create(t, p, appId, env, null);
        created = true;
      }
    }
    return created;
  }

  private UUID create(Template t, Part p, Integer appId, String env, UUID actor) {
    RuleSpec spec = DefaultTemplates.instantiate(t, p, appId, env);
    UUID id = UUID.randomUUID();
    rules.insert(
        new NewRule(
            id,
            appId,
            spec.target().env(),
            spec.name(),
            t.key(),
            spec.enabled(),
            RuleCodec.toJsonText(spec)),
        actor);
    return id;
  }
}
