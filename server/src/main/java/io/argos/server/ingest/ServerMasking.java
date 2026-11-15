/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ingest;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The server's default masking set of 16 §6.6 (SEC-36), applied once more to exception messages
 * that the agent has already masked (12 §3.5 (4): double safety; the agent may run with user
 * patterns only, or be old). Rules in table order: email, kr_mobile, kr_rrn, card (Luhn check),
 * ipv4, jwt.
 *
 * <p>The patterns are those of the agent's {@code MaskPatterns} (WO-10) with the same linear-time
 * forms (a negative lookbehind so that a run is scanned from its start only, possessive runs, no
 * match inside a longer number for the phone and resident number rules) and the same card rule: the
 * longest run of digits and single separators, then, when it fails the Luhn check, shorter
 * candidates from the same start that end before a separator and hold 13 to 19 digits (ADR-0010
 * C-122). Input is cut to {@value #MAX_INPUT_CHARS} characters before matching. Stateless and
 * thread-safe.
 */
public final class ServerMasking {

  /** Characters looked at; the rest is cut before matching. */
  public static final int MAX_INPUT_CHARS = 64 * 1024;

  private static final int CARD_MIN_DIGITS = 13;
  private static final int CARD_MAX_DIGITS = 19;

  private record Rule(Pattern pattern, String replacement, boolean luhn) {}

  private static final List<Rule> RULES =
      List.of(
          new Rule(
              Pattern.compile(
                  "(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]++@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"),
              "<email>",
              false),
          new Rule(
              Pattern.compile("(?<!\\d)01[016789][-\\s]?\\d{3,4}[-\\s]?\\d{4}(?!\\d)"),
              "<phone>",
              false),
          new Rule(Pattern.compile("(?<!\\d)\\d{6}[-\\s]?[1-4]\\d{6}(?!\\d)"), "<rrn>", false),
          new Rule(Pattern.compile("\\b(?:\\d[ -]?){13,19}\\b"), "<card>", true),
          new Rule(Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b"), "<ip>", false),
          new Rule(
              Pattern.compile(
                  "(?<![A-Za-z0-9_-])eyJ[A-Za-z0-9_-]{10,}+\\.[A-Za-z0-9_-]{10,}+"
                      + "\\.[A-Za-z0-9_-]{10,}+"),
              "<jwt>",
              false));

  private ServerMasking() {}

  /** {@code text} with every match of the default set replaced; {@code null} stays {@code null}. */
  public static String mask(String text) {
    if (text == null || text.isEmpty()) {
      return text;
    }
    String out = text.length() > MAX_INPUT_CHARS ? text.substring(0, MAX_INPUT_CHARS) : text;
    for (Rule rule : RULES) {
      out = replace(rule, out);
    }
    return out;
  }

  private static String replace(Rule rule, String text) {
    Matcher matcher = rule.pattern().matcher(text);
    StringBuilder out = null;
    int copied = 0;
    while (matcher.find()) {
      int start = matcher.start();
      int end = matcher.end();
      if (end == start) {
        continue;
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

  private static int cardEnd(String text, int start, int end) {
    int stop = end;
    while (stop > start && isCardSeparator(text.charAt(stop - 1))) {
      stop--;
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
      int i = candidate;
      while (i > start && !isCardSeparator(text.charAt(i - 1))) {
        i--;
        digits--;
      }
      if (i <= start) {
        return -1;
      }
      candidate = i - 1;
    }
    return -1;
  }

  private static boolean isCardSeparator(char c) {
    return c == ' ' || c == '-';
  }

  /** Luhn check over the digits of {@code text[start, end)}, 13 to 19 digits. */
  static boolean luhn(String text, int start, int end) {
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
}
