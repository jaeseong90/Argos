/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.sql;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** SqlNormalizer (10 §9.4, D-22, SEC-35): literals never survive, statement shape does. */
@Tag("req:SEC-35")
@Tag("req:D-22")
class SqlNormalizerTest {
  private final SqlNormalizer normalizer = SqlNormalizer.defaults();

  static List<String[]> cases() {
    return Arrays.asList(
        new String[] {"select * from orders where id = 42", "select * from orders where id = ?"},
        new String[] {"SELECT * FROM t WHERE name = 'kim'", "SELECT * FROM t WHERE name = ?"},
        new String[] {
          "select ?, :name, :1, $1, @p1, @user, ?2", "select ?, :name, :1, $1, @p1, @user, ?2"
        },
        new String[] {"select * from t where a in (1, 2, 3)", "select * from t where a in (?)"},
        new String[] {"select * from t where a IN (?,?,?)", "select * from t where a IN (?)"},
        new String[] {
          "select * from t where a not in ('x','y')", "select * from t where a not in (?)"
        },
        new String[] {
          "insert into t (a, b) values (1, 'x'), (2, 'y'), (3, 'z')",
          "insert into t (a, b) values (?, ?)"
        },
        new String[] {
          "insert into t values (?, now()), (?, now())",
          "insert into t values (?, now()), (?, now())"
        },
        new String[] {"select 'it''s', 'a\\'b' from dual", "select ?, ? from dual"},
        new String[] {"select N'홍길동', E'\\x41', X'0F', B'101'", "select ?, ?, ?, ?"},
        new String[] {
          "select * from t -- where email = 'kim@example.test'\nwhere x = 1",
          "select * from t where x = ?"
        },
        new String[] {
          "select /*+ INDEX(t i) */ * from t /* id = 5 /* nested */ */ where y = 2",
          "select * from t where y = ?"
        },
        new String[] {
          "select * from t # mysql 'comment'\nwhere z = 3", "select * from t where z = ?"
        },
        new String[] {
          "select * from #temp join ##global g on 1 = 1",
          "select * from #temp join ##global g on ? = ?"
        },
        new String[] {"select 1.5, .25, 1e10, 2.5E-3, 0xFF, 0x1a2b", "select ?, ?, ?, ?, ?, ?"},
        new String[] {
          "select a-1, b - 2, f(-3), x = -4, y=+5", "select a-?, b - ?, f(?), x = ?, y=?"
        },
        new String[] {"select col1, t_2024.v2 from t_2024", "select col1, t_2024.v2 from t_2024"},
        new String[] {
          "select \"Name 1\", `order`, [Total 2] from t",
          "select \"Name 1\", `order`, [Total 2] from t"
        },
        new String[] {"select arr[1], ARRAY['a', 'b']", "select arr[?], ARRAY[?, ?]"},
        new String[] {"select $$secret text$$, $tag$more 'x'$tag$", "select ?, ?"},
        new String[] {"select   *\n\tfrom  t\r\n where  a=1  ", "select * from t where a=?"},
        new String[] {"{call proc(?, 'x', 3)}", "{call proc(?, ?, ?)}"},
        new String[] {"select x::int, v$session from t", "select x::int, v$session from t"},
        new String[] {"select 'unterminated", "select ?"},
        new String[] {"update t set a = 'x' where b = 'y'", "update t set a = ? where b = ?"});
  }

  @ParameterizedTest
  @MethodSource("cases")
  void normalizes(String input, String expected) {
    assertThat(normalizer.normalize(input)).isEqualTo(expected);
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
    assertThat(small.normalize("select * from t where a = 'abcdefghijklmnopqrstuvwxyz'"))
        .isEqualTo("select * from t where a = ?");
  }

  @Test
  void backslashEscapeCanBeTurnedOff() {
    SqlNormalizer standard = new SqlNormalizer(false, SqlNormalizer.MAX_LENGTH);
    assertThat(standard.normalize("select 'C:\\' , 'x'")).isEqualTo("select ? , ?");
    assertThat(normalizer.normalize("select 'a\\'b'")).isEqualTo("select ?");
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
  }
}
