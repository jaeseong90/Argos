/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.load;

import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * k6-style durations: {@code 500ms}, {@code 30s}, {@code 1m30s}, {@code 2h}; a bare number is s.
 */
final class Durations {

  private static final Pattern PART = Pattern.compile("(\\d{1,9})(ms|s|m|h)");
  private static final Pattern SECONDS = Pattern.compile("\\d{1,9}");

  private Durations() {}

  static Duration parse(String text) {
    String value = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
    if (value.isEmpty()) {
      throw new IllegalArgumentException("empty duration");
    }
    if (SECONDS.matcher(value).matches()) {
      return Duration.ofSeconds(Long.parseLong(value));
    }
    Matcher part = PART.matcher(value);
    Duration total = Duration.ZERO;
    int position = 0;
    while (position < value.length()) {
      if (!part.find(position) || part.start() != position) {
        throw new IllegalArgumentException(
            "bad duration '" + text + "' (examples: 500ms, 30s, 1m30s, 2h)");
      }
      long amount = Long.parseLong(part.group(1));
      total =
          switch (part.group(2)) {
            case "ms" -> total.plusMillis(amount);
            case "s" -> total.plusSeconds(amount);
            case "m" -> total.plusMinutes(amount);
            default -> total.plusHours(amount);
          };
      position = part.end();
    }
    return total;
  }

  /** Seconds with millisecond precision, for reports. */
  static double seconds(Duration duration) {
    return duration.toMillis() / 1000.0;
  }
}
