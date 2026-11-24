/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.check;

import io.argos.agent.core.config.TokenHolder;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The {@code --check} result (10 §13.3): the rows, the exit code (0 when every row is PASS, WARN,
 * SKIP or INFO, 1 when one is FAIL) and the two output forms, a table for people and {@code --json}
 * for scripts.
 *
 * <p>Every text that leaves this class goes through {@link #scrub}: the configured token value and
 * every token of the 16 §2.1 format become {@value TokenHolder#MASK} (SEC-06, ADR-0010 C-94) and
 * control characters become {@code ?}, whatever a row's detail holds (a server message, a file
 * name, an exception message).
 *
 * <p>JSON form, one object: {@code {"agent":{"version","commit","protocol"},"result":
 * "PASS|WARN|FAIL","exit_code":n,"summary":{"pass","warn","fail","skip","info"},"checks":[{"item":
 * n,"check":"name","status":"PASS|WARN|FAIL|SKIP|INFO","detail":"...","notes":["..."]}],
 * "side_effects":["..."],"configuration":["key = value (source)"]}}. {@code configuration} lists
 * the keys that are not at their documented default, {@code key = value (source)} with the source
 * {@code file}, {@code sysprop} or {@code env} (the form of self-diagnostics); the table prints
 * them after the rows.
 */
final class CheckReport {
  static final int EXIT_OK = 0;
  static final int EXIT_FAIL = 1;
  static final int EXIT_USAGE = 2;

  /** Upper bound of rows (the class path rows are bounded before, this is the last guard). */
  static final int MAX_ROWS = 200;

  private final String version;
  private final String commit;
  private final String protocol;
  private final String secret;
  private final List<CheckRow> rows = new ArrayList<CheckRow>();
  private final List<String> sideEffects = new ArrayList<String>();
  private final List<String> configuration = new ArrayList<String>();

  /** Upper bound of configuration lines (the key registry has fewer keys). */
  static final int MAX_CONFIGURATION_LINES = 300;

  /**
   * @param secret the configured token value, masked wherever it would appear (null for none)
   */
  CheckReport(String version, String commit, String protocol, String secret) {
    this.version = version;
    this.commit = commit;
    this.protocol = protocol;
    this.secret = secret;
  }

  void add(CheckRow row) {
    if (rows.size() < MAX_ROWS) {
      rows.add(row);
    }
  }

  void addAll(List<CheckRow> more) {
    for (CheckRow row : more) {
      add(row);
    }
  }

  void sideEffect(String text) {
    if (sideEffects.size() < 8) {
      sideEffects.add(text);
    }
  }

  void configuration(List<String> lines) {
    for (String line : lines) {
      if (configuration.size() < MAX_CONFIGURATION_LINES) {
        configuration.add(line);
      }
    }
  }

  List<CheckRow> rows() {
    return Collections.unmodifiableList(rows);
  }

  int count(CheckRow.Status status) {
    int n = 0;
    for (CheckRow row : rows) {
      if (row.status == status) {
        n++;
      }
    }
    return n;
  }

  /** 1 when a row failed, else 0 (10 §13.3). */
  int exitCode() {
    return count(CheckRow.Status.FAIL) > 0 ? EXIT_FAIL : EXIT_OK;
  }

  /** FAIL, WARN or PASS: the worst status of the rows (SKIP and INFO count as PASS). */
  String result() {
    if (count(CheckRow.Status.FAIL) > 0) {
      return "FAIL";
    }
    return count(CheckRow.Status.WARN) > 0 ? "WARN" : "PASS";
  }

  String versionLine() {
    return "Argos agent " + version + " (commit " + commit + ", protocol " + protocol + ")";
  }

  // --- table -------------------------------------------------------------------------------------

  void printTable(PrintStream out) {
    out.println(scrub(versionLine()));
    int nameWidth = "CHECK".length();
    for (CheckRow row : rows) {
      nameWidth = Math.max(nameWidth, row.name.length());
    }
    String header = pad(" #", 3) + "  " + pad("CHECK", nameWidth) + "  " + pad("RESULT", 6) + "  ";
    out.println(header + "DETAIL");
    String indent = spaces(header.length());
    for (CheckRow row : rows) {
      out.println(
          pad(padLeft(Integer.toString(row.item), 2), 3)
              + "  "
              + pad(scrub(row.name), nameWidth)
              + "  "
              + pad(row.status.name(), 6)
              + "  "
              + scrub(row.detail));
      for (String note : row.notes) {
        out.println(indent + "- " + scrub(note));
      }
    }
    out.println(
        "Result: "
            + result()
            + " ("
            + count(CheckRow.Status.PASS)
            + " passed, "
            + count(CheckRow.Status.WARN)
            + " warnings, "
            + count(CheckRow.Status.FAIL)
            + " failed, "
            + count(CheckRow.Status.SKIP)
            + " skipped); exit code "
            + exitCode());
    for (String effect : sideEffects) {
      out.println("Note: " + scrub(effect));
    }
    if (!configuration.isEmpty()) {
      out.println("Configuration (keys not at their default):");
      for (String line : configuration) {
        out.println("  " + scrub(line));
      }
    }
    out.flush();
  }

  // --- JSON --------------------------------------------------------------------------------------

  void printJson(PrintStream out) {
    out.println(json());
    out.flush();
  }

  String json() {
    StringBuilder b = new StringBuilder(1024);
    b.append("{\"agent\":{\"version\":");
    string(b, version);
    b.append(",\"commit\":");
    string(b, commit);
    b.append(",\"protocol\":");
    string(b, protocol);
    b.append("},\"result\":");
    string(b, result());
    b.append(",\"exit_code\":").append(exitCode());
    b.append(",\"summary\":{\"pass\":")
        .append(count(CheckRow.Status.PASS))
        .append(",\"warn\":")
        .append(count(CheckRow.Status.WARN))
        .append(",\"fail\":")
        .append(count(CheckRow.Status.FAIL))
        .append(",\"skip\":")
        .append(count(CheckRow.Status.SKIP))
        .append(",\"info\":")
        .append(count(CheckRow.Status.INFO))
        .append('}');
    b.append(",\"checks\":[");
    for (int i = 0; i < rows.size(); i++) {
      CheckRow row = rows.get(i);
      if (i > 0) {
        b.append(',');
      }
      b.append("{\"item\":").append(row.item).append(",\"check\":");
      string(b, row.name);
      b.append(",\"status\":");
      string(b, row.status.name());
      b.append(",\"detail\":");
      string(b, row.detail);
      b.append(",\"notes\":[");
      for (int n = 0; n < row.notes.size(); n++) {
        if (n > 0) {
          b.append(',');
        }
        string(b, row.notes.get(n));
      }
      b.append("]}");
    }
    b.append("],\"side_effects\":[");
    for (int i = 0; i < sideEffects.size(); i++) {
      if (i > 0) {
        b.append(',');
      }
      string(b, sideEffects.get(i));
    }
    b.append("],\"configuration\":[");
    for (int i = 0; i < configuration.size(); i++) {
      if (i > 0) {
        b.append(',');
      }
      string(b, configuration.get(i));
    }
    b.append("]}");
    return b.toString();
  }

  private void string(StringBuilder b, String raw) {
    String value = scrub(raw);
    b.append('"');
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '"':
          b.append("\\\"");
          break;
        case '\\':
          b.append("\\\\");
          break;
        default:
          if (c < 0x20 || c == 0x2028 || c == 0x2029) {
            b.append(String.format("\\u%04x", (int) c));
          } else {
            b.append(c);
          }
      }
    }
    b.append('"');
  }

  // --- masking -----------------------------------------------------------------------------------

  /**
   * {@code text} with the configured token and every 16 §2.1 token replaced by {@value
   * TokenHolder#MASK} and control characters replaced by {@code ?}.
   */
  String scrub(String text) {
    if (text == null) {
      return "";
    }
    String value = text;
    if (secret != null && secret.length() >= 4 && value.contains(secret)) {
      value = value.replace(secret, TokenHolder.MASK);
    }
    return clean(TokenHolder.maskTokens(value));
  }

  /** {@code text} with control characters replaced by {@code ?}. */
  static String clean(String text) {
    StringBuilder out = new StringBuilder(text.length());
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      out.append(Character.isISOControl(c) ? '?' : c);
    }
    return out.toString();
  }

  private static String pad(String text, int width) {
    return text.length() >= width ? text : text + spaces(width - text.length());
  }

  private static String padLeft(String text, int width) {
    return text.length() >= width ? text : spaces(width - text.length()) + text;
  }

  private static String spaces(int n) {
    StringBuilder b = new StringBuilder(n);
    for (int i = 0; i < n; i++) {
      b.append(' ');
    }
    return b.toString();
  }
}
