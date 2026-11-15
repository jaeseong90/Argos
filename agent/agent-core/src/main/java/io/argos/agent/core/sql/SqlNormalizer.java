/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.sql;

/**
 * SQL normalizer (10 §9.4, D-22, SEC-35, DSN-10-17): replaces every literal with {@code ?}, so that
 * no value of a statement leaves the agent, and gives statements that differ only in literals,
 * comments or whitespace the same text (and so the same dictionary id).
 *
 * <p>Rules ({@link SqlTokenizer} reads the tokens, {@link SqlDialect} the vendor differences):
 *
 * <ul>
 *   <li>String literals {@code '...'} and the prefixed forms {@code N'..'}, {@code E'..'}, {@code
 *       X'..'}, {@code B'..'}, Oracle {@code q'[..]'}, PostgreSQL {@code $tag$...$tag$}, and {@code
 *       "..."} in the MySQL dialects, become {@code ?}; an unterminated literal runs to the end
 *       (masked, never copied).
 *   <li>Numbers (integer, decimal, exponent, {@code 0x} hex, {@code 0b} binary; a sign only after
 *       an operator, {@code (} or {@code ,}) become {@code ?}. Digits inside identifiers ({@code
 *       col1}, {@code t_2024}) stay.
 *   <li>Quoted identifiers ({@code "..."} in the ANSI dialects, {@code `...`}, {@code [...]}) stay
 *       as they are.
 *   <li>Comments are removed: line comments ({@code --}, {@code #} in the MySQL dialects) and block
 *       comments (nested ones counted), optimizer hints {@code /*+ ..} included unless {@code
 *       sql.keep-hints}, which keeps a hint with the literals inside it masked.
 *   <li>Bind markers {@code ?}, {@code ?1}, {@code :name}, {@code :1}, {@code $1}, {@code @p1},
 *       {@code @name} stay.
 *   <li>Runs of whitespace and comments become one space; leading and trailing space is dropped;
 *       case is kept; {@code {call proc(?)}} and PL/SQL {@code BEGIN ... END;} blocks only lose
 *       their literals.
 *   <li>{@code IN (?, ?, ...)} becomes {@code IN (?)}; {@code VALUES (?, ?), (?, ?)} keeps its
 *       first row.
 * </ul>
 *
 * <p>Bounds and fail-safety (DSN-10-17): at most {@code sql.max-length} characters are read (the
 * rest is cut, {@link #truncated(String)}); the work is bounded by {@value #BUDGET_PER_CHAR}
 * character reads per character plus {@value #BUDGET_BASE} (no regular expression, no
 * backtracking), which keeps a 64 KiB statement within the 1 ms budget. Any failure (exception,
 * budget overrun, an ambiguous or unterminated quote) yields {@link #UNPARSED}; the raw text is
 * never returned. Normalizer version {@value #NORM_VERSION} ({@code DictionaryEntry.norm_version},
 * D-22).
 */
public final class SqlNormalizer {
  /** {@code sql.max-length} default (10 §15): characters read from a statement. */
  public static final int MAX_LENGTH = 65_536;

  /** Text of a statement that could not be normalized (10 §9.4 "실패 안전"). */
  public static final String UNPARSED = "<unparsed>";

  /** The version of these rules ({@code DictionaryEntry.norm_version}, D-22). */
  public static final int NORM_VERSION = 1;

  /** Character reads allowed per character read from the statement. */
  static final int BUDGET_PER_CHAR = 8;

  /** Character reads allowed on top of the per-character budget. */
  static final int BUDGET_BASE = 4096;

  private static final SqlNormalizer DEFAULTS = new SqlNormalizer(true, MAX_LENGTH, false);

  private final boolean backslashEscape;
  private final int maxLength;
  private final boolean keepHints;
  private final int budgetPerChar;

  /**
   * @param backslashEscape {@code sql.backslash-escape}: a backslash escapes the next character in
   *     a string literal of the MySQL dialects
   * @param maxLength {@code sql.max-length}: characters read, the rest is cut
   */
  public SqlNormalizer(boolean backslashEscape, int maxLength) {
    this(backslashEscape, maxLength, false);
  }

  /**
   * @param backslashEscape {@code sql.backslash-escape}
   * @param maxLength {@code sql.max-length}
   * @param keepHints {@code sql.keep-hints}: optimizer hints stay (their literals masked)
   */
  public SqlNormalizer(boolean backslashEscape, int maxLength, boolean keepHints) {
    this(backslashEscape, maxLength, keepHints, BUDGET_PER_CHAR);
  }

  /** Tests: a different work budget (0 injects a budget failure into every statement). */
  SqlNormalizer(boolean backslashEscape, int maxLength, boolean keepHints, int budgetPerChar) {
    this.backslashEscape = backslashEscape;
    this.maxLength = Math.max(1, Math.min(MAX_LENGTH, maxLength));
    this.keepHints = keepHints;
    this.budgetPerChar = Math.max(0, budgetPerChar);
  }

  /** The documented defaults: backslash escapes on, {@value #MAX_LENGTH} characters, no hints. */
  public static SqlNormalizer defaults() {
    return DEFAULTS;
  }

  /** {@code sql.max-length} of this normalizer. */
  public int maxLength() {
    return maxLength;
  }

  /** {@code sql.backslash-escape} of this normalizer. */
  public boolean backslashEscape() {
    return backslashEscape;
  }

  /** {@code sql.keep-hints} of this normalizer. */
  public boolean keepHints() {
    return keepHints;
  }

  /** True when {@code sql} is longer than {@code sql.max-length} (the {@code truncated} flag). */
  public boolean truncated(String sql) {
    return sql != null && sql.length() > maxLength;
  }

  /** The normalized text of {@code sql} in the {@link SqlDialect#UNKNOWN} dialect. */
  public String normalize(String sql) {
    return normalize(sql, SqlDialect.UNKNOWN);
  }

  /**
   * The normalized text of {@code sql} read in {@code dialect} ({@link SqlDialect}); null for null,
   * {@link #UNPARSED} on any failure.
   */
  public String normalize(String sql, int dialect) {
    if (sql == null) {
      return null;
    }
    try {
      int length = Math.min(sql.length(), maxLength);
      long budget = (long) budgetPerChar * length + (budgetPerChar == 0 ? 0 : BUDGET_BASE);
      SqlTokenizer tokens =
          new SqlTokenizer(sql, 0, length, dialect, backslashEscape, keepHints, budget);
      StringBuilder out = new StringBuilder(Math.min(length, 4096) + 16);
      emit(sql, tokens, out, dialect, budget);
      String text = out.toString();
      // The list pass reads every output character at most three times (10 §9.4 budget).
      tokens.charge(3L * text.length());
      return collapseLists(text);
    } catch (RuntimeException | StackOverflowError e) {
      return UNPARSED;
    }
  }

  /**
   * Writes the tokens of {@code tokens} to {@code out}: literals as {@code ?}, gaps as one space,
   * every other token as written. Adjacent kept tokens are copied as one run of the input.
   */
  private void emit(String sql, SqlTokenizer tokens, StringBuilder out, int dialect, long budget) {
    int kind;
    int runStart = -1;
    int runEnd = -1;
    while ((kind = tokens.next()) != SqlTokenizer.END) {
      boolean gap = tokens.gap();
      boolean kept =
          kind != SqlTokenizer.STRING && kind != SqlTokenizer.NUMBER && kind != SqlTokenizer.HINT;
      if (kept && !gap && runStart >= 0 && tokens.start() == runEnd) {
        runEnd = tokens.end();
        continue;
      }
      if (runStart >= 0) {
        out.append(sql, runStart, runEnd);
        runStart = -1;
      }
      if (gap && out.length() > 0) {
        out.append(' ');
      }
      if (kept) {
        runStart = tokens.start();
        runEnd = tokens.end();
      } else if (kind == SqlTokenizer.HINT) {
        // sql.keep-hints: the hint stays, read like a statement (its literals masked).
        SqlTokenizer body =
            new SqlTokenizer(
                sql,
                tokens.hintBodyStart(),
                tokens.hintBodyEnd(),
                dialect,
                backslashEscape,
                false,
                Math.max(0, budget - tokens.work()));
        StringBuilder hint = new StringBuilder();
        emit(sql, body, hint, dialect, budget);
        tokens.charge(body.work());
        out.append("/*+");
        if (hint.length() > 0) {
          out.append(' ').append(hint);
        }
        out.append(" */");
      } else {
        out.append('?');
      }
    }
    if (runStart >= 0) {
      out.append(sql, runStart, runEnd);
    }
  }

  // --- IN lists and multi-row VALUES (on the literal-free text) ---------------------------------

  static String collapseLists(String text) {
    if (text.indexOf('(') < 0) {
      return text;
    }
    StringBuilder out = new StringBuilder(text.length());
    int i = 0;
    int copied = 0;
    int length = text.length();
    while (i < length) {
      char c = text.charAt(i);
      if ((c == 'i' || c == 'I') && isWordAt(text, i, "IN")) {
        int open = skipSpaces(text, i + 2);
        int close = questionGroupEnd(text, open);
        if (close > 0) {
          out.append(text, copied, i + 2).append(" (?)");
          i = close;
          copied = i;
          continue;
        }
      } else if ((c == 'v' || c == 'V') && isWordAt(text, i, "VALUES")) {
        int open = skipSpaces(text, i + 6);
        int close = questionGroupEnd(text, open);
        if (close > 0) {
          out.append(text, copied, close);
          i = close;
          // Drop the following rows that hold bind markers only.
          while (true) {
            int comma = skipSpaces(text, i);
            if (comma >= length || text.charAt(comma) != ',') {
              break;
            }
            int nextRow = skipSpaces(text, comma + 1);
            int nextClose = questionGroupEnd(text, nextRow);
            if (nextClose < 0) {
              break;
            }
            i = nextClose;
          }
          copied = i;
          continue;
        }
      }
      i++;
    }
    if (copied == 0) {
      return text;
    }
    return out.append(text, copied, length).toString();
  }

  /** True when the word {@code word} (ASCII, any case) starts at {@code i} on word boundaries. */
  private static boolean isWordAt(String text, int i, String word) {
    int end = i + word.length();
    if (end > text.length() || !text.regionMatches(true, i, word, 0, word.length())) {
      return false;
    }
    if (i > 0 && SqlTokenizer.isIdentifierPart(text.charAt(i - 1))) {
      return false;
    }
    return end == text.length() || !SqlTokenizer.isIdentifierPart(text.charAt(end));
  }

  private static int skipSpaces(String text, int i) {
    int j = i;
    while (j < text.length() && text.charAt(j) == ' ') {
      j++;
    }
    return j;
  }

  /**
   * End (after ')') of a group {@code ( ?, ?, ... )} that holds bind markers {@code ?} only, or -1.
   */
  private static int questionGroupEnd(String text, int open) {
    int length = text.length();
    if (open >= length || text.charAt(open) != '(') {
      return -1;
    }
    boolean expectMarker = true;
    int i = open + 1;
    while (i < length) {
      char c = text.charAt(i);
      if (c == ' ') {
        i++;
      } else if (expectMarker && c == '?' && !(i + 1 < length && isDigitAt(text, i + 1))) {
        expectMarker = false;
        i++;
      } else if (!expectMarker && c == ',') {
        expectMarker = true;
        i++;
      } else if (!expectMarker && c == ')') {
        return i + 1;
      } else {
        return -1;
      }
    }
    return -1;
  }

  private static boolean isDigitAt(String text, int i) {
    return SqlTokenizer.isDigit(text.charAt(i));
  }
}
