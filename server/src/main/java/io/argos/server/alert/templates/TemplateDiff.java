/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert.templates;

import io.argos.server.alert.rule.RuleCodec;
import io.argos.server.alert.rule.RuleSpec;
import io.argos.server.alert.templates.DefaultTemplates.Part;
import io.argos.server.alert.templates.DefaultTemplates.Template;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * "Difference from the template" of a rule made from a default template (F-S24, 12 §5.6): when the
 * rule's {@code template_version} is below the template's, the fields where the rule differs from
 * the current template part, compared on the canonical rule JSON. The rule is never changed
 * automatically; the operator applies the template values (WO-38 screens) or keeps the rule.
 */
public final class TemplateDiff {

  /** Fields compared (the rule's own fields: name, target, channels and enabled are excluded). */
  public static final List<String> FIELDS =
      List.of(
          "kind",
          "metric",
          "window",
          "for",
          "evaluation_unit",
          "condition",
          "recover",
          "min_samples",
          "no_data",
          "warmup_grace",
          "active_time",
          "mute_schedules",
          "renotify_interval",
          "message_template");

  private static final Set<String> NUMBER_FIELDS = Set.of("min_samples");

  private TemplateDiff() {}

  /**
   * One differing field.
   *
   * @param field the JSON field
   * @param rule the rule's value (JSON)
   * @param template the template's value (JSON)
   */
  public record Change(String field, JsonNode rule, JsonNode template) {}

  /**
   * The difference of a rule.
   *
   * @param templateKey the template
   * @param ruleVersion the template version the rule was made from
   * @param templateVersion the current template version
   * @param changes the differing fields (may be empty: only the version moved)
   */
  public record Diff(
      String templateKey, int ruleVersion, int templateVersion, List<Change> changes) {

    /** Copies. */
    public Diff {
      changes = List.copyOf(changes);
    }
  }

  /**
   * The difference of {@code rule} to its template, empty when the rule has no template, the
   * template or its part is unknown, or the rule is at the template's version.
   */
  public static Optional<Diff> of(RuleSpec rule, DefaultTemplates templates) {
    if (rule.templateKey() == null) {
      return Optional.empty();
    }
    Optional<Template> t = templates.get(rule.templateKey());
    if (t.isEmpty()) {
      return Optional.empty();
    }
    Optional<Part> part = t.get().part(rule.metric());
    int ruleVersion = rule.templateVersion() == null ? 0 : rule.templateVersion();
    if (part.isEmpty() || ruleVersion >= t.get().version()) {
      return Optional.empty();
    }
    ObjectNode mine = RuleCodec.toJson(rule);
    ObjectNode theirs =
        RuleCodec.toJson(
            DefaultTemplates.instantiate(
                t.get(), part.get(), rule.target().appId(), rule.target().env()));
    List<Change> changes = new ArrayList<>();
    for (String f : FIELDS) {
      JsonNode a = mine.get(f);
      JsonNode b = theirs.get(f);
      if (!same(f, a, b)) {
        changes.add(new Change(f, a, b));
      }
    }
    return Optional.of(new Diff(rule.templateKey(), ruleVersion, t.get().version(), changes));
  }

  private static boolean same(String field, JsonNode a, JsonNode b) {
    if (NUMBER_FIELDS.contains(field) && a != null && b != null && a.isNumber() && b.isNumber()) {
      return a.doubleValue() == b.doubleValue();
    }
    return Objects.equals(a, b);
  }
}
