/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.sql;

import java.util.Locale;

/**
 * How the SQL tokenizer reads the characters whose meaning differs between databases (10 §9.4,
 * SEC-35): double quotes, backslashes in strings, {@code #}, {@code --}, {@code [...]} and {@code
 * $}. The dialect comes from the vendor of the JDBC target ({@link JdbcUrlParser}) and assumes the
 * database's default session settings. An unknown target is read in the MySQL way, and the cases
 * known to read differently in the MySQL and the ANSI family make the statement {@code <unparsed>}:
 *
 * <ul>
 *   <li>{@link #MYSQL} (MySQL, MariaDB): {@code "..."} is a string; a backslash escapes the next
 *       character of a string when {@code sql.backslash-escape} is true (the default); {@code #}
 *       starts a line comment, also in the middle of a word; {@code --} starts one only before
 *       ASCII whitespace or control (incl. DEL).
 *   <li>{@link #UNKNOWN}: as MySQL, except that {@code --} always starts a comment, a {@code $}
 *       before a decimal number is a money constant, and these make the statement {@code
 *       <unparsed>}: a quote after an odd run of backslashes inside a string, a {@code #} comment
 *       or a {@code --} comment without ASCII whitespace or control (incl. DEL) after it that holds
 *       a quote, {@code $} or {@code /*}, a literal or comment that starts inside the SQL Server
 *       reading of a {@code [...]} and ends after its {@code ]} (one that closes inside the
 *       brackets, {@code ARRAY[DATE '2024-01-01']}, is masked), and inside a backtick identifier a
 *       quote, a dollar quote start ({@code $$}, {@code $tag$}) or a {@code $} before a number
 *       ({@code `v$session`} is kept).
 *   <li>{@link #POSTGRESQL}: {@code "..."} is an identifier; a backslash is a plain character
 *       except in {@code E'..'} strings, but a quote after an odd run of backslashes inside a
 *       string makes the statement {@code <unparsed>} (with {@code standard_conforming_strings=off}
 *       that backslash escapes the quote, so the string would end elsewhere; ADR-0010 C-183 (c));
 *       {@code #} is an operator.
 *   <li>{@link #ORACLE} (Oracle, Tibero), {@link #SQLSERVER}, {@link #ANSI} (H2, DB2, Altibase) and
 *       {@link #CUBRID}: {@code "..."} is an identifier; a backslash is a plain character; {@code
 *       #name} is an identifier (SQL Server temporary tables), any other {@code #} an operator. In
 *       SQL Server {@code [...]} is an identifier whatever it holds ({@code ]]} escapes) and {@code
 *       $1234.56} is a money constant, not a marker. In SQL Server and CUBRID a {@code "..."} whose
 *       content is not shaped like an identifier (an ASCII letter or {@code _} first, then only
 *       ASCII letters, digits, {@code _}, {@code $} and {@code #}, at most 128 characters) is read
 *       as a string and becomes {@code ?}: there {@code QUOTED_IDENTIFIER OFF} and {@code
 *       ansi_quotes=no} make {@code "..."} a string (ADR-0010 C-183 (d)). The ANSI and the CUBRID
 *       dialect stand for databases that also accept {@code [...]} or backtick identifiers in some
 *       modes, so, as in the unknown dialect, a literal or comment that crosses the {@code ]} of
 *       the SQL Server reading, and a quote, a dollar quote start or a {@code $} before a number
 *       inside backticks, make the statement {@code <unparsed>}.
 * </ul>
 *
 * <p>Settings other than the defaults can still make a literal survive: MySQL {@code
 * NO_BACKSLASH_ESCAPES} with {@code sql.backslash-escape} left true, and, with SQL Server {@code
 * QUOTED_IDENTIFIER OFF} or CUBRID {@code ansi_quotes=no}, a {@code "..."} string that holds one
 * identifier-shaped word (known limitations, docs/agent-config.md).
 *
 * <p>The same statement against the same vendor always normalizes to the same text, so its {@code
 * sql_id} is stable across instances and restarts (D-22, T-ACC-08).
 */
public final class SqlDialect {
  /** Vendor not known (no target, unknown URL): the stricter reading of both families. */
  public static final int UNKNOWN = 0;

  /** MySQL and MariaDB. */
  public static final int MYSQL = 1;

  /** PostgreSQL. */
  public static final int POSTGRESQL = 2;

  /** Oracle and Tibero (also {@code q'[...]'} quoting, which every dialect accepts). */
  public static final int ORACLE = 3;

  /** Microsoft SQL Server. */
  public static final int SQLSERVER = 4;

  /** H2, DB2, Altibase: ANSI quoting. */
  public static final int ANSI = 5;

  /**
   * CUBRID: ANSI quoting, and a {@code "..."} that is not shaped like an identifier is a string
   * ({@code ansi_quotes=no}, ADR-0010 C-183 (d)).
   */
  public static final int CUBRID = 6;

  /** Number of dialects. */
  static final int COUNT = 7;

  /**
   * Longest content of a {@code "..."} that SQL Server and CUBRID keep as an identifier (their
   * identifier length limits are 128 and 254 characters; ADR-0010 C-183 (d)).
   */
  static final int MAX_IDENTIFIER_SHAPE = 128;

  private SqlDialect() {}

  /** The dialect of a vendor name of {@link JdbcUrlParser} (any case); {@link #UNKNOWN} else. */
  public static int of(String vendor) {
    if (vendor == null) {
      return UNKNOWN;
    }
    switch (vendor.toLowerCase(Locale.ROOT)) {
      case JdbcUrlParser.MYSQL:
      case JdbcUrlParser.MARIADB:
        return MYSQL;
      case JdbcUrlParser.POSTGRESQL:
        return POSTGRESQL;
      case JdbcUrlParser.ORACLE:
      case JdbcUrlParser.TIBERO:
        return ORACLE;
      case JdbcUrlParser.SQLSERVER:
        return SQLSERVER;
      case JdbcUrlParser.H2:
      case JdbcUrlParser.DB2:
      case JdbcUrlParser.ALTIBASE:
        return ANSI;
      case JdbcUrlParser.CUBRID:
        return CUBRID;
      default:
        return UNKNOWN;
    }
  }

  /** A valid dialect number, or {@link #UNKNOWN}. */
  static int valid(int dialect) {
    return dialect >= 0 && dialect < COUNT ? dialect : UNKNOWN;
  }

  /** True when {@code "..."} is a string literal. */
  static boolean doubleQuoteIsString(int dialect) {
    return dialect == UNKNOWN || dialect == MYSQL;
  }

  /**
   * True when a session setting can make {@code "..."} a string literal (SQL Server {@code
   * QUOTED_IDENTIFIER OFF}, CUBRID {@code ansi_quotes=no}): a {@code "..."} that is not shaped like
   * an identifier is then read as a string (ADR-0010 C-183 (d)).
   */
  static boolean doubleQuoteMayBeString(int dialect) {
    return dialect == SQLSERVER || dialect == CUBRID;
  }

  /**
   * True when a backslash before a quote in a plain string can end the string elsewhere under a
   * session setting (PostgreSQL {@code standard_conforming_strings=off}): an odd run of backslashes
   * before a quote is then {@code <unparsed>} (ADR-0010 C-183 (c)).
   */
  static boolean oddBackslashBeforeQuoteFails(int dialect) {
    return dialect == POSTGRESQL;
  }

  /** True when {@code #} always starts a line comment. */
  static boolean hashIsComment(int dialect) {
    return dialect == UNKNOWN || dialect == MYSQL;
  }

  /** True when {@code sql.backslash-escape} applies to plain strings. */
  static boolean backslashApplies(int dialect) {
    return dialect == UNKNOWN || dialect == MYSQL;
  }

  /**
   * True when {@code [...]} and backticks may quote identifiers in one database or mode of the
   * dialect and not in another (unknown; ANSI; CUBRID), so that a reading crossing them is
   * ambiguous.
   */
  static boolean mixedIdentifierQuotes(int dialect) {
    return dialect == UNKNOWN || dialect == ANSI || dialect == CUBRID;
  }
}
