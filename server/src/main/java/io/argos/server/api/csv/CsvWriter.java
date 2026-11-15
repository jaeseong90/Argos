/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.csv;

import io.argos.server.api.query.UntrustedStrings;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;

/**
 * The CSV format of the exports (14 §4.17, 16 §9.6, SEC-63, DSN-14-12):
 *
 * <ul>
 *   <li>UTF-8 with a byte order mark, lines end with CRLF;
 *   <li>every cell in double quotes, a double quote inside doubled;
 *   <li>a value that starts with {@code =}, {@code +}, {@code -}, {@code @}, a tab or a carriage
 *       return gets a leading {@code '} (formula injection: a spreadsheet then shows it as text);
 *   <li>line breaks inside a value become the two characters {@code \n} (14 §4.17), so that every
 *       row is one line;
 *   <li>the other control characters of a text become U+FFFD (DSN-14-13, {@link #clean});
 *   <li>null is an empty cell; numbers in plain notation (no exponent); times ISO-8601 with
 *       milliseconds, in UTC ({@code Z}) or, with a time zone, with its offset.
 * </ul>
 *
 * The prefix applies to every cell, numbers included: a negative number such as a delta is written
 * {@code "'-0.12"}, which a spreadsheet shows as text rather than computing it (safe default).
 */
public final class CsvWriter {

  /** UTF-8 byte order mark. */
  public static final String BOM = "﻿";

  /** Line end (16 §9.6). */
  public static final String CRLF = "\r\n";

  private static final DateTimeFormatter UTC =
      DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT)
          .withZone(ZoneOffset.UTC);

  private static final DateTimeFormatter OFFSET =
      DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT);

  private final ZoneId zone;

  /**
   * A writer.
   *
   * @param zone the time zone of time cells, or null for UTC
   */
  public CsvWriter(ZoneId zone) {
    this.zone = zone;
  }

  /** The whole file: BOM, header line, rows. */
  public byte[] write(CsvTable table) {
    StringBuilder out = new StringBuilder(BOM);
    line(out, table.header());
    for (List<Object> row : table.rows()) {
      line(out, row);
    }
    return out.toString().getBytes(StandardCharsets.UTF_8);
  }

  private void line(StringBuilder out, List<?> cells) {
    for (int i = 0; i < cells.size(); i++) {
      if (i > 0) {
        out.append(',');
      }
      cell(out, text(cells.get(i)));
    }
    out.append(CRLF);
  }

  /** The text of one value before quoting. */
  String text(Object value) {
    return switch (value) {
      case null -> "";
      case Instant t -> time(t);
      case Double d -> plain(d);
      case Float f -> plain(f.doubleValue());
      case BigDecimal b -> b.toPlainString();
      case String s -> clean(s);
      default -> String.valueOf(value);
    };
  }

  /**
   * DSN-14-13 rule 3 (14 §2.10) for a cell, the CSV half of what {@code UntrustedStringsModule}
   * does for JSON: control characters U+0000..U+001F become {@link UntrustedStrings#REPLACEMENT},
   * except tab, line feed and carriage return. The two line break characters stay for {@link
   * #cell}, which writes them as {@code \n} (14 §4.17) and prefixes a value that starts with a
   * carriage return; the formula prefix is decided on the cleaned value.
   */
  static String clean(String value) {
    int first = -1;
    for (int i = 0; i < value.length(); i++) {
      if (isControl(value.charAt(i))) {
        first = i;
        break;
      }
    }
    if (first < 0) {
      return value;
    }
    char[] chars = value.toCharArray();
    for (int i = first; i < chars.length; i++) {
      if (isControl(chars[i])) {
        chars[i] = UntrustedStrings.REPLACEMENT;
      }
    }
    return new String(chars);
  }

  private static boolean isControl(char c) {
    return c < 0x20 && c != '\t' && c != '\n' && c != '\r';
  }

  private String time(Instant t) {
    Instant ms = t.truncatedTo(ChronoUnit.MILLIS);
    return zone == null ? UTC.format(ms) : OFFSET.format(ms.atZone(zone));
  }

  private static String plain(double d) {
    if (Double.isNaN(d) || Double.isInfinite(d)) {
      return "";
    }
    return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
  }

  /** One quoted cell (the rules of the class comment). */
  static void cell(StringBuilder out, String value) {
    out.append('"');
    if (!value.isEmpty() && dangerous(value.charAt(0))) {
      out.append('\'');
    }
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == '"') {
        out.append("\"\"");
      } else if (c == '\r') {
        if (i + 1 < value.length() && value.charAt(i + 1) == '\n') {
          i++;
        }
        out.append("\\n");
      } else if (c == '\n') {
        out.append("\\n");
      } else {
        out.append(c);
      }
    }
    out.append('"');
  }

  private static boolean dangerous(char first) {
    return first == '='
        || first == '+'
        || first == '-'
        || first == '@'
        || first == '\t'
        || first == '\r';
  }
}
