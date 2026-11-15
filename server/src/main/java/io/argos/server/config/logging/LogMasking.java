/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.config.logging;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The last line of defence against secrets in the server log (12 §7.1, 17 §11.3, 16 §2.6): every
 * log message and every rendered stack trace passes through {@link #mask(String)} before a layout
 * sees it ({@link MaskingLogEventFactory}). Code must still never log a secret; this catches the
 * mistakes.
 *
 * <p>Rules, applied in order (the replacement keeps what identifies the value, drops the value):
 *
 * <ol>
 *   <li>agent tokens {@code argos_[A-Za-z0-9_-]{43}} become {@code argos_****} (12 §7.1, ADR-0010
 *       C-94);
 *   <li>{@code Authorization} header values keep their scheme: {@code Authorization: Bearer ****},
 *       {@code Authorization: Basic ****}, also when the value is wrapped in brackets or quotes
 *       after the separator, as header lists and maps print it ({@code Authorization:[Bearer ****]}
 *       of Spring's request logging, {@code {Authorization=[Bearer ****], Host=[x]}} of {@code
 *       Map<String, List<String>>.toString()}); the closing bracket stays. A header with several
 *       values is masked entirely: inside brackets every comma-separated item up to the closing
 *       bracket ({@code {Authorization=[Basic ****, Basic ****]}}), without brackets every further
 *       item that names its scheme, also when every value is quoted ({@code Authorization: Basic
 *       ****, Basic ****}, {@code [Authorization:"Basic ****", "Basic ****", Host:"x"]} of Spring's
 *       {@code HttpHeaders.toString()}; a following {@code , next} without a scheme is not part of
 *       the header and stays). Rule 2 scans the text once from left to right, so its cost grows
 *       linearly with the length of the message, also when brackets are never closed. Values that
 *       are already masked stay as they are, so masking twice gives the same text;
 *   <li>any other {@code Bearer <credential>} becomes {@code Bearer ****};
 *   <li>credentials in URLs: {@code password=}, {@code pwd=} and {@code passwd=} parameters of JDBC
 *       and HTTP URLs, and the password of {@code scheme://user:password@host};
 *   <li>{@code key=value} and {@code key: value} pairs whose key ends with {@code password}, {@code
 *       passwd}, {@code pwd}, {@code secret}, {@code token}, {@code api-key}, {@code apikey},
 *       {@code access-key}, {@code private-key}, {@code credential} or {@code credentials} (any
 *       case, separators {@code -}, {@code _}, {@code .}): {@code db.password=****}.
 * </ol>
 *
 * <p>Every rule leaves an already masked value unchanged: {@code mask(mask(x))} equals {@code
 * mask(x)}.
 */
public final class LogMasking {

  /** The replacement of a masked value. */
  public static final String MASK = "****";

  /** A matched secret and its replacement template. */
  private record Rule(Pattern pattern, String template) {

    String apply(String text) {
      Matcher m = pattern.matcher(text);
      return m.find() ? m.replaceAll(template) : text;
    }
  }

  /** The schemes an {@code Authorization} value may name before its credential. */
  private static final String AUTH_SCHEME = "(?:bearer|basic|digest|token|negotiate)";

  /** One credential of an {@code Authorization} value outside brackets. */
  private static final String AUTH_VALUE = "[^\\s\"',;})\\]]+";

  /** Rule 2: the key and its separator; the value starts at its end. */
  private static final Pattern AUTHORIZATION_KEY =
      Pattern.compile("(?i)authorization[\"']?\\s*+[:=]\\s*+");

  /**
   * Rule 2, a value outside a closed bracket on the same line: the first item, optionally after
   * opening brackets or quotes, and every further {@code , <scheme> <credential>} item, each
   * optionally quoted. Prefix and scheme are possessive, so the scheme of an already masked value
   * is never taken for its credential.
   */
  private static final Pattern AUTHORIZATION_UNBRACKETED =
      Pattern.compile(
          "(?i)[\\[(\"']*+(?:"
              + AUTH_SCHEME
              + "\\s++)?+"
              + AUTH_VALUE
              + "(?:[\"']?,\\s*+[\"']?"
              + AUTH_SCHEME
              + "\\s++"
              + AUTH_VALUE
              + ")*+");

  /**
   * The head of one item of an {@code Authorization} value: leading blanks, brackets and quotes,
   * then the scheme. What follows, up to trailing quotes and blanks, is the credential.
   */
  private static final Pattern AUTHORIZATION_ITEM_HEAD =
      Pattern.compile("(?i)[\\s\\[(\"']*+(?:" + AUTH_SCHEME + "\\s++)?+");

  /** Rule 1. */
  private static final Rule AGENT_TOKEN =
      new Rule(Pattern.compile("argos_[A-Za-z0-9_-]{43}"), "argos_" + MASK);

  /** Rules 3 to 5. */
  private static final List<Rule> RULES_AFTER_AUTHORIZATION =
      List.of(
          new Rule(
              Pattern.compile("(?i)\\b(bearer\\s+)(?!\\*{4})[A-Za-z0-9._~+/=-]+"), "$1" + MASK),
          new Rule(
              Pattern.compile("(?i)([?&;](?:password|passwd|pwd)=)(?!\\*{4})[^&;#\\s\"']+"),
              "$1" + MASK),
          new Rule(
              Pattern.compile(
                  "(?i)(\\b[a-z][a-z0-9+.-]*://[^/\\s:@\"']+:)(?!\\*{4}@)[^@\\s/\"']+@"),
              "$1" + MASK + "@"),
          new Rule(
              Pattern.compile(
                  "(?i)(\\b[\\w.-]*(?:password|passwd|pwd|secret|token|api[_.-]?key|access[_.-]?key"
                      + "|private[_.-]?key|credentials?)[\"']?\\s*[=:]\\s*[\"']?)(?!\\*{4})"
                      + "[^\\s\"',;&}]+"),
              "$1" + MASK));

  private LogMasking() {}

  /** {@code text} with every secret the rules recognise replaced; null stays null. */
  public static String mask(String text) {
    if (text == null || text.isEmpty()) {
      return text;
    }
    String result = maskAuthorization(AGENT_TOKEN.apply(text));
    for (Rule rule : RULES_AFTER_AUTHORIZATION) {
      result = rule.apply(result);
    }
    return result;
  }

  /**
   * Rule 2: after every {@code Authorization} key, the items of its value keep their scheme and
   * lose their credential. A value that opens a bracket closed on the same line is masked up to
   * that bracket, which stays; any other value as far as {@link #AUTHORIZATION_UNBRACKETED}
   * reaches. The position of the next closing bracket or line end is remembered between keys and
   * only searched again past it, so every character is scanned for it at most once.
   */
  private static String maskAuthorization(String text) {
    Matcher key = AUTHORIZATION_KEY.matcher(text);
    Matcher unbracketed = AUTHORIZATION_UNBRACKETED.matcher(text);
    int length = text.length();
    StringBuilder out = null;
    int copied = 0;
    int from = 0;
    int stop = -1;
    while (from < length && key.find(from)) {
      int value = key.end();
      int end = -1;
      String replacement = null;
      if (value < length && isOpeningBracket(text.charAt(value))) {
        if (stop < value + 1) {
          stop = value + 1;
          while (stop < length && !isClosingBracketOrLineEnd(text.charAt(stop))) {
            stop++;
          }
        }
        if (stop < length && isClosingBracket(text.charAt(stop))) {
          end = stop;
          replacement =
              text.charAt(value) + maskAuthorizationItems(text.substring(value + 1, stop));
        }
      }
      if (end < 0) {
        unbracketed.region(value, length);
        if (unbracketed.lookingAt()) {
          end = unbracketed.end();
          replacement = maskAuthorizationItems(text.substring(value, end));
        }
      }
      if (end < 0) {
        from = value;
        continue;
      }
      if (out == null) {
        out = new StringBuilder(length + 16);
      }
      out.append(text, copied, value).append(replacement);
      copied = end;
      from = end;
    }
    return out == null ? text : out.append(text, copied, length).toString();
  }

  private static String maskAuthorizationItems(String items) {
    String[] parts = items.split(",", -1);
    StringBuilder out = new StringBuilder(items.length() + 8);
    for (int i = 0; i < parts.length; i++) {
      if (i > 0) {
        out.append(',');
      }
      out.append(maskAuthorizationItem(parts[i]));
    }
    return out.toString();
  }

  /**
   * An item with a credential keeps its brackets, quotes and scheme; the credential becomes ****.
   */
  private static String maskAuthorizationItem(String item) {
    Matcher head = AUTHORIZATION_ITEM_HEAD.matcher(item);
    int start = head.lookingAt() ? head.end() : 0;
    int end = item.length();
    while (end > start && isQuoteOrBlank(item.charAt(end - 1))) {
      end--;
    }
    if (end == start) {
      return item;
    }
    return item.substring(0, start) + MASK + item.substring(end);
  }

  private static boolean isOpeningBracket(char c) {
    return c == '[' || c == '(';
  }

  private static boolean isClosingBracket(char c) {
    return c == ']' || c == ')';
  }

  private static boolean isClosingBracketOrLineEnd(char c) {
    return isClosingBracket(c) || c == '\r' || c == '\n';
  }

  /** The characters of {@code ["'\s]}. */
  private static boolean isQuoteOrBlank(char c) {
    return switch (c) {
      case '"', '\'', ' ', '\t', '\n', '\u000B', '\f', '\r' -> true;
      default -> false;
    };
  }
}
