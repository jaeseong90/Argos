/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.load;

import java.time.Duration;
import java.util.Locale;

/** Number formats of the report. */
final class Format {

  private Format() {}

  /** Bytes as {@code 1.23 GiB}, {@code 512.0 MiB}, ...; {@code n/a} when negative. */
  static String bytes(long bytes) {
    if (bytes < 0) {
      return "n/a";
    }
    double b = bytes;
    String[] units = {"B", "KiB", "MiB", "GiB", "TiB"};
    int u = 0;
    while (b >= 1024 && u < units.length - 1) {
      b /= 1024;
      u++;
    }
    return u == 0 ? bytes + " B" : String.format(Locale.ROOT, "%.2f %s", b, units[u]);
  }

  /** Seconds as {@code 123 ms} or {@code 4.56 s}; {@code n/a} for NaN. */
  static String seconds(double seconds) {
    if (Double.isNaN(seconds)) {
      return "n/a";
    }
    return seconds < 1
        ? String.format(Locale.ROOT, "%.0f ms", seconds * 1000)
        : String.format(Locale.ROOT, "%.2f s", seconds);
  }

  /** Milliseconds as {@code 123 ms}; {@code n/a} for NaN. */
  static String millis(double millis) {
    return Double.isNaN(millis) ? "n/a" : String.format(Locale.ROOT, "%.0f ms", millis);
  }

  /** A count with thousands separators. */
  static String count(long n) {
    return String.format(Locale.ROOT, "%,d", n);
  }

  /** A rate per second with thousands separators. */
  static String rate(double perSecond) {
    return String.format(Locale.ROOT, "%,.0f/s", perSecond);
  }

  /** {@code 1h}, {@code 5m}, {@code 90s}. */
  static String duration(Duration d) {
    long s = d.toSeconds();
    if (s % 3600 == 0 && s > 0) {
      return s / 3600 + " h";
    }
    if (s % 60 == 0 && s > 0) {
      return s / 60 + " min";
    }
    return s + " s";
  }

  /** A Markdown table cell (pipes escaped, new lines as spaces). */
  static String cell(String text) {
    return text.replace("|", "\\|").replace('\n', ' ');
  }
}
