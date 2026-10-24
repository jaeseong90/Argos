/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.schema;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lexical helpers for ClickHouse SQL scripts: statement splitting and placeholder substitution that
 * respect comments ({@code --}, block comments), string literals and quoted identifiers.
 *
 * <p>Placeholders ({@code {name}}) are substituted in SQL code only; text in comments and quotes is
 * left as written, so comments may mention placeholders freely.
 */
public final class SqlScript {

  /** Placeholder syntax: {@code {db}}, {@code {retention.xlog-days}}, {@code {rollup:xlog}}. */
  static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z][a-z0-9_.:-]*)}");

  private SqlScript() {}

  /** Kind of a lexical segment. */
  enum Kind {
    CODE,
    COMMENT,
    QUOTED
  }

  /** A lexical segment of a script. */
  record Segment(Kind kind, String text) {}

  /** Splits a script into lexical segments. Unterminated quotes and comments are an error. */
  static List<Segment> lex(String sql) {
    List<Segment> segments = new ArrayList<>();
    StringBuilder code = new StringBuilder();
    int i = 0;
    int n = sql.length();
    while (i < n) {
      char c = sql.charAt(i);
      if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
        int end = sql.indexOf('\n', i);
        end = end < 0 ? n : end;
        flush(segments, code);
        segments.add(new Segment(Kind.COMMENT, sql.substring(i, end)));
        i = end;
      } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
        int end = sql.indexOf("*/", i + 2);
        if (end < 0) {
          throw new IllegalArgumentException("Unterminated block comment at offset " + i);
        }
        flush(segments, code);
        segments.add(new Segment(Kind.COMMENT, sql.substring(i, end + 2)));
        i = end + 2;
      } else if (c == '\'' || c == '"' || c == '`') {
        int end = closingQuote(sql, i, c);
        flush(segments, code);
        segments.add(new Segment(Kind.QUOTED, sql.substring(i, end + 1)));
        i = end + 1;
      } else {
        code.append(c);
        i++;
      }
    }
    flush(segments, code);
    return segments;
  }

  private static int closingQuote(String sql, int start, char quote) {
    int i = start + 1;
    while (i < sql.length()) {
      char c = sql.charAt(i);
      if (c == '\\') {
        i += 2;
      } else if (c == quote) {
        if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) {
          i += 2; // doubled quote
        } else {
          return i;
        }
      } else {
        i++;
      }
    }
    throw new IllegalArgumentException("Unterminated quote " + quote + " at offset " + start);
  }

  private static void flush(List<Segment> segments, StringBuilder code) {
    if (code.length() > 0) {
      segments.add(new Segment(Kind.CODE, code.toString()));
      code.setLength(0);
    }
  }

  /**
   * Splits a script into statements at semicolons outside comments and quotes. Statements are
   * trimmed; statements that contain only comments and whitespace are dropped. Comments inside a
   * statement are kept.
   */
  public static List<String> split(String sql) {
    List<String> statements = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    boolean hasCode = false;
    for (Segment s : lex(sql)) {
      if (s.kind() != Kind.CODE) {
        current.append(s.text());
        hasCode |= s.kind() == Kind.QUOTED;
        continue;
      }
      String text = s.text();
      int from = 0;
      int semicolon;
      while ((semicolon = text.indexOf(';', from)) >= 0) {
        String part = text.substring(from, semicolon);
        current.append(part);
        hasCode |= !part.isBlank();
        if (hasCode) {
          statements.add(trimStatement(current.toString()));
        }
        current.setLength(0);
        hasCode = false;
        from = semicolon + 1;
      }
      String rest = text.substring(from);
      current.append(rest);
      hasCode |= !rest.isBlank();
    }
    if (hasCode) {
      statements.add(trimStatement(current.toString()));
    }
    return statements;
  }

  /** Strips leading comment lines and surrounding whitespace of a statement. */
  private static String trimStatement(String statement) {
    List<Segment> segments = lex(statement);
    int first = 0;
    while (first < segments.size()
        && (segments.get(first).kind() == Kind.COMMENT
            || (segments.get(first).kind() == Kind.CODE && segments.get(first).text().isBlank()))) {
      first++;
    }
    StringBuilder sb = new StringBuilder();
    for (int i = first; i < segments.size(); i++) {
      sb.append(segments.get(i).text());
    }
    return sb.toString().strip();
  }

  /** Removes all comments (statement text for comparisons and lint rules). */
  public static String withoutComments(String sql) {
    StringBuilder sb = new StringBuilder();
    for (Segment s : lex(sql)) {
      if (s.kind() != Kind.COMMENT) {
        sb.append(s.text());
      }
    }
    return sb.toString();
  }

  /** Placeholder names used in SQL code (not in comments or quotes), in order of appearance. */
  public static Set<String> placeholders(String sql) {
    Set<String> names = new LinkedHashSet<>();
    for (Segment s : lex(sql)) {
      if (s.kind() == Kind.CODE) {
        Matcher m = PLACEHOLDER.matcher(s.text());
        while (m.find()) {
          names.add(m.group(1));
        }
      }
    }
    return names;
  }

  /**
   * Replaces every placeholder in SQL code with {@code resolver.apply(name)}. The replacement text
   * is inserted as is (it is not scanned again).
   */
  public static String replacePlaceholders(String sql, Function<String, String> resolver) {
    StringBuilder out = new StringBuilder(sql.length());
    for (Segment s : lex(sql)) {
      if (s.kind() != Kind.CODE) {
        out.append(s.text());
        continue;
      }
      Matcher m = PLACEHOLDER.matcher(s.text());
      int last = 0;
      while (m.find()) {
        out.append(s.text(), last, m.start());
        out.append(resolver.apply(m.group(1)));
        last = m.end();
      }
      out.append(s.text(), last, s.text().length());
    }
    return out.toString();
  }
}
