/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.bench;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GC activity in the measured window from the app's {@code -Xlog:gc} file: pauses, pause time and
 * the bytes allocated between collections (the transient memory cost per request, 02 §12 A-3). The
 * window is given in JVM uptime seconds, read from the thread dumps around the window (the largest
 * {@code elapsed=} is the age of the oldest thread, which the JVM starts first).
 */
final class GcLog {
  private GcLog() {}

  private static final Pattern EVENT =
      Pattern.compile(
          "^\\[([0-9.]+)s\\]\\[info\\s*\\]\\[gc\\s*\\] GC\\((\\d+)\\) (Pause [A-Za-z]+.*?) (\\d+)([KMG])->(\\d+)([KMG])\\((\\d+)([KMG])\\) ([0-9.]+)ms$");
  private static final Pattern ELAPSED = Pattern.compile(" elapsed=([0-9.]+)s ");
  private static final Pattern LINES = Pattern.compile("\\R");

  /** JVM uptime in seconds at a {@code jcmd Thread.print}, NaN when no thread shows it. */
  static double uptimeOfThreadDump(String dump) {
    double max = Double.NaN;
    Matcher m = ELAPSED.matcher(dump);
    while (m.find()) {
      double v = Double.parseDouble(m.group(1));
      max = Double.isNaN(max) ? v : Math.max(max, v);
    }
    return max;
  }

  private static double mb(String value, String unit) {
    double v = Double.parseDouble(value);
    return switch (unit) {
      case "K" -> v / 1024;
      case "G" -> v * 1024;
      default -> v;
    };
  }

  /** Pauses with their time and the allocation between pauses inside [from, to] uptime seconds. */
  static Map<String, Object> window(String log, double from, double to) {
    int pauses = 0;
    int young = 0;
    double pauseMs = 0;
    double allocatedMb = 0;
    double previousAfter = Double.NaN;
    for (String line : LINES.split(log, -1)) {
      Matcher m = EVENT.matcher(line.trim());
      if (!m.matches()) {
        continue;
      }
      double at = Double.parseDouble(m.group(1));
      double before = mb(m.group(4), m.group(5));
      double after = mb(m.group(6), m.group(7));
      if (at >= from && at <= to) {
        pauses++;
        pauseMs += Double.parseDouble(m.group(10));
        if (m.group(3).startsWith("Pause Young")) {
          young++;
        }
        if (!Double.isNaN(previousAfter) && before >= previousAfter) {
          allocatedMb += before - previousAfter;
        }
      }
      previousAfter = after;
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("fromUptimeSeconds", from);
    out.put("toUptimeSeconds", to);
    out.put("pauses", pauses);
    out.put("youngPauses", young);
    out.put("pauseMsTotal", pauseMs);
    out.put("allocatedMbBetweenPauses", allocatedMb);
    return out;
  }
}
