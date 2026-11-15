/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.sql;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.core.trace.Dictionary;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** SqlNormalizer (10 §9.4, D-22, SEC-35): literals never survive, statement shape does. */
@Tag("req:SEC-35")
@Tag("req:D-22")
class SqlNormalizerTest {
  private final SqlNormalizer normalizer = SqlNormalizer.defaults();

  /** {input, expected, dialect}: the unknown dialect unless a case needs a vendor's reading. */
  static List<Object[]> cases() {
    int unknown = SqlDialect.UNKNOWN;
    return Arrays.asList(
        new Object[] {
          "select * from orders where id = 42", "select * from orders where id = ?", unknown
        },
        new Object[] {
          "SELECT * FROM t WHERE name = 'kim'", "SELECT * FROM t WHERE name = ?", unknown
        },
        new Object[] {
          "select ?, :name, :1, $1, @p1, @user, ?2",
          "select ?, :name, :1, $1, @p1, @user, ?2",
          unknown
        },
        new Object[] {
          "select * from t where a in (1, 2, 3)", "select * from t where a in (?)", unknown
        },
        new Object[] {
          "select * from t where a IN (?,?,?)", "select * from t where a IN (?)", unknown
        },
        new Object[] {
          "select * from t where a not in ('x','y')", "select * from t where a not in (?)", unknown
        },
        new Object[] {
          "insert into t (a, b) values (1, 'x'), (2, 'y'), (3, 'z')",
          "insert into t (a, b) values (?, ?)",
          unknown
        },
        new Object[] {
          "insert into t values (?, now()), (?, now())",
          "insert into t values (?, now()), (?, now())",
          unknown
        },
        new Object[] {
          "select 'it''s', 'a\\'b' from dual", "select ?, ? from dual", SqlDialect.MYSQL
        },
        new Object[] {"select N'홍길동', E'\\x41', X'0F', B'101'", "select ?, ?, ?, ?", unknown},
        new Object[] {
          "select * from t -- where email = 'kim@example.test'\nwhere x = 1",
          "select * from t where x = ?",
          unknown
        },
        new Object[] {
          "select /*+ INDEX(t i) */ * from t /* id = 5 /* nested */ */ where y = 2",
          "select * from t where y = ?",
          unknown
        },
        // A '#' comment holding a quote is <unparsed> in the unknown dialect (T-SEC-35c below);
        // the MySQL reading removes it.
        new Object[] {
          "select * from t # mysql 'comment'\nwhere z = 3",
          "select * from t where z = ?",
          SqlDialect.MYSQL
        },
        new Object[] {
          "select * from t # mysql 'comment'\nwhere z = 3", SqlNormalizer.UNPARSED, unknown
        },
        new Object[] {
          "select * from #temp join ##global g on 1 = 1",
          "select * from #temp join ##global g on ? = ?",
          SqlDialect.SQLSERVER
        },
        new Object[] {
          "select 1.5, .25, 1e10, 2.5E-3, 0xFF, 0x1a2b", "select ?, ?, ?, ?, ?, ?", unknown
        },
        new Object[] {
          "select a-1, b - 2, f(-3), x = -4, y=+5", "select a-?, b - ?, f(?), x = ?, y=?", unknown
        },
        new Object[] {
          "select col1, t_2024.v2 from t_2024", "select col1, t_2024.v2 from t_2024", unknown
        },
        // ADR-0010 C-183 (d), a criterion change by PM decision (not a weakening): in SQL Server a
        // "..." whose content is not identifier-shaped (here a space) may be a string under
        // QUOTED_IDENTIFIER OFF and is masked; [..] and backticks stay.
        new Object[] {
          "select \"Name 1\", `order`, [Total 2] from t",
          "select ?, `order`, [Total 2] from t",
          SqlDialect.SQLSERVER
        },
        new Object[] {
          "select \"Name1\", `order`, [Total 2] from t",
          "select \"Name1\", `order`, [Total 2] from t",
          SqlDialect.SQLSERVER
        },
        new Object[] {"select arr[1], ARRAY['a', 'b']", "select arr[?], ARRAY[?, ?]", unknown},
        new Object[] {"select $$secret text$$, $tag$more 'x'$tag$", "select ?, ?", unknown},
        new Object[] {
          "select   *\n\tfrom  t\r\n where  a=1  ", "select * from t where a=?", unknown
        },
        new Object[] {"{call proc(?, 'x', 3)}", "{call proc(?, ?, ?)}", unknown},
        new Object[] {
          "select x::int, v$session from t", "select x::int, v$session from t", unknown
        },
        new Object[] {"select 'unterminated", "select ?", unknown},
        new Object[] {
          "update t set a = 'x' where b = 'y'", "update t set a = ? where b = ?", unknown
        },
        // WO-13 additions.
        new Object[] {"select 0b1011, 0B11", "select ?, ?", unknown},
        new Object[] {
          "select q'{it's}', nq'!x'y!' from dual", "select ?, ? from dual", SqlDialect.ORACLE
        },
        new Object[] {
          "select \"kim\" from t where a = \"lee\"", "select ? from t where a = ?", unknown
        },
        new Object[] {
          "select \"kim\" from t where a = \"lee\"",
          "select \"kim\" from t where a = \"lee\"",
          SqlDialect.POSTGRESQL
        },
        new Object[] {
          "select data #> '{a,b}' from t", "select data #> ? from t", SqlDialect.POSTGRESQL
        },
        new Object[] {"select @@IDENTITY, @@ROWCOUNT", "select @@IDENTITY, @@ROWCOUNT", unknown},
        new Object[] {"select 1\u00a0from\u00a0dual", "select ? from dual", unknown});
  }

  @ParameterizedTest(name = "normalizes {0} ({2})")
  @MethodSource("cases")
  void normalizes(String input, String expected, int dialect) {
    assertThat(normalizer.normalize(input, dialect)).isEqualTo(expected);
  }

  @Test
  void seededPersonalDataNeverSurvives() {
    String sql =
        "SELECT * FROM customer WHERE email = 'kim.argos_seed@example.test' AND phone ="
            + " '010-1234-5678' AND rrn = '900101-1234567' AND card = 4111111111111111 AND"
            + " name = N'ARGOS_SEED 홍길동'";
    String normalized = normalizer.normalize(sql);
    assertThat(normalized)
        .isEqualTo(
            "SELECT * FROM customer WHERE email = ? AND phone = ? AND rrn = ? AND card = ? AND name"
                + " = ?")
        .doesNotContain("ARGOS_SEED")
        .doesNotContain("example.test")
        .doesNotContain("1234");
  }

  @Test
  void whitespaceAndCommentVariantsShareOneText() {
    String a = normalizer.normalize("select * from t where id = 1");
    String b = normalizer.normalize("select *\n  from t   /* by id */\nwhere id = 99");
    assertThat(a).isEqualTo(b);
  }

  @Test
  void longStatementsAreCutWithoutLeakingTheCutLiteral() {
    SqlNormalizer small = new SqlNormalizer(true, 30);
    String sql = "select * from t where a = 'abcdefghijklmnopqrstuvwxyz'";
    assertThat(small.normalize(sql)).isEqualTo("select * from t where a = ?");
    assertThat(small.truncated(sql)).isTrue();
    assertThat(small.truncated("select 1")).isFalse();
    assertThat(new SqlNormalizer(true, 1_000_000).maxLength())
        .as("sql.max-length is at most 65,536 (10 §15)")
        .isEqualTo(SqlNormalizer.MAX_LENGTH);
  }

  @Test
  @DisplayName(
      "backslash escapes: on for MySQL by default, off with sql.backslash-escape=false, never in"
          + " ANSI strings; a backslash before a quote in the unknown dialect is <unparsed>")
  void backslashEscapes() {
    SqlNormalizer standard = new SqlNormalizer(false, SqlNormalizer.MAX_LENGTH);
    assertThat(standard.normalize("select 'C:\\' , 'x'")).isEqualTo("select ? , ?");
    assertThat(normalizer.normalize("select 'a\\'b'", SqlDialect.MYSQL)).isEqualTo("select ?");
    // ADR-0010 C-183 (c), a criterion change by PM decision (not a weakening): PostgreSQL reads
    // this statement differently with standard_conforming_strings=off, so it is <unparsed> now
    // (it was "select ?, ?"); T-SEC-35f below.
    assertThat(normalizer.normalize("select 'C:\\', 'secret'", SqlDialect.POSTGRESQL))
        .isEqualTo(SqlNormalizer.UNPARSED);
    for (int dialect :
        new int[] {SqlDialect.ORACLE, SqlDialect.SQLSERVER, SqlDialect.ANSI, SqlDialect.CUBRID}) {
      assertThat(normalizer.normalize("select 'C:\\', 'secret'", dialect))
          .as("a backslash is a plain character (%d)", dialect)
          .isEqualTo("select ?, ?");
    }
    // Read as MySQL the second string would hide "secret" outside a literal; read as ANSI the first
    // one would end early. The unknown dialect cannot tell, so nothing is kept.
    assertThat(normalizer.normalize("select 'C:\\', 'secret'")).isEqualTo(SqlNormalizer.UNPARSED);
    assertThat(normalizer.normalize("select 'a\\'b'")).isEqualTo(SqlNormalizer.UNPARSED);
    assertThat(normalizer.normalize("select 'a\\\\'"))
        .as("an even run is unambiguous")
        .isEqualTo("select ?");
    assertThat(normalizer.normalize("select E'a\\'b'")).as("E'..' strings").isEqualTo("select ?");
  }

  @Test
  @DisplayName("an unterminated quoted identifier is <unparsed>: it would copy the rest")
  void unterminatedIdentifier() {
    assertThat(normalizer.normalize("select \"a from t where x = 'secret'", SqlDialect.POSTGRESQL))
        .isEqualTo(SqlNormalizer.UNPARSED);
    assertThat(normalizer.normalize("select `a from t where x = 'secret'", SqlDialect.MYSQL))
        .isEqualTo(SqlNormalizer.UNPARSED);
  }

  /** {@code out} is {@code <unparsed>} or holds none of {@code seeds}. */
  private static void unparsedOrNoSeed(String what, String out, String... seeds) {
    if (SqlNormalizer.UNPARSED.equals(out)) {
      return;
    }
    for (String seed : seeds) {
      assertThat(out).as("%s: %s", what, out).doesNotContain(seed);
    }
  }

  @Test
  @DisplayName(
      "T-SEC-35a SQL Server [...] keeps any character as identifier (quotes included, ]] escapes,"
          + " unterminated is <unparsed>); the other dialects never keep a literal behind a quote"
          + " in brackets or backticks and mask literals that close inside array brackets")
  void bracketIdentifiers() {
    int sqlServer = SqlDialect.SQLSERVER;
    assertThat(
            normalizer.normalize(
                "SELECT [it's] FROM t WHERE email = 'kim@x.com' AND a = 'b'", sqlServer))
        .isEqualTo("SELECT [it's] FROM t WHERE email = ? AND a = ?");
    assertThat(
            normalizer.normalize(
                "UPDATE [Order's] SET [note] = 'secretI' WHERE [id] = 5", sqlServer))
        .isEqualTo("UPDATE [Order's] SET [note] = ? WHERE [id] = ?");
    assertThat(normalizer.normalize("SELECT [O'Brien col] FROM t WHERE ssn = 'secret1'", sqlServer))
        .isEqualTo("SELECT [O'Brien col] FROM t WHERE ssn = ?");
    assertThat(normalizer.normalize("[a]]b]", sqlServer)).isEqualTo("[a]]b]");
    assertThat(normalizer.normalize("[abc", sqlServer)).isEqualTo(SqlNormalizer.UNPARSED);
    assertThat(normalizer.normalize("SELECT [abc FROM t WHERE x = 'secret2'", sqlServer))
        .isEqualTo(SqlNormalizer.UNPARSED);
    // SQL Server identifiers that a token reading would cut short: '[' before a digit, ']]', a
    // double-quoted string or a comment start inside.
    String[][] crossing = {
      {"SELECT [1's] FROM t WHERE email = 'kim@x.com'", "SELECT [1's] FROM t WHERE email = ?"},
      {"SELECT [a]]'b] FROM t WHERE x = 'secret3'", "SELECT [a]]'b] FROM t WHERE x = ?"},
      {"SELECT [\"'\" 'x] FROM t WHERE a = 'secret4'", "SELECT [\"'\" 'x] FROM t WHERE a = ?"},
      {"SELECT [/*] 'x */ secret5' FROM t", "SELECT [/*] ? FROM t"},
    };
    for (String[] c : crossing) {
      assertThat(normalizer.normalize(c[0], sqlServer)).isEqualTo(c[1]);
    }

    // {input, normalized text in the unknown, the ANSI and the CUBRID dialect}: a quote in a
    // "[name" scan or a literal or comment crossing the SQL Server ']' is <unparsed>; without a
    // literal, or without any ']' (no SQL Server reading), the token reading stays. A "[name" scan
    // that reaches a quote with no ']' after the '[' is <unparsed> in every dialect (WO-13 N-2,
    // the loop after the next one).
    String[][] mixed = {
      {"SELECT [it's] FROM t WHERE email = 'kim@x.com' AND a = 'b'", SqlNormalizer.UNPARSED},
      {"UPDATE [Order's] SET [note] = 'secretI' WHERE [id] = 5", SqlNormalizer.UNPARSED},
      {"SELECT [O'Brien col] FROM t WHERE ssn = 'secret1'", SqlNormalizer.UNPARSED},
      {"[a]]b]", "[a]]b]"},
      {"[abc", "[abc"},
      {"SELECT [abc FROM t WHERE x = 'secret2'", "SELECT [abc FROM t WHERE x = ?"},
      {crossing[0][0], SqlNormalizer.UNPARSED},
      {crossing[1][0], SqlNormalizer.UNPARSED},
      {crossing[2][0], SqlNormalizer.UNPARSED},
      {crossing[3][0], SqlNormalizer.UNPARSED},
    };
    for (int dialect : new int[] {SqlDialect.UNKNOWN, SqlDialect.ANSI, SqlDialect.CUBRID}) {
      for (String[] c : mixed) {
        assertThat(normalizer.normalize(c[0], dialect))
            .as("%s (%d)", c[0], dialect)
            .isEqualTo(c[1]);
      }
    }
    // An unclosed '[' whose name scan reaches a quote: no database accepts the statement, and the
    // SQL Server reading (an identifier up to the end) and the token reading (a string from the
    // quote) disagree about every later literal, so every dialect gives <unparsed> (WO-13 N-2).
    for (String input :
        new String[] {
          "SELECT [it's FROM t WHERE email = 'kim@x.com'",
          "SELECT [O'Brien col FROM t WHERE ssn = 'secret1'",
          "SELECT [it's FROM t WHERE email = 'kim@x.com' AND y = 'y'"
        }) {
      for (int dialect = 0; dialect < SqlDialect.COUNT; dialect++) {
        assertThat(normalizer.normalize(input, dialect))
            .as("%s (%d)", input, dialect)
            .isEqualTo(SqlNormalizer.UNPARSED);
      }
    }
    // PostgreSQL, Oracle and MySQL have no bracket identifiers; the five inputs of the finding
    // still never keep a literal there.
    String[] seeds = {"kim", "x.com", "secretI", "secret1", "secret2"};
    for (int dialect : new int[] {SqlDialect.POSTGRESQL, SqlDialect.ORACLE, SqlDialect.MYSQL}) {
      for (int i = 0; i < 6; i++) {
        String input = mixed[i][0];
        unparsedOrNoSeed(input + " (" + dialect + ")", normalizer.normalize(input, dialect), seeds);
      }
    }
    // Brackets without quotes stay as before: identifiers, subscripts and array literals.
    assertThat(normalizer.normalize("select [Total 2], arr[1], ARRAY['a', 'b'] from t"))
        .isEqualTo("select [Total 2], arr[?], ARRAY[?, ?] from t");
    assertThat(normalizer.normalize("select m[a][b], ARRAY[[x]], f(col[g('k')])"))
        .isEqualTo("select m[a][b], ARRAY[[x]], f(col[g(?)])");
    // A "[name" scan that reaches a quote of a literal closing inside the brackets (typed and
    // prefixed literals in arrays) is masked, not <unparsed>, in every dialect but SQL Server.
    String dates = "SELECT * FROM t WHERE d = ANY (ARRAY[DATE '2024-01-01', DATE '2024-02-01'])";
    for (int dialect :
        new int[] {
          SqlDialect.POSTGRESQL,
          SqlDialect.ANSI,
          SqlDialect.CUBRID,
          SqlDialect.UNKNOWN,
          SqlDialect.MYSQL
        }) {
      assertThat(normalizer.normalize(dates, dialect))
          .as("%s (%d)", dates, dialect)
          .isEqualTo("SELECT * FROM t WHERE d = ANY (ARRAY[DATE ?, DATE ?])")
          .doesNotContain("2024");
    }
    assertThat(normalizer.normalize("SELECT ARRAY[E'a\\nb', N'x'] FROM t", SqlDialect.POSTGRESQL))
        .isEqualTo("SELECT ARRAY[?, ?] FROM t");
    for (int dialect :
        new int[] {
          SqlDialect.POSTGRESQL,
          SqlDialect.ANSI,
          SqlDialect.UNKNOWN,
          SqlDialect.MYSQL,
          SqlDialect.ORACLE
        }) {
      assertThat(normalizer.normalize("SELECT ARRAY[INTERVAL '1 day'] FROM t", dialect))
          .as("interval (%d)", dialect)
          .isEqualTo("SELECT ARRAY[INTERVAL ?] FROM t");
    }

    // Backticks: an identifier in MySQL; in the unknown dialect a quote or '$' inside would start
    // a literal in the ANSI reading.
    String backtick = "SELECT `it's` FROM t WHERE a = 'secret6'";
    assertThat(normalizer.normalize(backtick, SqlDialect.MYSQL))
        .isEqualTo("SELECT `it's` FROM t WHERE a = ?");
    assertThat(normalizer.normalize(backtick)).isEqualTo(SqlNormalizer.UNPARSED);
    assertThat(normalizer.normalize("SELECT `a$$b` FROM t")).isEqualTo(SqlNormalizer.UNPARSED);
    assertThat(normalizer.normalize("SELECT `order`, `col 1` FROM t WHERE a = 'x'"))
        .isEqualTo("SELECT `order`, `col 1` FROM t WHERE a = ?");
    // Only a '$' that starts a literal in the other reading (a dollar quote, a number) is
    // ambiguous inside backticks; Oracle-style names such as v$session are kept.
    for (int dialect : new int[] {SqlDialect.UNKNOWN, SqlDialect.ANSI, SqlDialect.CUBRID}) {
      assertThat(normalizer.normalize("SELECT `v$session` FROM t", dialect))
          .as("v$session (%d)", dialect)
          .isEqualTo("SELECT `v$session` FROM t");
      for (String input :
          new String[] {
            "SELECT `a$$b` FROM t", "SELECT `$x$y` FROM t", "SELECT `a$12.50` FROM t WHERE x = 1"
          }) {
        assertThat(normalizer.normalize(input, dialect))
            .as("%s (%d)", input, dialect)
            .isEqualTo(SqlNormalizer.UNPARSED);
      }
    }
  }

  @Test
  @DisplayName(
      "T-SEC-35b SQL Server money constants ($1234.56, $98000, $.5) are numbers; PostgreSQL $1"
          + " stays a marker; the unknown dialect reads $ before a decimal as money")
  void moneyConstants() {
    int sqlServer = SqlDialect.SQLSERVER;
    assertThat(normalizer.normalize("UPDATE acct SET balance = $1234.56 WHERE id = 7", sqlServer))
        .isEqualTo("UPDATE acct SET balance = ? WHERE id = ?");
    assertThat(normalizer.normalize("SELECT * FROM acct WHERE salary > $98000", sqlServer))
        .isEqualTo("SELECT * FROM acct WHERE salary > ?");
    assertThat(normalizer.normalize("SELECT $.5, -$7", sqlServer)).isEqualTo("SELECT ?, -?");
    assertThat(normalizer.normalize("SELECT * FROM acct WHERE id = $1", SqlDialect.POSTGRESQL))
        .isEqualTo("SELECT * FROM acct WHERE id = $1");
    assertThat(normalizer.normalize("UPDATE acct SET balance = $1234.56 WHERE id = $1"))
        .isEqualTo("UPDATE acct SET balance = ? WHERE id = $1");
  }

  @Test
  @DisplayName(
      "T-SEC-35c comments: MySQL '--' only before ASCII whitespace or control (incl. DEL), '#'"
          + " also inside a word; the unknown dialect makes a '#' or unspaced '--' comment holding a"
          + " quote <unparsed>")
  void dialectComments() {
    int mysql = SqlDialect.MYSQL;
    String minus = normalizer.normalize("SELECT a--'x\n secret4' FROM t", mysql);
    assertThat(minus).isEqualTo("SELECT a--? FROM t").doesNotContain("secret4");
    assertThat(normalizer.normalize("SELECT a--1 FROM t", mysql)).isEqualTo("SELECT a-? FROM t");
    assertThat(normalizer.normalize("SELECT a -- note 'x\nFROM t", mysql))
        .isEqualTo("SELECT a FROM t");
    assertThat(normalizer.normalize("SELECT a --\tnote\nFROM t", mysql))
        .isEqualTo("SELECT a FROM t");
    assertThat(normalizer.normalize("SELECT id#secret5\nFROM t", mysql))
        .isEqualTo("SELECT id FROM t");
    assertThat(normalizer.normalize("SELECT id#secret5\nFROM t"))
        .as("unknown dialect, no quote in the comment")
        .isEqualTo("SELECT id FROM t");
    assertThat(normalizer.normalize("SELECT a # 'x\n secret3' FROM t"))
        .isEqualTo(SqlNormalizer.UNPARSED);
    assertThat(normalizer.normalize("SELECT a--'x\n secret4' FROM t"))
        .isEqualTo(SqlNormalizer.UNPARSED);
    assertThat(normalizer.normalize("SELECT a # $$x\n secret3$$ FROM t"))
        .isEqualTo(SqlNormalizer.UNPARSED);
    assertThat(normalizer.normalize("SELECT a--x FROM t\nWHERE b = 1"))
        .isEqualTo("SELECT a WHERE b = ?");
    assertThat(normalizer.normalize("SELECT a -- 'x\nFROM t"))
        .as("'-- ' is a comment in both readings")
        .isEqualTo("SELECT a FROM t");
    // A character outside ASCII after "--" (NBSP, U+3000, U+2028, the C1 control U+0085) does not
    // start a MySQL comment: the literal behind it is masked; the unknown dialect reads such a
    // "--" strictly.
    for (String space : new String[] {" ", "　", " ", "\u0085"}) {
      String input = "SELECT 1 --" + space + "'x\n secret4' FROM t";
      String what = "U+" + Integer.toHexString(space.charAt(0));
      unparsedOrNoSeed(what + " (mysql)", normalizer.normalize(input, mysql), "secret4");
      assertThat(normalizer.normalize(input)).as(what).isEqualTo(SqlNormalizer.UNPARSED);
    }
    // ASCII whitespace and control (incl. DEL) after "--", and "--" at the end, stay comments.
    assertThat(normalizer.normalize("SELECT a --\u007fnote\nFROM t", mysql))
        .isEqualTo("SELECT a FROM t");
    assertThat(normalizer.normalize("SELECT a --", mysql)).isEqualTo("SELECT a");
    assertThat(normalizer.normalize("SELECT a --")).isEqualTo("SELECT a");
    assertThat(normalizer.normalize("SELECT a --\tnote 'x\nFROM t")).isEqualTo("SELECT a FROM t");
    // SQL Server keeps '#name' temporary tables (unchanged).
    assertThat(normalizer.normalize("select * from #tmp where a#b = 1", SqlDialect.SQLSERVER))
        .isEqualTo("select * from #tmp where a#b = ?");
  }

  @Test
  @DisplayName(
      "T-SEC-35e SQL Server and CUBRID: a \"...\" whose content is not identifier-shaped (e-mail,"
          + " sentence, digits first, empty, over 128 characters, doubled quote, non-ASCII) is a"
          + " literal; an identifier-shaped one stays with the same text and sql_id as before")
  void doubleQuotedStringsInSqlServerAndCubrid() {
    // ADR-0010 C-183 (d): QUOTED_IDENTIFIER OFF (SQL Server) and ansi_quotes=no (CUBRID) make
    // "..." a string; without a setting key, content that is not identifier-shaped is masked.
    String a128 = repeat('a', 128);
    String a129 = repeat('a', 129);
    String[][] masked = {
      {"SELECT * FROM t WHERE email = \"kim@x.com\"", "SELECT * FROM t WHERE email = ?"},
      {
        "UPDATE t SET note = \"call me at five\" WHERE id = 1", "UPDATE t SET note = ? WHERE id = ?"
      },
      {"SELECT \"010-1234-5678\", \"900101\", \"1abc\" FROM t", "SELECT ?, ?, ? FROM t"},
      {"SELECT \"\" FROM t", "SELECT ? FROM t"},
      {"SELECT \"" + a129 + "\" FROM t", "SELECT ? FROM t"},
      {"SELECT \"it\"\"s\", \"a b\", \"x.y\" FROM t", "SELECT ?, ?, ? FROM t"},
      {"SELECT \"홍길동\" FROM t", "SELECT ? FROM t"},
      {"INSERT INTO t (a, b) VALUES (\"secret 7\", \"x@y\")", "INSERT INTO t (a, b) VALUES (?, ?)"},
    };
    String[] seeds = {"kim", "x.com", "call me", "010", "900101", "1abc", "aaaa", "홍길동", "a b"};
    // Identifier-shaped: an ASCII letter or '_' first, then letters, digits, '_', '$', '#'.
    String kept =
        "SELECT \"Name1\", \"_tmp\", \"col$1\", \"a#b\", \"" + a128 + "\" FROM \"Orders\" o";
    for (int dialect : new int[] {SqlDialect.SQLSERVER, SqlDialect.CUBRID}) {
      for (String[] c : masked) {
        String out = normalizer.normalize(c[0], dialect);
        assertThat(out).as("%s (%d)", c[0], dialect).isEqualTo(c[1]);
        for (String seed : seeds) {
          assertThat(out).as("%s (%d)", c[0], dialect).doesNotContain(seed);
        }
      }
      String out = normalizer.normalize(kept, dialect);
      assertThat(out).as("identifier-shaped (%d)", dialect).isEqualTo(kept);
      assertThat(Dictionary.hash64(out))
          .as("sql_id as in the ANSI dialect, which keeps every \"...\" (%d)", dialect)
          .isEqualTo(Dictionary.hash64(normalizer.normalize(kept, SqlDialect.ANSI)));
      assertThat(normalizer.normalize("SELECT \"abc FROM t WHERE x = 'secret8'", dialect))
          .as("unterminated (%d)", dialect)
          .isEqualTo(SqlNormalizer.UNPARSED);
      // The residual risk of C-183 (d), documented in docs/agent-config.md: under the legacy
      // setting a string that is one identifier-shaped word cannot be told from an identifier.
      assertThat(normalizer.normalize("SELECT * FROM t WHERE name = \"kim\"", dialect))
          .isEqualTo("SELECT * FROM t WHERE name = \"kim\"");
    }
    // The other ANSI-quoting dialects have no such setting: "..." stays an identifier there.
    for (int dialect : new int[] {SqlDialect.POSTGRESQL, SqlDialect.ORACLE, SqlDialect.ANSI}) {
      assertThat(normalizer.normalize(masked[0][0], dialect))
          .as("%d", dialect)
          .isEqualTo(masked[0][0]);
    }
  }

  @Test
  @DisplayName(
      "T-SEC-35f PostgreSQL: a quote after an odd run of backslashes in a plain string is"
          + " <unparsed> (standard_conforming_strings=off would end the string elsewhere); an even"
          + " run, a backslash before another character and E'..' strings are read as before")
  void postgresqlOddBackslashBeforeQuote() {
    // ADR-0010 C-183 (c): fail-safe without a setting key; sql.backslash-escape stays MySQL's.
    int pg = SqlDialect.POSTGRESQL;
    SqlNormalizer standard = new SqlNormalizer(false, SqlNormalizer.MAX_LENGTH);
    for (SqlNormalizer n : new SqlNormalizer[] {normalizer, standard}) {
      for (String input :
          new String[] {
            "select 'C:\\', 'secret9'",
            "select * from t where s = 'a\\'bseed'",
            "select 'a\\\\\\', 'secret9'",
            "select 'a\\'''",
            "select N'x\\', 'secret9'"
          }) {
        assertThat(n.normalize(input, pg)).as(input).isEqualTo(SqlNormalizer.UNPARSED);
      }
      assertThat(n.normalize("select 'C:\\\\', 'x'", pg)).isEqualTo("select ?, ?");
      assertThat(n.normalize("select 'C:\\temp\\x', 'y'", pg)).isEqualTo("select ?, ?");
      assertThat(n.normalize("select E'a\\'b', 'c'", pg)).isEqualTo("select ?, ?");
      assertThat(n.normalize("select $$a\\'b$$, \"c\\\"", pg)).isEqualTo("select ?, \"c\\\"");
      assertThat(n.normalize("select 'abc\\", pg)).as("unterminated").isEqualTo("select ?");
    }
  }

  private static String repeat(char c, int count) {
    StringBuilder out = new StringBuilder(count);
    for (int i = 0; i < count; i++) {
      out.append(c);
    }
    return out.toString();
  }

  @Test
  void nullAndEmpty() {
    assertThat(normalizer.normalize(null)).isNull();
    assertThat(normalizer.normalize("")).isEmpty();
    assertThat(normalizer.normalize("   ")).isEmpty();
  }

  @Test
  void collapseLeavesMixedGroupsAlone() {
    assertThat(SqlNormalizer.collapseLists("where a in (?, b)")).isEqualTo("where a in (?, b)");
    assertThat(SqlNormalizer.collapseLists("where inner in(?)")).isEqualTo("where inner in (?)");
    assertThat(SqlNormalizer.collapseLists("join t_in (?)")).isEqualTo("join t_in (?)");
    assertThat(SqlNormalizer.collapseLists("where a in (?1, ?2)")).isEqualTo("where a in (?1, ?2)");
  }

  @Test
  @DisplayName("dialects of the JDBC vendors (JdbcUrlParser names)")
  void dialectOfVendor() {
    assertThat(SqlDialect.of("mysql")).isEqualTo(SqlDialect.MYSQL);
    assertThat(SqlDialect.of("MariaDB")).isEqualTo(SqlDialect.MYSQL);
    assertThat(SqlDialect.of("postgresql")).isEqualTo(SqlDialect.POSTGRESQL);
    assertThat(SqlDialect.of("oracle")).isEqualTo(SqlDialect.ORACLE);
    assertThat(SqlDialect.of("tibero")).isEqualTo(SqlDialect.ORACLE);
    assertThat(SqlDialect.of("sqlserver")).isEqualTo(SqlDialect.SQLSERVER);
    for (String ansi : new String[] {"h2", "db2", "altibase"}) {
      assertThat(SqlDialect.of(ansi)).as(ansi).isEqualTo(SqlDialect.ANSI);
    }
    // ANSI quoting with the "..." rule of ADR-0010 C-183 (d).
    assertThat(SqlDialect.of("cubrid")).isEqualTo(SqlDialect.CUBRID);
    assertThat(SqlDialect.of("sqlite")).isEqualTo(SqlDialect.UNKNOWN);
    assertThat(SqlDialect.of(null)).isEqualTo(SqlDialect.UNKNOWN);
    assertThat(SqlDialect.valid(99)).isEqualTo(SqlDialect.UNKNOWN);
  }
}
