/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.sql;

/**
 * SQL normalizer (10 §9.4, D-22, SEC-35): replaces every literal with {@code ?}, so that no value
 * of a statement leaves the agent, and gives statements that differ only in literals, comments or
 * whitespace the same text (and so the same dictionary id).
 *
 * <p>One pass over at most {@link #MAX_LENGTH} characters (the rest is cut), no regular
 * expressions, linear time. Token states of 10 §9.4:
 *
 * <ul>
 *   <li>String literals {@code '...'} ({@code ''} escape; {@code \'} when {@code backslashEscape},
 *       the MySQL default of {@code sql.backslash-escape}) and prefixed literals {@code N'..'},
 *       {@code E'..'}, {@code X'..'}, {@code B'..'} become {@code ?}; an unterminated literal runs
 *       to the end (masked, never copied).
 *   <li>PostgreSQL dollar quoting {@code $tag$...$tag$} becomes {@code ?}.
 *   <li>Numbers (integer, decimal, exponent, {@code 0x} hex; a sign only after an operator, {@code
 *       (} or {@code ,}) become {@code ?}. Digits inside identifiers ({@code col1}, {@code t_2024})
 *       stay.
 *   <li>Quoted identifiers {@code "..."}, {@code `...`} and {@code [...]} (identifier characters
 *       only) stay as they are.
 *   <li>Comments are removed: line comments ({@code --}, and {@code #} for MySQL, except {@code
 *       #name} and {@code ##name}, SQL Server temporary tables) and block comments (Oracle hints
 *       included, nested comments counted).
 *   <li>Bind markers {@code ?}, {@code ?1}, {@code :name}, {@code :1}, {@code $1}, {@code @p1},
 *       {@code @name} stay.
 *   <li>Runs of whitespace and comments become one space; leading and trailing space is dropped.
 *   <li>{@code IN (?, ?, ...)} becomes {@code IN (?)}; {@code VALUES (?, ?), (?, ?)} keeps its
 *       first row.
 * </ul>
 *
 * <p>Any failure yields {@link #UNPARSED} (the raw text is never returned). Case is kept.
 *
 * <p>WO-02 PoC: the 50-case suite of 10 §9.4, the {@code truncated} flag, {@code norm_version} and
 * the {@code sql_unparsed_total} counter arrive with the jdbc module proper (WO-13).
 */
public final class SqlNormalizer {
  /** {@code sql.max-length} default (10 §15): characters read from a statement. */
  public static final int MAX_LENGTH = 65_536;

  /** Text of a statement that could not be normalized (10 §9.4 "실패 안전"). */
  public static final String UNPARSED = "<unparsed>";

  private static final SqlNormalizer DEFAULTS = new SqlNormalizer(true, MAX_LENGTH);

  /** Characters before which a sign belongs to the following number (10 §9.4). */
  private static final String SIGN_CONTEXT = "=<>!(,+-*/%|&^~";

  private final boolean backslashEscape;
  private final int maxLength;

  /**
   * @param backslashEscape {@code sql.backslash-escape}: a backslash escapes the next character in
   *     a string literal
   * @param maxLength {@code sql.max-length}: characters read, the rest is cut
   */
  public SqlNormalizer(boolean backslashEscape, int maxLength) {
    this.backslashEscape = backslashEscape;
    this.maxLength = Math.max(1, maxLength);
  }

  /** The documented defaults: backslash escapes on, {@value #MAX_LENGTH} characters. */
  public static SqlNormalizer defaults() {
    return DEFAULTS;
  }

  /** The normalized text of {@code sql}; null for null, {@link #UNPARSED} on failure. */
  public String normalize(String sql) {
    if (sql == null) {
      return null;
    }
    try {
      return collapseLists(scan(sql));
    } catch (RuntimeException e) {
      return UNPARSED;
    }
  }

  private String scan(String input) {
    int length = Math.min(input.length(), maxLength);
    StringBuilder out = new StringBuilder(Math.min(length, 4096) + 16);
    boolean gap = false;
    int i = 0;
    while (i < length) {
      char c = input.charAt(i);
      char next = i + 1 < length ? input.charAt(i + 1) : 0;
      if (Character.isWhitespace(c)) {
        gap = true;
        i++;
        continue;
      }
      if (c == '-' && next == '-') {
        i = endOfLine(input, i + 2, length);
        gap = true;
        continue;
      }
      if (c == '/' && next == '*') {
        i = endOfBlockComment(input, i + 2, length);
        gap = true;
        continue;
      }
      if (c == '#' && !(isIdentifierStart(next) || next == '#')) {
        i = endOfLine(input, i + 1, length);
        gap = true;
        continue;
      }
      if (gap && out.length() > 0) {
        out.append(' ');
      }
      gap = false;
      if (c == '\'') {
        i = endOfString(input, i + 1, length);
        out.append('?');
      } else if (c == '"' || c == '`') {
        int end = endOfQuotedIdentifier(input, i + 1, length, c);
        out.append(input, i, end);
        i = end;
      } else if (c == '[') {
        int end = endOfBracketIdentifier(input, i + 1, length);
        if (end > 0) {
          out.append(input, i, end);
          i = end;
        } else {
          out.append(c);
          i++;
        }
      } else if (c == '$') {
        i = dollar(input, i, length, out);
      } else if (c == '?') {
        // Bind marker, with the digits of a numbered marker (?1).
        int end = i + 1;
        while (end < length && isDigit(input.charAt(end))) {
          end++;
        }
        out.append(input, i, end);
        i = end;
      } else if ((c == ':' || c == '@') && isIdentifierPart(next)) {
        // Bind markers :name, :1, @p1, @name (and @@identity): copied with their name.
        int end = endOfWord(input, i + 1, length);
        out.append(input, i, end);
        i = end;
      } else if (isDigit(c) || (c == '.' && isDigit(next))) {
        i = endOfNumber(input, i, length);
        out.append('?');
      } else if ((c == '-' || c == '+') && startsNumber(input, i + 1, length) && signContext(out)) {
        i = endOfNumber(input, i + 1, length);
        out.append('?');
      } else if (isIdentifierStart(c) || c == '#') {
        int end = endOfWord(input, i + 1, length);
        if (end - i == 1 && end < length && input.charAt(end) == '\'' && isLiteralPrefix(c)) {
          // N'..', E'..', X'..', B'..': the prefix belongs to the literal.
          i = endOfString(input, end + 1, length);
          out.append('?');
        } else {
          out.append(input, i, end);
          i = end;
        }
      } else {
        out.append(c);
        i++;
      }
    }
    return out.toString();
  }

  /** {@code $1} bind marker, {@code $tag$...$tag$} literal, or a plain {@code $}. */
  private static int dollar(String input, int start, int length, StringBuilder out) {
    int i = start + 1;
    if (i < length && isDigit(input.charAt(i))) {
      while (i < length && isDigit(input.charAt(i))) {
        i++;
      }
      out.append(input, start, i);
      return i;
    }
    int tagEnd = i;
    if (tagEnd < length && isIdentifierStart(input.charAt(tagEnd))) {
      tagEnd++;
      while (tagEnd < length && isTagPart(input.charAt(tagEnd))) {
        tagEnd++;
      }
    }
    if (tagEnd < length && input.charAt(tagEnd) == '$') {
      String delimiter = input.substring(start, tagEnd + 1);
      int close = input.indexOf(delimiter, tagEnd + 1);
      out.append('?');
      return close < 0 || close >= length ? length : Math.min(length, close + delimiter.length());
    }
    out.append('$');
    return start + 1;
  }

  private int endOfString(String input, int start, int length) {
    int i = start;
    while (i < length) {
      char c = input.charAt(i);
      if (c == '\\' && backslashEscape) {
        i += 2;
        continue;
      }
      if (c == '\'') {
        if (i + 1 < length && input.charAt(i + 1) == '\'') {
          i += 2;
          continue;
        }
        return i + 1;
      }
      i++;
    }
    return length;
  }

  private static int endOfQuotedIdentifier(String input, int start, int length, char quote) {
    int i = start;
    while (i < length) {
      if (input.charAt(i) == quote) {
        if (i + 1 < length && input.charAt(i + 1) == quote) {
          i += 2;
          continue;
        }
        return i + 1;
      }
      i++;
    }
    return length;
  }

  /**
   * End of a {@code [name]} identifier (a letter or '_' first, then identifier characters, spaces
   * and dots), or -1 otherwise: array literals and subscripts ({@code ARRAY['a']}, {@code arr[1]})
   * are then scanned as tokens and their literals masked.
   */
  private static int endOfBracketIdentifier(String input, int start, int length) {
    if (start >= length || !isIdentifierStart(input.charAt(start))) {
      return -1;
    }
    int i = start + 1;
    while (i < length) {
      char c = input.charAt(i);
      if (c == ']') {
        return i + 1;
      }
      if (!(isIdentifierPart(c) || c == ' ' || c == '.')) {
        return -1;
      }
      i++;
    }
    return -1;
  }

  private static int endOfLine(String input, int start, int length) {
    int i = start;
    while (i < length && input.charAt(i) != '\n' && input.charAt(i) != '\r') {
      i++;
    }
    return i;
  }

  private static int endOfBlockComment(String input, int start, int length) {
    int depth = 1;
    int i = start;
    while (i < length) {
      char c = input.charAt(i);
      if (c == '*' && i + 1 < length && input.charAt(i + 1) == '/') {
        depth--;
        i += 2;
        if (depth == 0) {
          return i;
        }
        continue;
      }
      if (c == '/' && i + 1 < length && input.charAt(i + 1) == '*') {
        depth++;
        i += 2;
        continue;
      }
      i++;
    }
    return length;
  }

  private static int endOfWord(String input, int start, int length) {
    int i = start;
    while (i < length && isIdentifierPart(input.charAt(i))) {
      i++;
    }
    return i;
  }

  private static boolean startsNumber(String input, int i, int length) {
    if (i >= length) {
      return false;
    }
    char c = input.charAt(i);
    return isDigit(c) || (c == '.' && i + 1 < length && isDigit(input.charAt(i + 1)));
  }

  /** End of the number starting at {@code start} (a digit or a '.' before a digit). */
  private static int endOfNumber(String input, int start, int length) {
    int i = start;
    if (input.charAt(i) == '0'
        && i + 2 < length
        && (input.charAt(i + 1) == 'x' || input.charAt(i + 1) == 'X')
        && isHexDigit(input.charAt(i + 2))) {
      i += 2;
      while (i < length && isHexDigit(input.charAt(i))) {
        i++;
      }
      return i;
    }
    while (i < length && isDigit(input.charAt(i))) {
      i++;
    }
    if (i < length && input.charAt(i) == '.') {
      i++;
      while (i < length && isDigit(input.charAt(i))) {
        i++;
      }
    }
    if (i < length && (input.charAt(i) == 'e' || input.charAt(i) == 'E')) {
      int exponent = i + 1;
      if (exponent < length && (input.charAt(exponent) == '+' || input.charAt(exponent) == '-')) {
        exponent++;
      }
      if (exponent < length && isDigit(input.charAt(exponent))) {
        i = exponent;
        while (i < length && isDigit(input.charAt(i))) {
          i++;
        }
      }
    }
    return i;
  }

  /** True when a sign at this point is part of a number: after an operator, '(' or ','. */
  private static boolean signContext(StringBuilder out) {
    int i = out.length() - 1;
    while (i >= 0 && out.charAt(i) == ' ') {
      i--;
    }
    return i < 0 || SIGN_CONTEXT.indexOf(out.charAt(i)) >= 0;
  }

  private static boolean isLiteralPrefix(char c) {
    return c == 'N' || c == 'n' || c == 'E' || c == 'e' || c == 'X' || c == 'x' || c == 'B'
        || c == 'b';
  }

  private static boolean isDigit(char c) {
    return c >= '0' && c <= '9';
  }

  private static boolean isHexDigit(char c) {
    return isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
  }

  private static boolean isIdentifierStart(char c) {
    return c != 0 && (Character.isLetter(c) || c == '_');
  }

  private static boolean isIdentifierPart(char c) {
    return c != 0 && (Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '#');
  }

  private static boolean isTagPart(char c) {
    return Character.isLetterOrDigit(c) || c == '_';
  }

  // --- IN lists and multi-row VALUES (on the literal-free text) ---------------------------------

  static String collapseLists(String text) {
    if (text.indexOf('(') < 0) {
      return text;
    }
    StringBuilder out = new StringBuilder(text.length());
    int i = 0;
    int length = text.length();
    while (i < length) {
      if (isWordAt(text, i, "IN")) {
        int open = skipSpaces(text, i + 2);
        int close = questionGroupEnd(text, open);
        if (close > 0) {
          out.append(text, i, i + 2).append(" (?)");
          i = close;
          continue;
        }
      } else if (isWordAt(text, i, "VALUES")) {
        int open = skipSpaces(text, i + 6);
        int close = questionGroupEnd(text, open);
        if (close > 0) {
          out.append(text, i, close);
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
          continue;
        }
      }
      out.append(text.charAt(i));
      i++;
    }
    return out.toString();
  }

  /** True when the word {@code word} (ASCII, any case) starts at {@code i} on word boundaries. */
  private static boolean isWordAt(String text, int i, String word) {
    int end = i + word.length();
    if (end > text.length() || !text.regionMatches(true, i, word, 0, word.length())) {
      return false;
    }
    if (i > 0 && isIdentifierPart(text.charAt(i - 1))) {
      return false;
    }
    return end == text.length() || !isIdentifierPart(text.charAt(end));
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
      } else if (expectMarker && c == '?') {
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
}
