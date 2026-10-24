/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.queries;

import java.util.ArrayList;
import java.util.List;

/** TabSeparated output of ClickHouse: rows of fields, escaped (raw) or unescaped. */
public final class Tsv {

  private Tsv() {}

  /** Rows with unescaped fields ({@code \t \n \\ \'} and the other ClickHouse escapes). */
  public static List<List<String>> rows(String body) {
    List<List<String>> rows = new ArrayList<>();
    for (List<String> raw : rawRows(body)) {
      List<String> fields = new ArrayList<>(raw.size());
      for (String f : raw) {
        fields.add(unescape(f));
      }
      rows.add(fields);
    }
    return rows;
  }

  /**
   * Rows with the fields exactly as sent: the escaped text form, which is also the form ClickHouse
   * parses query parameter values in.
   */
  public static List<List<String>> rawRows(String body) {
    List<List<String>> rows = new ArrayList<>();
    if (body.isEmpty()) {
      return rows;
    }
    for (String line : body.split("\n", -1)) {
      if (line.isEmpty()) {
        continue;
      }
      List<String> fields = new ArrayList<>();
      int start = 0;
      for (int i = 0; i <= line.length(); i++) {
        if (i == line.length() || line.charAt(i) == '\t') {
          fields.add(line.substring(start, i));
          start = i + 1;
        }
      }
      rows.add(fields);
    }
    return rows;
  }

  /** Number of rows of a TabSeparated body (non-empty lines). */
  public static long count(String body) {
    long n = 0;
    int start = 0;
    for (int i = 0; i <= body.length(); i++) {
      if (i == body.length() || body.charAt(i) == '\n') {
        if (i > start) {
          n++;
        }
        start = i + 1;
      }
    }
    return n;
  }

  static String unescape(String field) {
    if (field.indexOf('\\') < 0) {
      return field;
    }
    StringBuilder sb = new StringBuilder(field.length());
    for (int i = 0; i < field.length(); i++) {
      char c = field.charAt(i);
      if (c != '\\' || i + 1 == field.length()) {
        sb.append(c);
        continue;
      }
      char next = field.charAt(++i);
      switch (next) {
        case 't' -> sb.append('\t');
        case 'n' -> sb.append('\n');
        case 'r' -> sb.append('\r');
        case 'b' -> sb.append('\b');
        case 'f' -> sb.append('\f');
        case '0' -> sb.append('\0');
        default -> sb.append(next);
      }
    }
    return sb.toString();
  }
}
