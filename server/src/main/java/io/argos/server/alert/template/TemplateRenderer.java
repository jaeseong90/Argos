/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert.template;

import io.argos.server.alert.api.AlertNotification;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Alert message templates (12 §5.5, 14 §4.13, SEC-39): {@code {{variable}}} placeholders, where the
 * variable is one of {@link AlertNotification#VARIABLES} ({@code app}, {@code env}, {@code
 * instance}, {@code service}, {@code host}, {@code metric}, {@code value}, {@code threshold},
 * {@code severity}, {@code duration}, {@code started_at}, {@code link}, {@code runbook}, {@code
 * rule}). Any other reference ({@code {{x}}} with an unknown name, a filter, a dotted path, an
 * expression) is refused when the rule is saved ({@code 422 template_variable_forbidden});
 * collected raw text is never a variable. Rendering substitutes the values as plain text, a
 * variable without a value becomes empty; nothing is evaluated.
 */
public final class TemplateRenderer {

  /** Characters of a message template at most. */
  public static final int MAX_TEMPLATE_CHARS = 2_048;

  /** The message of a rule without a template. */
  public static final String DEFAULT_TEMPLATE =
      "[{{severity}}] {{rule}}: {{app}} {{env}} {{metric}} {{value}} (threshold {{threshold}}) for"
          + " {{duration}} {{link}}";

  /** Any {@code {{...}}} reference (no nested braces). */
  private static final Pattern REFERENCE = Pattern.compile("\\{\\{([^{}]*)\\}\\}");

  private static final Pattern NAME = Pattern.compile("\\s*([a-z_]+)\\s*");

  private TemplateRenderer() {}

  /**
   * The references of {@code template} that are not allowed variables, in order of appearance,
   * without duplicates (empty: the template is valid).
   */
  public static List<String> forbiddenReferences(String template) {
    if (template == null) {
      return List.of();
    }
    Set<String> out = new LinkedHashSet<>();
    Matcher m = REFERENCE.matcher(template);
    while (m.find()) {
      String name = variable(m.group(1));
      if (name == null || !AlertNotification.VARIABLES.contains(name)) {
        out.add(m.group(1).strip());
      }
    }
    return new ArrayList<>(out);
  }

  /** The allowed variables a template references, in order, without duplicates. */
  public static List<String> references(String template) {
    Set<String> out = new LinkedHashSet<>();
    if (template != null) {
      Matcher m = REFERENCE.matcher(template);
      while (m.find()) {
        String name = variable(m.group(1));
        if (name != null && AlertNotification.VARIABLES.contains(name)) {
          out.add(name);
        }
      }
    }
    return new ArrayList<>(out);
  }

  /**
   * Renders {@code template} (or {@link #DEFAULT_TEMPLATE} when null or blank) with {@code
   * variables}. A forbidden reference renders as empty text (a stored template never has one; this
   * is a second line of defence).
   */
  public static String render(String template, Map<String, String> variables) {
    String t = template == null || template.isBlank() ? DEFAULT_TEMPLATE : template;
    Matcher m = REFERENCE.matcher(t);
    StringBuilder out = new StringBuilder(t.length() + 64);
    while (m.find()) {
      String name = variable(m.group(1));
      String value =
          name == null || !AlertNotification.VARIABLES.contains(name) ? null : variables.get(name);
      m.appendReplacement(out, Matcher.quoteReplacement(value == null ? "" : value));
    }
    m.appendTail(out);
    return out.toString().replaceAll(" {2,}", " ").strip();
  }

  private static String variable(String inner) {
    Matcher m = NAME.matcher(inner);
    return m.matches() ? m.group(1) : null;
  }
}
