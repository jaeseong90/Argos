/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.transactions;

import java.util.ArrayList;
import java.util.List;

/**
 * The {@code masked_by_role} form of bind parameters (14 §4.6, §7.3, DSN-14-16, 02 §13 C-13, 16
 * §6.7): a viewer without {@code pii.view} sees the type and length of each value of a {@code full}
 * mode step, never the value. The agent writes the step detail as {@code <index>=<value>} separated
 * by {@code ", "} ({@code SqlState.paramDetail}): text values quoted with {@code '} ({@code ''}
 * inside), numbers and booleans bare, {@code NULL}, {@code [byte[](n)]} and {@code <Type>}.
 *
 * <ul>
 *   <li>quoted text becomes {@code [String(n)]} (n = characters without the quotes);
 *   <li>a number becomes {@code [Number(n)]}, {@code true}/{@code false} {@code [Boolean(n)]};
 *   <li>{@code NULL}, {@code [..]} and {@code <..>} stay (they carry no value);
 *   <li>anything else becomes {@code [masked]}: an unreadable detail never leaks.
 * </ul>
 */
public final class BindParams {

  private BindParams() {}

  /** The masked form of a step detail; null stays null. */
  public static String mask(String detail) {
    if (detail == null || detail.isEmpty()) {
      return detail;
    }
    List<String> out = new ArrayList<>();
    int i = 0;
    int n = detail.length();
    while (i < n) {
      int eq = indexIndex(detail, i);
      if (eq < 0) {
        out.add("[masked]");
        break;
      }
      String index = detail.substring(i, eq);
      int valueStart = eq + 1;
      int valueEnd;
      String masked;
      if (valueStart < n && detail.charAt(valueStart) == '\'') {
        int j = valueStart + 1;
        int chars = 0;
        boolean closed = false;
        while (j < n) {
          char c = detail.charAt(j);
          if (c == '\'') {
            if (j + 1 < n && detail.charAt(j + 1) == '\'') {
              chars++;
              j += 2;
              continue;
            }
            closed = true;
            j++;
            break;
          }
          chars++;
          j++;
        }
        valueEnd = j;
        masked = closed ? "[String(" + chars + ")]" : "[masked]";
      } else {
        int sep = detail.indexOf(", ", valueStart);
        valueEnd = sep < 0 ? n : sep;
        masked = bare(detail.substring(valueStart, valueEnd));
      }
      out.add(index + "=" + masked);
      if (valueEnd >= n) {
        break;
      }
      if (!detail.startsWith(", ", valueEnd)) {
        out.add("[masked]");
        break;
      }
      i = valueEnd + 2;
    }
    return String.join(", ", out);
  }

  /** The position of {@code =} after the digits of an index at {@code from}, or -1. */
  private static int indexIndex(String detail, int from) {
    int j = from;
    while (j < detail.length() && Character.isDigit(detail.charAt(j)) && j - from < 4) {
      j++;
    }
    return j > from && j < detail.length() && detail.charAt(j) == '=' ? j : -1;
  }

  private static String bare(String value) {
    if (value.equals("NULL")) {
      return value;
    }
    if (value.length() >= 2
        && ((value.startsWith("[") && value.endsWith("]"))
            || (value.startsWith("<") && value.endsWith(">")))) {
      return value;
    }
    if (value.equals("true") || value.equals("false")) {
      return "[Boolean(" + value.length() + ")]";
    }
    if (!value.isEmpty() && value.matches("-?[0-9]+(\\.[0-9]+)?([eE][-+]?[0-9]+)?")) {
      return "[Number(" + value.length() + ")]";
    }
    return "[masked]";
  }
}
