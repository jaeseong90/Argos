/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert.rule;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Durations of the rule JSON (14 §4.13: {@code "1m"}, {@code "5m"}, {@code "30s"}, {@code "2h"},
 * {@code "7d"}; parts may be combined, {@code "1h30m"}). The canonical text uses the largest unit
 * that divides the duration and otherwise the parts from days down to seconds.
 */
public final class RuleDurations {

  private static final Pattern WHOLE = Pattern.compile("(?:\\d{1,6}[dhms])+");
  private static final Pattern PART = Pattern.compile("(\\d{1,6})([dhms])");

  private RuleDurations() {}

  /**
   * Parses a rule duration.
   *
   * @return the duration, or null when {@code text} is not one
   */
  public static Duration parse(String text) {
    if (text == null) {
      return null;
    }
    String t = text.strip();
    if (t.equals("0")) {
      return Duration.ZERO;
    }
    if (!WHOLE.matcher(t).matches()) {
      return null;
    }
    Duration d = Duration.ZERO;
    Matcher m = PART.matcher(t);
    while (m.find()) {
      long n = Long.parseLong(m.group(1));
      d =
          d.plus(
              switch (m.group(2)) {
                case "d" -> Duration.ofDays(n);
                case "h" -> Duration.ofHours(n);
                case "m" -> Duration.ofMinutes(n);
                default -> Duration.ofSeconds(n);
              });
    }
    return d;
  }

  /** The canonical text of a whole number of seconds ({@code 0s} for zero). */
  public static String format(Duration d) {
    long s = d.toSeconds();
    if (s == 0) {
      return "0s";
    }
    StringBuilder b = new StringBuilder();
    long days = s / 86_400;
    s %= 86_400;
    long hours = s / 3_600;
    s %= 3_600;
    long minutes = s / 60;
    s %= 60;
    if (days > 0) {
      b.append(days).append('d');
    }
    if (hours > 0) {
      b.append(hours).append('h');
    }
    if (minutes > 0) {
      b.append(minutes).append('m');
    }
    if (s > 0) {
      b.append(s).append('s');
    }
    return b.toString();
  }

  /** A human text for messages ({@code 5m 10s}, {@code 1h 2m}, {@code 30s}). */
  public static String human(Duration d) {
    long s = Math.max(0, d.toSeconds());
    if (s < 60) {
      return s + "s";
    }
    long h = s / 3_600;
    long m = (s % 3_600) / 60;
    long sec = s % 60;
    if (h > 0) {
      return m == 0 ? h + "h" : h + "h " + m + "m";
    }
    return sec == 0 ? m + "m" : m + "m " + sec + "s";
  }
}
