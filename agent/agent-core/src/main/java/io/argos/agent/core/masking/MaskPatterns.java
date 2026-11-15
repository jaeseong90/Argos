/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.masking;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The masking rules of {@link Masker}: the default set of 16 §6.6 (SEC-36), in table order, and the
 * user patterns of {@code capture.mask-patterns} compiled once.
 *
 * <table>
 *   <caption>Default set (16 §6.6)</caption>
 *   <tr><th>Name</th><th>Pattern</th><th>Replacement</th></tr>
 *   <tr><td>email</td><td>{@code [A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}}</td>
 *   <td>{@code <email>}</td></tr>
 *   <tr><td>kr_mobile</td><td>{@code 01[016789][-\s]?\d{3,4}[-\s]?\d{4}}</td>
 *   <td>{@code <phone>}</td></tr>
 *   <tr><td>kr_rrn</td><td>{@code \d{6}[-\s]?[1-4]\d{6}}</td><td>{@code <rrn>}</td></tr>
 *   <tr><td>card</td><td>{@code \b(?:\d[ -]?){13,19}\b}, only when the Luhn check passes</td>
 *   <td>{@code <card>}</td></tr>
 *   <tr><td>ipv4</td><td>{@code \b(?:\d{1,3}\.){3}\d{1,3}\b}</td><td>{@code <ip>}</td></tr>
 *   <tr><td>jwt</td><td>{@code eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}}
 *   (16 §5.4)</td><td>{@code <jwt>}</td></tr>
 * </table>
 *
 * <p>Differences to the table text, each one equivalent for a match but linear in time on
 * adversarial input (03 §2, no catastrophic backtracking): the email and jwt rules start only where
 * a run of their characters starts (a negative lookbehind), so a 64 KiB run is scanned once and not
 * once per start position; runs that cannot be followed by the next literal are matched
 * possessively. kr_mobile and kr_rrn also refuse to match inside a longer number ({@code
 * (?<!\d)}/{@code (?!\d)}), so that the 16-digit card number is not cut by the rrn rule before the
 * card rule sees it (the WO-02 Masker reason). The jwt rule of 16 §5.4 is anchored to a whole
 * environment value there; here it matches a token inside a message.
 *
 * <p>User patterns: at most {@value #MAX_USER_PATTERNS}, each at most {@value
 * #MAX_USER_PATTERN_CHARS} characters, replaced by {@value #USER_REPLACEMENT}. 10 §15 makes {@code
 * capture.mask-patterns} a list of regular expressions; the name and replacement columns of 16 §6.6
 * have no place in it, so every user match becomes {@value #USER_REPLACEMENT}. A user pattern is
 * not known to be linear: {@link Masker} runs it under a character-read budget.
 */
public final class MaskPatterns {
  /** Most user patterns ({@code capture.mask-patterns} items). */
  public static final int MAX_USER_PATTERNS = 32;

  /** Longest user pattern, in characters. */
  public static final int MAX_USER_PATTERN_CHARS = 512;

  /** Replacement of every user pattern match. */
  public static final String USER_REPLACEMENT = "<masked>";

  /** One named rule. */
  public static final class Rule {
    private final String name;
    private final Pattern pattern;
    private final String replacement;
    private final boolean luhn;
    private final boolean user;

    Rule(String name, Pattern pattern, String replacement, boolean luhn, boolean user) {
      this.name = name;
      this.pattern = pattern;
      this.replacement = replacement;
      this.luhn = luhn;
      this.user = user;
    }

    /** {@code email}, {@code kr_mobile}, ..., or {@code user_<n>} (1-based). */
    public String name() {
      return name;
    }

    public Pattern pattern() {
      return pattern;
    }

    public String replacement() {
      return replacement;
    }

    /** True for the card rule: a match is replaced only when the Luhn check passes. */
    public boolean luhn() {
      return luhn;
    }

    /** True for a user pattern (run under the read budget). */
    public boolean user() {
      return user;
    }
  }

  private static final List<Rule> DEFAULTS =
      Collections.unmodifiableList(
          Arrays.asList(
              rule(
                  "email",
                  "(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]++@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}",
                  "<email>",
                  false),
              rule(
                  "kr_mobile",
                  "(?<!\\d)01[016789][-\\s]?\\d{3,4}[-\\s]?\\d{4}(?!\\d)",
                  "<phone>",
                  false),
              rule("kr_rrn", "(?<!\\d)\\d{6}[-\\s]?[1-4]\\d{6}(?!\\d)", "<rrn>", false),
              rule("card", "\\b(?:\\d[ -]?){13,19}\\b", "<card>", true),
              rule("ipv4", "\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b", "<ip>", false),
              rule(
                  "jwt",
                  "(?<![A-Za-z0-9_-])eyJ[A-Za-z0-9_-]{10,}+\\.[A-Za-z0-9_-]{10,}+"
                      + "\\.[A-Za-z0-9_-]{10,}+",
                  "<jwt>",
                  false)));

  private MaskPatterns() {}

  /** The default set in table order (16 §6.6). */
  public static List<Rule> defaults() {
    return DEFAULTS;
  }

  /**
   * The user patterns of {@code patterns} ({@code capture.mask-patterns}), compiled once, in order.
   * A blank item is ignored; an item longer than {@value #MAX_USER_PATTERN_CHARS} characters or one
   * that does not compile is skipped with a warning added to {@code warnings}; items after the
   * {@value #MAX_USER_PATTERNS}th usable one are dropped with one warning. Warnings name the item
   * position, never its text.
   */
  public static List<Rule> compileUser(List<String> patterns, List<String> warnings) {
    List<Rule> rules = new ArrayList<Rule>();
    if (patterns == null) {
      return Collections.unmodifiableList(rules);
    }
    int dropped = 0;
    for (int i = 0; i < patterns.size(); i++) {
      String text = patterns.get(i);
      if (text == null || text.trim().isEmpty()) {
        continue;
      }
      if (rules.size() >= MAX_USER_PATTERNS) {
        dropped++;
        continue;
      }
      String where = "capture.mask-patterns pattern " + (i + 1);
      if (text.length() > MAX_USER_PATTERN_CHARS) {
        warnings.add(where + " is longer than " + MAX_USER_PATTERN_CHARS + " characters; skipped");
        continue;
      }
      Pattern pattern;
      try {
        pattern = Pattern.compile(text);
      } catch (PatternSyntaxException e) {
        warnings.add(where + " is not a valid regular expression; skipped");
        continue;
      }
      rules.add(new Rule("user_" + (i + 1), pattern, USER_REPLACEMENT, false, true));
    }
    if (dropped > 0) {
      warnings.add(
          "capture.mask-patterns has more than "
              + MAX_USER_PATTERNS
              + " patterns; "
              + dropped
              + " ignored");
    }
    return Collections.unmodifiableList(rules);
  }

  private static Rule rule(String name, String regex, String replacement, boolean luhn) {
    return new Rule(name, Pattern.compile(regex), replacement, luhn, false);
  }
}
