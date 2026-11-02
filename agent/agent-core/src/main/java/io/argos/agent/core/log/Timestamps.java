/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.log;

import java.util.TimeZone;

/**
 * ISO-8601 local timestamps with offset ({@code 2026-10-24T14:23:45.123+09:00}) without java.time
 * or Calendar: loading those at premain cost about 100 ms on Java 8 (N-30). Thread-safe (no state
 * besides the zone).
 */
final class Timestamps {
  private static final long MILLIS_PER_DAY = 86_400_000L;

  private final TimeZone zone;

  Timestamps(TimeZone zone) {
    this.zone = zone;
  }

  String format(long epochMillis) {
    StringBuilder out = new StringBuilder(29);
    append(out, epochMillis);
    return out.toString();
  }

  void append(StringBuilder out, long epochMillis) {
    int offset = zone.getOffset(epochMillis);
    long local = epochMillis + offset;
    long days = Math.floorDiv(local, MILLIS_PER_DAY);
    long millisOfDay = Math.floorMod(local, MILLIS_PER_DAY);
    // Civil date from days since 1970-01-01 (proleptic Gregorian calendar).
    long z = days + 719_468;
    long era = (z >= 0 ? z : z - 146_096) / 146_097;
    long dayOfEra = z - era * 146_097;
    long yearOfEra = (dayOfEra - dayOfEra / 1460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365;
    long year = yearOfEra + era * 400;
    long dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100);
    long monthIndex = (5 * dayOfYear + 2) / 153;
    long day = dayOfYear - (153 * monthIndex + 2) / 5 + 1;
    long month = monthIndex < 10 ? monthIndex + 3 : monthIndex - 9;
    if (month <= 2) {
      year++;
    }
    pad(out, year, 4).append('-');
    pad(out, month, 2).append('-');
    pad(out, day, 2).append('T');
    pad(out, millisOfDay / 3_600_000L, 2).append(':');
    pad(out, (millisOfDay / 60_000L) % 60, 2).append(':');
    pad(out, (millisOfDay / 1000L) % 60, 2).append('.');
    pad(out, millisOfDay % 1000L, 3);
    if (offset == 0) {
      out.append('Z');
    } else {
      int minutes = Math.abs(offset) / 60_000;
      out.append(offset < 0 ? '-' : '+');
      pad(out, minutes / 60, 2).append(':');
      pad(out, minutes % 60, 2);
    }
  }

  private static StringBuilder pad(StringBuilder out, long value, int width) {
    String digits = Long.toString(value);
    for (int i = digits.length(); i < width; i++) {
      out.append('0');
    }
    return out.append(digits);
  }
}
