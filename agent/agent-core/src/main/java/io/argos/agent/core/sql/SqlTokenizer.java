/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.sql;

/**
 * The single-pass SQL tokenizer of the normalizer (10 §9.4, D-22, SEC-35) and of the {@code
 * partial} bind parameter mapping (16 §6.7). It splits {@code input[from, to)} into tokens; no
 * regular expression, no recursion except one level for a kept optimizer hint.
 *
 * <p>Lexer states of 10 §9.4 and where they are read:
 *
 * <ul>
 *   <li>DEFAULT: {@link #next()} (whitespace, words, bind markers, symbols);
 *   <li>SQUOTE: {@link #singleQuoted} ({@code ''} escape; a backslash escapes the next character
 *       when {@code sql.backslash-escape} applies to the dialect, and always in {@code E'..'}; in
 *       PostgreSQL a quote after an odd run of backslashes is {@link Unparsable}, ADR-0010 C-183
 *       (c));
 *   <li>DQUOTE: {@link #quotedIdentifier} (ANSI dialects, kept; in SQL Server and CUBRID only when
 *       its content is shaped like an identifier, else a literal, {@link #identifierShaped},
 *       ADR-0010 C-183 (d)) or {@link #doubleQuotedString} (MySQL and unknown dialects, a literal);
 *   <li>BACKTICK: {@link #quotedIdentifier} (kept);
 *   <li>BRACKET: SQL Server {@code [...]} with {@code ]]} escapes, any character inside ({@link
 *       #quotedIdentifier}); the other dialects {@link #bracketIdentifier} (kept);
 *   <li>LINE_COMMENT: {@link #endOfLine} ({@code --}, only before ASCII whitespace or control
 *       (incl. DEL) in the MySQL dialect; {@code #} in the MySQL and the unknown dialect, also in
 *       the middle of a word);
 *   <li>BLOCK_COMMENT: {@link #endOfBlockComment} (nested comments counted; {@code /*+ ..}
 *       optimizer hints too, unless {@code sql.keep-hints});
 *   <li>DOLLAR_QUOTE: {@link #dollar} (PostgreSQL {@code $tag$...$tag$});
 *   <li>NUMBER: {@link #endOfNumber} (integer, decimal, exponent, {@code 0x} hex, {@code 0b}
 *       binary, a sign only after an operator, {@code (} or {@code ,}; SQL Server money constants
 *       {@code $1234.56}, and in the unknown dialect {@code $} before a decimal number).
 * </ul>
 *
 * <p>Budget (DSN-10-17 "1ms 예산"): every character the tokenizer reads, including the ones a failed
 * look-ahead reads again, counts as work; past {@code budget} it throws {@link Unparsable} and the
 * normalizer answers {@code <unparsed>}. The callers set the budget to a fixed multiple of the
 * input length, so the time per statement is bounded by its (already capped) length. A statement
 * that cannot be read safely also throws {@link Unparsable}: an unterminated quoted identifier
 * (copying it would copy the rest of the statement); a literal or comment that starts inside the
 * SQL Server reading of a {@code [...]} and ends after its {@code ]} ({@link #crossing}), which is
 * armed at every {@code [} in the unknown, the ANSI and the CUBRID dialect (whose databases quote
 * identifiers in more than one way) and, in the other dialects but SQL Server, at a {@code [name}
 * whose scan reaches a quote (the SQL Server reading would see an identifier, the token reading a
 * string; a literal that closes inside the brackets, {@code ARRAY[DATE '2024-01-01']}, is masked as
 * usual); a {@code [name} whose scan reaches a quote when no {@code ]} follows the {@code [} at all
 * (no reading of the statement is valid, and the two readings disagree about every later literal);
 * in the unknown dialect, a quote after an odd run of backslashes (the MySQL and the ANSI reading
 * end the string at different places) and a {@code #} comment or a {@code --} comment without ASCII
 * whitespace or control (incl. DEL) after it that holds a quote, a {@code $} or a {@code /*} (the
 * MySQL reading and the other one disagree whether it is a comment); in PostgreSQL, a quote after
 * an odd run of backslashes inside a plain string ({@code standard_conforming_strings} on and off
 * end the string at different places); and, in the unknown, the ANSI and the CUBRID dialect, a
 * quote, a dollar quote start ({@code $$}, {@code $tag$}) or a {@code $} before a number inside a
 * backtick identifier ({@link #requireNoLiteralStart}).
 */
final class SqlTokenizer {
  /** No more tokens. */
  static final int END = 0;

  /**
   * Identifier or keyword: letters, digits, {@code _}, {@code $}, {@code #} ({@code #} not in the
   * MySQL and the unknown dialect, where it starts a comment); kept.
   */
  static final int WORD = 1;

  /** {@code "..."} (ANSI dialects), {@code `...`}, {@code [...]}: kept as written. */
  static final int QUOTED_IDENTIFIER = 2;

  /** A string literal of any form: replaced by {@code ?}. */
  static final int STRING = 3;

  /** A numeric literal: replaced by {@code ?}. */
  static final int NUMBER = 4;

  /** A positional JDBC parameter {@code ?}. */
  static final int MARKER = 5;

  /** {@code ?1}, {@code :name}, {@code :1}, {@code @p1}, {@code @name}, {@code $1}: kept. */
  static final int NAMED_MARKER = 6;

  /** Any other single character (operators, parentheses, commas, ...). */
  static final int SYMBOL = 7;

  /** An optimizer hint {@code /*+ ... *}{@code /} while {@code sql.keep-hints} is true. */
  static final int HINT = 8;

  /** Characters before which a sign belongs to the following number (10 §9.4). */
  private static final String SIGN_CONTEXT = "=<>!(,+-*/%|&^~";

  /** Longest dollar-quote tag read as one ({@code $tag$}); a longer one is a plain {@code $}. */
  static final int MAX_DOLLAR_TAG = 64;

  /** The statement cannot be read within the budget or without ambiguity. */
  static final class Unparsable extends RuntimeException {
    private static final long serialVersionUID = 1L;

    Unparsable(String reason) {
      super(reason, null, false, false);
    }
  }

  private final String in;
  private final int to;
  private final int dialect;
  private final boolean backslash;
  private final boolean ambiguityFails;
  private final boolean hashComment;
  private final boolean mixedQuotes;
  private final boolean doubleQuoteMayBeString;
  private final boolean oddBackslashFails;
  private final boolean keepHints;
  private final long budget;

  private long work;
  private int pos;
  private int start;
  private int end;
  private boolean gap;
  private int kind = END;
  private char symbol;
  private int hintBodyStart;
  private int hintBodyEnd;

  /**
   * End of the SQL Server reading of the last armed {@code [} (0: none; {@link
   * #armSqlServerReading}).
   */
  private int bracketEnd;

  /** No closing {@code ]} at or after this position (an armed {@code [} found none). */
  private int noCloseFrom = Integer.MAX_VALUE;

  /**
   * @param in the statement
   * @param from first character read
   * @param to end of the characters read (exclusive; the cut of {@code sql.max-length})
   * @param dialect a {@link SqlDialect} number
   * @param backslashEscape {@code sql.backslash-escape}
   * @param keepHints {@code sql.keep-hints}
   * @param budget character reads allowed
   */
  SqlTokenizer(
      String in,
      int from,
      int to,
      int dialect,
      boolean backslashEscape,
      boolean keepHints,
      long budget) {
    this.in = in;
    this.pos = from;
    this.to = Math.min(to, in.length());
    this.dialect = SqlDialect.valid(dialect);
    this.backslash = backslashEscape && SqlDialect.backslashApplies(this.dialect);
    this.ambiguityFails = this.backslash && this.dialect == SqlDialect.UNKNOWN;
    this.hashComment = SqlDialect.hashIsComment(this.dialect);
    this.mixedQuotes = SqlDialect.mixedIdentifierQuotes(this.dialect);
    this.doubleQuoteMayBeString = SqlDialect.doubleQuoteMayBeString(this.dialect);
    this.oddBackslashFails = SqlDialect.oddBackslashBeforeQuoteFails(this.dialect);
    this.keepHints = keepHints;
    this.budget = budget;
  }

  /** Start of the current token. */
  int start() {
    return start;
  }

  /** End (exclusive) of the current token. */
  int end() {
    return end;
  }

  /** True when whitespace or a comment came before the current token. */
  boolean gap() {
    return gap;
  }

  /** The character of the current {@link #SYMBOL} token. */
  char symbol() {
    return symbol;
  }

  /** Body of the current {@link #HINT} token (between {@code /*+} and the closing marker). */
  int hintBodyStart() {
    return hintBodyStart;
  }

  int hintBodyEnd() {
    return hintBodyEnd;
  }

  /** Characters read so far (the budget counter). */
  long work() {
    return work;
  }

  /** Adds {@code count} character reads that did not move the position (look-ahead, re-reads). */
  void charge(long count) {
    work += count;
    if (work > budget) {
      throw new Unparsable("budget");
    }
  }

  /** Reads the next token and returns its kind ({@link #END} at the end). */
  int next() {
    gap = false;
    while (pos < to) {
      char c = in.charAt(pos);
      if (isSpace(c)) {
        gap = true;
        int space = pos + 1;
        while (space < to && isSpace(in.charAt(space))) {
          space++;
        }
        advance(space); // one character read each, as one advance per character would charge
        continue;
      }
      char n = peek(pos + 1);
      if (c == '-' && n == '-') {
        boolean spaced = commentSpaceAt(pos + 2);
        if (!spaced && dialect == SqlDialect.MYSQL) {
          // MySQL: "--" starts a comment only before ASCII whitespace or control (incl. DEL); else
          // it is two minus signs ("a--1" is a - (-1)).
          return token(c, n);
        }
        // Unknown dialect: "--x" is a comment in the ANSI reading and two minus signs in the MySQL
        // one; a quote, '$' or "/*" behind it would end differently in the two readings.
        skipComment(endOfLine(pos + 2, !spaced && dialect == SqlDialect.UNKNOWN));
        continue;
      }
      if (c == '/' && n == '*') {
        if (keepHints && peek(pos + 2) == '+') {
          return hint();
        }
        skipComment(endOfBlockComment(pos + 2));
        continue;
      }
      if (c == '#') {
        if (hashComment) {
          // Unknown dialect: '#' is a comment in the MySQL reading and an operator in the
          // PostgreSQL one; a quote, '$' or "/*" behind it would end differently in the two.
          skipComment(endOfLine(pos + 1, dialect == SqlDialect.UNKNOWN));
          continue;
        }
        if (isIdentifierStart(n) || n == '#') {
          return token(WORD, endOfWord(pos + 1));
        }
        return symbolToken(c);
      }
      return token(c, n);
    }
    start = to;
    end = to;
    kind = END;
    return END;
  }

  private int token(char c, char n) {
    // The common tokens first (no other branch below takes a letter, '_' or a digit).
    if (isIdentifierStart(c)) {
      return word(c);
    }
    if (isDigit(c)) {
      return token(NUMBER, endOfNumber(pos));
    }
    if (c == '\'') {
      return token(STRING, singleQuoted(pos + 1, backslash, ambiguityFails));
    }
    if (c == '"') {
      if (SqlDialect.doubleQuoteIsString(dialect)) {
        return token(STRING, doubleQuotedString(pos + 1));
      }
      int close = quotedIdentifier(pos + 1, '"');
      if (doubleQuoteMayBeString && !identifierShaped(pos + 1, close - 1)) {
        // SQL Server QUOTED_IDENTIFIER OFF, CUBRID ansi_quotes=no: "..." may be a string there, and
        // only identifier-shaped content is kept (ADR-0010 C-183 (d)).
        return token(STRING, close);
      }
      return token(QUOTED_IDENTIFIER, close);
    }
    if (c == '`') {
      int close = quotedIdentifier(pos + 1, '`');
      if (mixedQuotes) {
        // A reading without a backtick quote (ANSI) would start a literal at a quote or '$'
        // inside, which this reading would keep as part of the identifier.
        requireNoLiteralStart(pos + 1, close - 1);
      }
      return token(QUOTED_IDENTIFIER, close);
    }
    if (c == '[') {
      if (dialect == SqlDialect.SQLSERVER) {
        // SQL Server: every character up to the closing ']' (']]' escapes it) is the identifier,
        // quotes included; unterminated is unparsable.
        return token(QUOTED_IDENTIFIER, quotedIdentifier(pos + 1, ']'));
      }
      if (mixedQuotes) {
        // The SQL Server reading of this '[': an identifier up to the closing ']'. A literal or a
        // comment of this reading that starts inside it and ends after it is ambiguous ({@link
        // #crossing}).
        armSqlServerReading(pos);
      }
      int close = bracketIdentifier(pos + 1);
      return close > 0 ? token(QUOTED_IDENTIFIER, close) : symbolToken(c);
    }
    if (c == '$') {
      return dollar();
    }
    if (c == '?') {
      int digits = endOfDigits(pos + 1);
      return token(digits > pos + 1 ? NAMED_MARKER : MARKER, digits);
    }
    if (c == '@' && n == '@') {
      return token(NAMED_MARKER, endOfWord(pos + 2));
    }
    if ((c == ':' || c == '@') && wordPart(n)) {
      return token(NAMED_MARKER, endOfWord(pos + 1));
    }
    if (c == '.' && isDigit(n)) {
      return token(NUMBER, endOfNumber(pos));
    }
    if ((c == '-' || c == '+') && startsNumber(pos + 1) && signContext()) {
      return token(NUMBER, endOfNumber(pos + 1));
    }
    return symbolToken(c);
  }

  /**
   * A word, or a prefixed literal: {@code N'..'}, {@code E'..'}, {@code X'..'}, {@code q'[..]'}.
   */
  private int word(char c) {
    int wordEnd = endOfWord(pos + 1);
    if (wordEnd < to && in.charAt(wordEnd) == '\'') {
      int length = wordEnd - pos;
      if ((length == 1 && (c == 'q' || c == 'Q')) || (length == 2 && isNq(pos))) {
        int close = qQuoted(wordEnd + 1);
        if (close > 0) {
          return token(STRING, close);
        }
      }
      if (length == 1 && isLiteralPrefix(c)) {
        // E'..' (PostgreSQL) always reads backslash escapes and is unambiguous.
        boolean eString = c == 'E' || c == 'e';
        return token(
            STRING, singleQuoted(wordEnd + 1, eString || backslash, !eString && ambiguityFails));
      }
    }
    return token(WORD, wordEnd);
  }

  private boolean isNq(int at) {
    char a = in.charAt(at);
    char b = in.charAt(at + 1);
    return (a == 'n' || a == 'N') && (b == 'q' || b == 'Q');
  }

  private int token(int tokenKind, int tokenEnd) {
    start = pos;
    end = Math.max(tokenEnd, pos + 1);
    crossing(end);
    advance(end);
    kind = tokenKind;
    return tokenKind;
  }

  private int symbolToken(char c) {
    symbol = c;
    return token(SYMBOL, pos + 1);
  }

  private int hint() {
    int close = endOfBlockComment(pos + 2);
    hintBodyStart = pos + 3;
    boolean closed = close >= pos + 4 && in.charAt(close - 1) == '/' && in.charAt(close - 2) == '*';
    hintBodyEnd = closed ? close - 2 : close;
    if (hintBodyEnd < hintBodyStart) {
      hintBodyEnd = hintBodyStart;
    }
    return token(HINT, close);
  }

  /** Skips a comment that ends at {@code commentEnd}: a gap. */
  private void skipComment(int commentEnd) {
    crossing(commentEnd);
    advance(commentEnd);
    gap = true;
  }

  /**
   * A token or comment from {@link #pos} to {@code tokenEnd} that starts inside the armed SQL
   * Server reading of a {@code [...]} identifier ({@link #armSqlServerReading}) and ends after it
   * is {@link Unparsable}. There the SQL Server reading resumes at the {@code ]} while this one is
   * still inside a literal or comment, so a literal of one reading would be code in the other
   * ({@code [1's] ... 'kim@x'}, {@code [a]]'b] ...}, {@code [/*] 'x *}{@code / secret'}, {@code
   * [it's] ... 'kim@x'}).
   */
  private void crossing(int tokenEnd) {
    if (pos < bracketEnd && tokenEnd > bracketEnd) {
      throw new Unparsable("ambiguous bracket");
    }
  }

  private void advance(int newPos) {
    int target = Math.min(newPos, to);
    work += Math.max(1, target - pos);
    if (work > budget) {
      throw new Unparsable("budget");
    }
    pos = target;
  }

  private char peek(int at) {
    return at < to ? in.charAt(at) : 0;
  }

  // --- SQUOTE / DQUOTE --------------------------------------------------------------------------

  /** End (after the closing quote) of a {@code '...'} literal whose body starts at {@code from}. */
  private int singleQuoted(int from, boolean escapes, boolean ambiguous) {
    return quotedLiteral(from, '\'', escapes, ambiguous);
  }

  /** MySQL dialects: {@code "..."} is a string literal ({@code ""} and backslash escapes). */
  private int doubleQuotedString(int from) {
    return quotedLiteral(from, '"', backslash, ambiguityFails);
  }

  /**
   * End of a literal quoted by {@code quote}; an unterminated literal runs to the end (it is masked
   * as a whole, never copied). Without {@code escapes}, in PostgreSQL, a quote after an odd run of
   * backslashes is {@link Unparsable}: with {@code standard_conforming_strings=off} the last
   * backslash escapes that quote and the literal ends elsewhere (ADR-0010 C-183 (c)); an even run
   * ends it at the same quote in both readings.
   */
  private int quotedLiteral(int from, char quote, boolean escapes, boolean ambiguous) {
    int i = from;
    while (i < to) {
      char c = in.charAt(i);
      if (c == '\\' && escapes) {
        if (ambiguous && i + 1 < to && in.charAt(i + 1) == quote) {
          throw new Unparsable("ambiguous backslash");
        }
        i += 2;
        continue;
      }
      if (c == '\\' && oddBackslashFails) {
        int runEnd = i + 1;
        while (runEnd < to && in.charAt(runEnd) == '\\') {
          runEnd++;
        }
        if (((runEnd - i) & 1) == 1 && runEnd < to && in.charAt(runEnd) == quote) {
          throw new Unparsable("odd backslashes before a quote");
        }
        i = runEnd;
        continue;
      }
      if (c == quote) {
        if (i + 1 < to && in.charAt(i + 1) == quote) {
          i += 2;
          continue;
        }
        return i + 1;
      }
      i++;
    }
    return to;
  }

  /**
   * Oracle alternative quoting {@code q'<d>...<d>'} (body starts at {@code from}, the delimiter):
   * {@code [}, {@code (}, {@code <}, <code>{</code> close with their pair, any other character with
   * itself. Returns -1 when no valid delimiter follows (then the {@code q} is a word).
   */
  private int qQuoted(int from) {
    if (from >= to) {
      return -1;
    }
    char open = in.charAt(from);
    if (isSpace(open) || open == '\'') {
      return -1;
    }
    char close = closing(open);
    int i = from + 1;
    while (i + 1 < to) {
      if (in.charAt(i) == close && in.charAt(i + 1) == '\'') {
        return i + 2;
      }
      i++;
    }
    return to;
  }

  private static char closing(char open) {
    switch (open) {
      case '[':
        return ']';
      case '(':
        return ')';
      case '<':
        return '>';
      case '{':
        return '}';
      default:
        return open;
    }
  }

  // --- quoted identifiers (DQUOTE, BACKTICK, BRACKET) -------------------------------------------

  /**
   * End of an identifier quoted by {@code quote} (doubled quote escapes it). Unterminated: {@link
   * Unparsable}, because keeping it would keep the rest of the statement.
   */
  private int quotedIdentifier(int from, char quote) {
    int i = from;
    while (i < to) {
      if (in.charAt(i) == quote) {
        if (i + 1 < to && in.charAt(i + 1) == quote) {
          i += 2;
          continue;
        }
        return i + 1;
      }
      i++;
    }
    throw new Unparsable("unterminated identifier");
  }

  /**
   * True when {@code in[from, until)}, the content of a {@code "..."}, is shaped like an
   * identifier: 1 to {@value SqlDialect#MAX_IDENTIFIER_SHAPE} characters, an ASCII letter or {@code
   * _} first, then only ASCII letters, digits, {@code _}, {@code $} and {@code #} (no space, no
   * doubled quote). SQL Server and CUBRID keep such a {@code "..."} as an identifier and read any
   * other one as a string (ADR-0010 C-183 (d)). The characters read are charged to the budget.
   */
  private boolean identifierShaped(int from, int until) {
    int length = until - from;
    if (length < 1 || length > SqlDialect.MAX_IDENTIFIER_SHAPE) {
      charge(1);
      return false;
    }
    charge(length);
    char first = in.charAt(from);
    if (!(isAsciiLetter(first) || first == '_')) {
      return false;
    }
    for (int i = from + 1; i < until; i++) {
      char c = in.charAt(i);
      if (!(isAsciiLetter(c) || isDigit(c) || c == '_' || c == '$' || c == '#')) {
        return false;
      }
    }
    return true;
  }

  private static boolean isAsciiLetter(char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
  }

  /**
   * End (after {@code ]}) of a {@code [name]} identifier in a dialect other than SQL Server (a
   * letter or '_' first, then identifier characters, spaces and dots), or -1 otherwise: array
   * literals and subscripts ({@code ARRAY['a']}, {@code arr[1]}, {@code ARRAY[DATE '2024-01-01']})
   * are then read as tokens and their literals masked. When the scan reaches a quote, the SQL
   * Server reading of this {@code [} is armed if it is not yet ({@link #armSqlServerReading}; the
   * unknown, the ANSI and the CUBRID dialect arm it at every {@code [}): the SQL Server reading
   * would keep the quote inside an identifier and the token reading starts a string there, so a
   * literal that closes inside the brackets is masked as usual, while one that runs past the {@code
   * ]} ({@code [it's] ... 'kim@x.com'}, where the two readings disagree about every later literal)
   * is {@link Unparsable} ({@link #crossing}). When no {@code ]} follows the {@code [} at all
   * ({@code [it's FROM t WHERE email = 'kim@x.com'}), the quote is {@link Unparsable} at once: no
   * database accepts the statement, and the two readings still disagree about every later literal.
   * A failed look-ahead is charged to the budget.
   */
  private int bracketIdentifier(int from) {
    if (from >= to || !isIdentifierStart(in.charAt(from))) {
      return -1;
    }
    int i = from + 1;
    while (i < to) {
      char c = in.charAt(i);
      if (c == ']') {
        return i + 1;
      }
      if (!(wordPart(c) || c == ' ' || c == '.')) {
        charge(i - from);
        if (c == '\'' || c == '"' || c == '`') {
          if (!mixedQuotes) {
            // The unknown, the ANSI and the CUBRID dialect armed it at the '[' already.
            armSqlServerReading(from - 1);
          }
          if (from - 1 >= noCloseFrom) {
            throw new Unparsable("quote in unclosed bracket");
          }
        }
        return -1;
      }
      i++;
    }
    charge(i - from);
    return -1;
  }

  /**
   * Arms the SQL Server reading of the {@code [} at {@code bracketPos}, an identifier up to the
   * closing {@code ]} ({@code ]]} escapes), whose end {@link #crossing} checks. Nothing is done
   * when {@code bracketPos} lies inside the reading armed last or when an earlier armed {@code [}
   * found no {@code ]} after it, so every part of the input is scanned for it at most once.
   */
  private void armSqlServerReading(int bracketPos) {
    if (bracketPos >= bracketEnd && bracketPos < noCloseFrom) {
      int sqlServerEnd = sqlServerBracketEnd(bracketPos + 1);
      if (sqlServerEnd < 0) {
        noCloseFrom = bracketPos;
      } else {
        bracketEnd = sqlServerEnd;
      }
    }
  }

  /**
   * End (after {@code ]}) of the SQL Server reading of a {@code [} whose body starts at {@code
   * from} ({@code ]]} escapes), or -1 when no {@code ]} closes it (SQL Server would reject the
   * statement). The scan is charged; the caller scans each part of the input at most once.
   */
  private int sqlServerBracketEnd(int from) {
    int i = from;
    while (i < to) {
      if (in.charAt(i) == ']') {
        if (i + 1 < to && in.charAt(i + 1) == ']') {
          i += 2;
          continue;
        }
        charge(i + 1 - from);
        return i + 1;
      }
      i++;
    }
    charge(i - from);
    return -1;
  }

  /**
   * Unknown and ANSI dialects, a backtick identifier: {@link Unparsable} when {@code in[from,
   * until)} holds a literal start of a reading without backtick quotes: a {@code '} or {@code "},
   * or a {@code $} that starts a dollar quote ({@code $$}, {@code $tag$} with a tag of at most
   * {@value #MAX_DOLLAR_TAG} characters, as {@link #dollar} reads it) or stands before a number
   * ({@code $1}, {@code $1234.56}, {@code $.5}). Any other {@code $} stays part of the identifier
   * ({@code `v$session`}). The scan and the tag look-ahead are charged.
   */
  private void requireNoLiteralStart(int from, int until) {
    long reads = Math.max(0, until - from);
    for (int i = from; i < until; i++) {
      char c = in.charAt(i);
      if (c == '\'' || c == '"') {
        throw new Unparsable("quote in backtick identifier");
      }
      if (c == '$') {
        int tagEnd = endOfDollarTag(i + 1);
        if (startsNumber(i + 1) || closesDollarTag(i + 1, tagEnd)) {
          throw new Unparsable("literal start in backtick identifier");
        }
        reads += tagEnd - (i + 1);
      }
    }
    charge(reads);
  }

  // --- LINE_COMMENT / BLOCK_COMMENT ------------------------------------------------------------

  /**
   * End of a line comment whose body starts at {@code from}. {@code strict} (a comment only one of
   * the readings of the unknown dialect sees): a character that starts a literal or a comment in
   * the other reading ({@code '}, {@code "}, {@code `}, {@code $}, {@code /*}) is {@link
   * Unparsable}, because the text after the line would be code in one reading and a literal in the
   * other.
   */
  private int endOfLine(int from, boolean strict) {
    int i = from;
    while (i < to) {
      char c = in.charAt(i);
      if (c == '\n' || c == '\r') {
        break;
      }
      if (strict
          && (c == '\'' || c == '"' || c == '`' || c == '$' || (c == '/' && peek(i + 1) == '*'))) {
        throw new Unparsable("ambiguous comment");
      }
      i++;
    }
    return i;
  }

  /**
   * True when the character at {@code at} ends a MySQL {@code --}: ASCII whitespace or control
   * (incl. DEL), or the end of the statement. A character outside ASCII (NBSP, U+3000, U+2028, the
   * C1 control U+0085) does not: MySQL reads the next byte with its single-byte character classes,
   * where the lead byte of a multi-byte character is neither, so there {@code --} followed by one
   * is two minus signs.
   */
  private boolean commentSpaceAt(int at) {
    if (at >= to) {
      return true;
    }
    char c = in.charAt(at);
    return c <= ' ' || c == '\u007f';
  }

  /** End of a block comment whose body starts at {@code from}; nested comments are counted. */
  private int endOfBlockComment(int from) {
    int depth = 1;
    int i = from;
    while (i < to) {
      char c = in.charAt(i);
      if (c == '*' && i + 1 < to && in.charAt(i + 1) == '/') {
        depth--;
        i += 2;
        if (depth == 0) {
          return i;
        }
        continue;
      }
      if (c == '/' && i + 1 < to && in.charAt(i + 1) == '*') {
        depth++;
        i += 2;
        continue;
      }
      i++;
    }
    return to;
  }

  // --- DOLLAR_QUOTE ----------------------------------------------------------------------------

  /**
   * {@code $1} bind marker, {@code $tag$...$tag$} literal, or a plain {@code $}. SQL Server has no
   * {@code $n} marker: there {@code $1234.56} and {@code $98000} are money constants (a number). In
   * the unknown dialect a {@code $} before a number with a fraction is a money constant too
   * (PostgreSQL markers have no fraction); {@code $1} stays a marker.
   */
  private int dollar() {
    int i = pos + 1;
    if (startsNumber(i)
        && (dialect == SqlDialect.SQLSERVER || (dialect == SqlDialect.UNKNOWN && hasFraction(i)))) {
      return token(NUMBER, endOfNumber(i));
    }
    if (i < to && isDigit(in.charAt(i))) {
      return token(NAMED_MARKER, endOfDigits(i));
    }
    int tagEnd = endOfDollarTag(i);
    if (closesDollarTag(i, tagEnd)) {
      int delimiterLength = tagEnd + 1 - pos;
      int close = find(pos, delimiterLength, tagEnd + 1);
      return token(STRING, close < 0 ? to : close + delimiterLength);
    }
    return symbolToken('$');
  }

  /**
   * End of the dollar-quote tag that starts at {@code i}, just after a {@code $}: a letter or
   * {@code _} and then letters, digits and {@code _}, read up to one character past {@value
   * #MAX_DOLLAR_TAG}; {@code i} itself when no tag starts there ({@code $$}).
   */
  private int endOfDollarTag(int i) {
    int tagEnd = i;
    if (tagEnd < to && isIdentifierStart(in.charAt(tagEnd))) {
      tagEnd++;
      while (tagEnd < to && isTagPart(in.charAt(tagEnd)) && tagEnd - i <= MAX_DOLLAR_TAG) {
        tagEnd++;
      }
    }
    return tagEnd;
  }

  /** True when the tag {@code in[i, tagEnd)} is closed by a {@code $}: a dollar-quote delimiter. */
  private boolean closesDollarTag(int i, int tagEnd) {
    return tagEnd < to && in.charAt(tagEnd) == '$' && tagEnd - i <= MAX_DOLLAR_TAG;
  }

  /**
   * First position at or after {@code from} where the {@code length} characters at {@code needleAt}
   * occur again before {@link #to}, or -1. Every comparison is charged to the budget.
   */
  private int find(int needleAt, int length, int from) {
    char first = in.charAt(needleAt);
    for (int i = from; i + length <= to; i++) {
      if (in.charAt(i) != first) {
        continue;
      }
      charge(length);
      if (in.regionMatches(i, in, needleAt, length)) {
        return i;
      }
    }
    return -1;
  }

  // --- NUMBER ----------------------------------------------------------------------------------

  private boolean startsNumber(int at) {
    if (at >= to) {
      return false;
    }
    char c = in.charAt(at);
    return isDigit(c) || (c == '.' && at + 1 < to && isDigit(in.charAt(at + 1)));
  }

  /** True when the number starting at {@code at} has a fraction ({@code 12.5}, {@code .5}). */
  private boolean hasFraction(int at) {
    int dot = endOfDigits(at);
    return dot + 1 < to && in.charAt(dot) == '.' && isDigit(in.charAt(dot + 1));
  }

  /** End of the number starting at {@code from} (a digit or a '.' before a digit). */
  private int endOfNumber(int from) {
    int i = from;
    char first = in.charAt(i);
    if (first == '0' && i + 2 < to) {
      char x = in.charAt(i + 1);
      if ((x == 'x' || x == 'X') && isHexDigit(in.charAt(i + 2))) {
        i += 2;
        while (i < to && isHexDigit(in.charAt(i))) {
          i++;
        }
        return i;
      }
      if ((x == 'b' || x == 'B') && isBinaryDigit(in.charAt(i + 2))) {
        i += 2;
        while (i < to && isBinaryDigit(in.charAt(i))) {
          i++;
        }
        return i;
      }
    }
    i = endOfDigits(i);
    if (i < to && in.charAt(i) == '.') {
      i = endOfDigits(i + 1);
    }
    if (i < to && (in.charAt(i) == 'e' || in.charAt(i) == 'E')) {
      int exponent = i + 1;
      if (exponent < to && (in.charAt(exponent) == '+' || in.charAt(exponent) == '-')) {
        exponent++;
      }
      if (exponent < to && isDigit(in.charAt(exponent))) {
        i = endOfDigits(exponent);
      }
    }
    return i;
  }

  private int endOfDigits(int from) {
    int i = from;
    while (i < to && isDigit(in.charAt(i))) {
      i++;
    }
    return i;
  }

  /** True when a sign at this point is part of a number: after an operator, '(' or ','. */
  private boolean signContext() {
    return kind == END || (kind == SYMBOL && SIGN_CONTEXT.indexOf(symbol) >= 0);
  }

  // --- words -----------------------------------------------------------------------------------

  private int endOfWord(int from) {
    int i = from;
    while (i < to && wordPart(in.charAt(i))) {
      i++;
    }
    return i;
  }

  /**
   * An identifier character of this dialect: in the dialects where {@code #} starts a comment
   * (MySQL, unknown) a {@code #} ends the word and starts the comment ({@code id#note}), as MySQL
   * reads it.
   */
  private boolean wordPart(char c) {
    return isIdentifierPart(c) && !(hashComment && c == '#');
  }

  private static boolean isLiteralPrefix(char c) {
    return c == 'N' || c == 'n' || c == 'E' || c == 'e' || c == 'X' || c == 'x' || c == 'B'
        || c == 'b';
  }

  static boolean isSpace(char c) {
    if (c == ' ') {
      return true;
    }
    if (c > ' ' && c < 128) {
      return false; // ASCII fast path: printable characters
    }
    return Character.isWhitespace(c) || c == ' ';
  }

  static boolean isDigit(char c) {
    return c >= '0' && c <= '9';
  }

  private static boolean isHexDigit(char c) {
    return isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
  }

  private static boolean isBinaryDigit(char c) {
    return c == '0' || c == '1';
  }

  static boolean isIdentifierStart(char c) {
    if (c < 128) {
      return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
    }
    return Character.isLetter(c);
  }

  static boolean isIdentifierPart(char c) {
    if (c < 128) {
      return (c >= 'a' && c <= 'z')
          || (c >= 'A' && c <= 'Z')
          || (c >= '0' && c <= '9')
          || c == '_'
          || c == '$'
          || c == '#';
    }
    return Character.isLetterOrDigit(c);
  }

  private static boolean isTagPart(char c) {
    return Character.isLetterOrDigit(c) || c == '_';
  }
}
