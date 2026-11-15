/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.masking;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;

/**
 * Masking of collected text (SEC-36, 16 §6.6): the default set of {@link MaskPatterns} in table
 * order, then the user patterns of {@code capture.mask-patterns}. The same set applies to exception
 * messages, the {@code detail} field and {@code partial} bind parameters (16 §6.6; the call sites
 * are WO-12/WO-13).
 *
 * <p>Bounds (03 §2, 10 §14): the input is cut to {@value #MAX_INPUT_CHARS} characters before
 * matching; {@link #maskMessage(String, int)} applies the length limit of an exception message
 * ({@code capture.error-message-max-length}, 1,024 by default) after masking (16 §6.6). The default
 * rules are linear in the input length ({@link MaskPatterns}); a user pattern may not be, so it
 * runs under a budget of {@value #USER_BUDGET_PER_CHAR} character reads per input character plus
 * {@value #USER_BUDGET_BASE}: a pattern that exceeds it stops, and the whole text becomes {@value
 * #BUDGET_EXCEEDED} (fail closed: nothing unmasked leaves the agent), counted by {@link
 * #budgetExceededTotal()}. A user pattern that overflows the stack instead ({@code java.util.regex}
 * recurses once per repetition of a group with an alternation, so {@code (?:a|b)*c} on a few
 * thousand characters throws {@link StackOverflowError} long before the budget is used up) or fails
 * in any other way is handled the same: {@value #BUDGET_EXCEEDED}, counted, nothing thrown to the
 * caller (the transaction is kept). The default rules are linear and not recursive, so they run
 * without this guard.
 *
 * <p>The card rule (16 §6.6 {@code \b(?:\d[ -]?){13,19}\b} with the Luhn check) takes the longest
 * run of digits and single separators. When that run fails the Luhn check, shorter candidates from
 * the same start are checked, longest first, each ending before a separator (a word boundary after
 * a digit, never inside a digit group) and holding 13 to 19 digits: at most 7 candidates of at most
 * 37 characters per run, so the rule stays linear. The first candidate that passes becomes {@code
 * <card>} and the rest of the run stays as written, so a card followed by another number ({@code
 * 4111 1111 1111 1111 1}) is masked (SEC-36: 13 to 19 digit cards).
 *
 * <p>Immutable and thread-safe; {@link #install(Masker)} publishes the masker built from the
 * current configuration and {@link #current()} returns it (the default set until the configuration
 * is loaded). No lock, no allocation besides the result when nothing matches.
 */
public final class Masker {
  /** Input characters looked at; the rest is cut before matching. */
  public static final int MAX_INPUT_CHARS = 64 * 1024;

  /** Default length limit of an exception message after masking (SEC-36, 10 §14). */
  public static final int DEFAULT_MESSAGE_MAX = 1024;

  /** Character reads a user pattern may make per input character. */
  static final int USER_BUDGET_PER_CHAR = 64;

  /** Character reads every user pattern may make on top of the per-character budget. */
  static final int USER_BUDGET_BASE = 4096;

  /** The whole text when a user pattern ran out of its budget. */
  public static final String BUDGET_EXCEEDED = "<masked>";

  /** Digits of a card number (SEC-36, 16 §6.6). */
  private static final int CARD_MIN_DIGITS = 13;

  private static final int CARD_MAX_DIGITS = 19;

  private static final Masker DEFAULT = new Masker(Collections.<MaskPatterns.Rule>emptyList());
  private static final AtomicLong BUDGET_EXCEEDED_TOTAL = new AtomicLong();
  private static volatile Masker current = DEFAULT;

  private final List<MaskPatterns.Rule> userRules;

  private Masker(List<MaskPatterns.Rule> userRules) {
    this.userRules = userRules;
  }

  /** The default set only (16 §6.6). */
  public static Masker defaults() {
    return DEFAULT;
  }

  /**
   * The default set followed by the user patterns {@code userPatterns} ({@code
   * capture.mask-patterns}); invalid or surplus patterns are skipped with a warning added to {@code
   * warnings} ({@link MaskPatterns#compileUser(List, List)}).
   */
  public static Masker of(List<String> userPatterns, List<String> warnings) {
    List<MaskPatterns.Rule> rules = MaskPatterns.compileUser(userPatterns, warnings);
    return rules.isEmpty() ? DEFAULT : new Masker(rules);
  }

  /** The masker of the current configuration ({@link #defaults()} until one is installed). */
  public static Masker current() {
    return current;
  }

  /** Publishes {@code masker} as {@link #current()} (start, remote configuration change). */
  public static void install(Masker masker) {
    current = masker == null ? DEFAULT : masker;
  }

  /** Times a user pattern ran out of its budget since the JVM started (self-diagnostics). */
  public static long budgetExceededTotal() {
    return BUDGET_EXCEEDED_TOTAL.get();
  }

  /** Number of user patterns. */
  public int userPatternCount() {
    return userRules.size();
  }

  /**
   * {@code text} cut to {@value #MAX_INPUT_CHARS} characters, with every match of the default set
   * and then of the user patterns replaced; null stays null.
   */
  public String mask(String text) {
    if (text == null || text.isEmpty()) {
      return text;
    }
    String out = text.length() > MAX_INPUT_CHARS ? text.substring(0, MAX_INPUT_CHARS) : text;
    for (MaskPatterns.Rule rule : MaskPatterns.defaults()) {
      out = replace(rule, out, out);
    }
    for (MaskPatterns.Rule rule : userRules) {
      BudgetedText budgeted =
          new BudgetedText(out, USER_BUDGET_BASE + (long) USER_BUDGET_PER_CHAR * out.length());
      try {
        out = replace(rule, budgeted, out);
      } catch (RuntimeException | StackOverflowError e) {
        // BudgetExceeded (backtracking), StackOverflowError (java.util.regex recurses once per
        // repetition of a group with an alternation, such as (?:a|b)*c, so a long input overflows
        // the stack before the budget runs out) or any other failure of a user pattern: fail
        // closed, never pass the text on unmasked and never lose the transaction (SEC-36).
        BUDGET_EXCEEDED_TOTAL.incrementAndGet();
        return BUDGET_EXCEEDED;
      }
    }
    return out;
  }

  /**
   * An exception message: {@link #mask(String)}, then cut to {@code maxLength} characters (16 §6.6:
   * the limit applies after masking; a surrogate pair is not split). {@code maxLength} below 0
   * means {@value #DEFAULT_MESSAGE_MAX}.
   */
  public String maskMessage(String text, int maxLength) {
    String masked = mask(text);
    int max = maxLength < 0 ? DEFAULT_MESSAGE_MAX : maxLength;
    if (masked == null || masked.length() <= max) {
      return masked;
    }
    int end = max;
    if (end > 0 && Character.isHighSurrogate(masked.charAt(end - 1))) {
      end--;
    }
    return masked.substring(0, end);
  }

  /** {@code text} ({@code input} is the same characters) with the matches of {@code rule}. */
  private static String replace(MaskPatterns.Rule rule, CharSequence input, String text) {
    Matcher matcher = rule.pattern().matcher(input);
    StringBuilder out = null;
    int copied = 0;
    while (matcher.find()) {
      int start = matcher.start();
      int end = matcher.end();
      if (end == start) {
        continue; // an empty match of a user pattern replaces nothing
      }
      if (rule.luhn()) {
        end = cardEnd(text, start, end);
        if (end < 0) {
          continue;
        }
      }
      if (out == null) {
        out = new StringBuilder(text.length());
      }
      out.append(text, copied, start).append(rule.replacement());
      copied = end;
    }
    if (out == null) {
      return text;
    }
    return out.append(text, copied, text.length()).toString();
  }

  /**
   * The end of the card number in the card rule match {@code text[start, end)}, or -1 when none:
   * the match itself when it passes the Luhn check, else the longest shorter candidate from {@code
   * start} that ends before a separator, holds 13 to 19 digits and passes (see the class comment).
   * A trailing separator of the match stays in the text.
   */
  private static int cardEnd(String text, int start, int end) {
    int stop = end;
    while (stop > start && isCardSeparator(text.charAt(stop - 1))) {
      stop--; // the card pattern may take one trailing separator
    }
    int digits = 0;
    for (int i = start; i < stop; i++) {
      if (!isCardSeparator(text.charAt(i))) {
        digits++;
      }
    }
    int candidate = stop;
    while (digits >= CARD_MIN_DIGITS) {
      if (digits <= CARD_MAX_DIGITS && luhn(text, start, candidate)) {
        return candidate;
      }
      // The next shorter candidate ends before the separator in front of the last digit group.
      int i = candidate;
      while (i > start && !isCardSeparator(text.charAt(i - 1))) {
        i--;
        digits--;
      }
      if (i <= start) {
        return -1;
      }
      candidate = i - 1; // the separator stays outside the candidate
    }
    return -1;
  }

  private static boolean isCardSeparator(char c) {
    return c == ' ' || c == '-';
  }

  /** Luhn check over the digits of {@code candidate} (separators ignored), 13-19 digits. */
  static boolean luhn(String candidate) {
    return luhn(candidate, 0, candidate.length());
  }

  private static boolean luhn(String text, int start, int end) {
    int sum = 0;
    int digits = 0;
    boolean doubled = false;
    for (int i = end - 1; i >= start; i--) {
      char c = text.charAt(i);
      if (c < '0' || c > '9') {
        continue;
      }
      int d = c - '0';
      if (doubled) {
        d *= 2;
        if (d > 9) {
          d -= 9;
        }
      }
      sum += d;
      doubled = !doubled;
      digits++;
    }
    return digits >= CARD_MIN_DIGITS && digits <= CARD_MAX_DIGITS && sum % 10 == 0;
  }

  /** Thrown by {@link BudgetedText} when a user pattern used up its reads; no stack trace. */
  private static final class BudgetExceeded extends RuntimeException {
    private static final long serialVersionUID = 1L;

    BudgetExceeded() {
      super("masking budget exceeded", null, false, false);
    }
  }

  /** The text as seen by a user pattern: every character read counts against the budget. */
  private static final class BudgetedText implements CharSequence {
    private final String text;
    private final int offset;
    private final int length;
    private final long[] remaining;

    BudgetedText(String text, long budget) {
      this(text, 0, text.length(), new long[] {budget});
    }

    private BudgetedText(String text, int offset, int length, long[] remaining) {
      this.text = text;
      this.offset = offset;
      this.length = length;
      this.remaining = remaining;
    }

    @Override
    public int length() {
      return length;
    }

    @Override
    public char charAt(int index) {
      if (--remaining[0] < 0) {
        throw new BudgetExceeded();
      }
      if (index < 0 || index >= length) {
        throw new IndexOutOfBoundsException();
      }
      return text.charAt(offset + index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
      if (start < 0 || end > length || start > end) {
        throw new IndexOutOfBoundsException();
      }
      return new BudgetedText(text, offset + start, end - start, remaining);
    }

    @Override
    public String toString() {
      return text.substring(offset, offset + length);
    }
  }
}
