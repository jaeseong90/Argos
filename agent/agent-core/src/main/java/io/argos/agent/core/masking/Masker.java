/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.masking;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Message masking (SEC-36, 16 §6.6): the default regular expression set applied to exception
 * messages before they leave the application thread, in table order. Length limits are applied
 * after masking (16 §6.6). User patterns ({@code capture.mask-patterns}) and the shared use for
 * {@code partial} bind parameters and step details arrive with WO-12/WO-13.
 *
 * <p>Default set (name: replacement): email {@code <email>}, kr_mobile {@code <phone>}, kr_rrn
 * {@code <rrn>}, card (13-19 digits that pass the Luhn check) {@code <card>}, ipv4 {@code <ip>},
 * jwt {@code <jwt>}.
 */
public final class Masker {
  private static final Pattern EMAIL =
      Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
  // kr_mobile and kr_rrn carry digit boundaries in addition to the 16 §6.6 patterns, so that they
  // do not match inside longer numbers (a 16-digit card number would otherwise be cut by the rrn
  // rule before the card rule sees it).
  private static final Pattern KR_MOBILE =
      Pattern.compile("(?<!\\d)01[016789][-\\s]?\\d{3,4}[-\\s]?\\d{4}(?!\\d)");
  private static final Pattern KR_RRN = Pattern.compile("(?<!\\d)\\d{6}[-\\s]?[1-4]\\d{6}(?!\\d)");
  private static final Pattern CARD = Pattern.compile("\\b(?:\\d[ -]?){13,19}\\b");
  private static final Pattern IPV4 = Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b");

  /**
   * The JWT value pattern of 16 §5.4 ({@code [A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.}) anchored at
   * the start of a token instead of the start of the text, so that dotted class names inside a
   * message ({@code com.example.LongPackageName.Other}) are not taken for tokens.
   */
  private static final Pattern JWT =
      Pattern.compile(
          "(?<![A-Za-z0-9_.-])[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]*");

  private static final Masker DEFAULT = new Masker();

  private Masker() {}

  /** The default set (16 §6.6). */
  public static Masker defaults() {
    return DEFAULT;
  }

  /** {@code text} with every match of the set replaced; null stays null. */
  public String mask(String text) {
    if (text == null || text.isEmpty()) {
      return text;
    }
    String out = EMAIL.matcher(text).replaceAll("<email>");
    out = KR_MOBILE.matcher(out).replaceAll("<phone>");
    out = KR_RRN.matcher(out).replaceAll("<rrn>");
    out = maskCards(out);
    out = IPV4.matcher(out).replaceAll("<ip>");
    return JWT.matcher(out).replaceAll("<jwt>");
  }

  private static String maskCards(String text) {
    Matcher matcher = CARD.matcher(text);
    StringBuilder out = null;
    int copied = 0;
    while (matcher.find()) {
      if (!luhn(matcher.group())) {
        continue;
      }
      if (out == null) {
        out = new StringBuilder(text.length());
      }
      // The pattern may take one trailing separator; it stays in the text.
      int end = matcher.end();
      while (end > matcher.start()
          && (text.charAt(end - 1) == ' ' || text.charAt(end - 1) == '-')) {
        end--;
      }
      out.append(text, copied, matcher.start()).append("<card>");
      copied = end;
    }
    if (out == null) {
      return text;
    }
    return out.append(text, copied, text.length()).toString();
  }

  /** Luhn check over the digits of {@code candidate} (separators ignored), 13-19 digits. */
  static boolean luhn(String candidate) {
    int sum = 0;
    int digits = 0;
    boolean doubled = false;
    for (int i = candidate.length() - 1; i >= 0; i--) {
      char c = candidate.charAt(i);
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
    return digits >= 13 && digits <= 19 && sum % 10 == 0;
  }
}
