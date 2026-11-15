/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.sql;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The 1 ms budget of the normalizer (10 §9.4, DSN-10-17) as a character-read bound: 64 KiB inputs
 * built to make a tokenizer look ahead and back (unclosed brackets, dollar tags, nested comments,
 * marker lists, numbers, quotes) stay within a fixed multiple of their length, so the time per
 * statement is bounded by {@code sql.max-length} without a clock. The wall-clock check of the same
 * inputs is {@code SqlNormalizerTimingIntegrationTest} (a slow integration test, ADR-0010 C-146
 * (2)).
 */
@Tag("req:SEC-35")
@Tag("req:D-22")
class SqlNormalizerBudgetTest {
  /** Character reads per input character the worst inputs below actually need, at most. */
  static final int OBSERVED_BOUND_PER_CHAR = 3;

  /** 64 KiB statements that stress every look-ahead of the tokenizer. */
  static List<String> worstCases() {
    int n = SqlNormalizer.MAX_LENGTH;
    return Arrays.asList(
        fill("select ", "[a ", n),
        fill("select ", "[ab.cd e", n),
        fill("select ", "$a$", n),
        fill("select ", "in (?, ?, ?, ", n),
        fill("select ", "values (?,?),(?, x", n),
        fill("select ", "/*", n),
        fill("select ", "'a''b'", n),
        fill("select ", "1.5e+3 0x1F ", n),
        fill("select ", "-1,", n),
        fill("select ", "col_1 = :p1 and ", n),
        fill("select ", "q'[x]' ", n),
        fill("select ", "-- c\n", n),
        fill("select ", "\"a\" ", n),
        fill("select ", "a", n));
  }

  /** {@code head} and whole copies of {@code unit} up to {@code length} characters. */
  static String fill(String head, String unit, int length) {
    StringBuilder out = new StringBuilder(length + unit.length());
    out.append(head);
    while (out.length() + unit.length() <= length) {
      out.append(unit);
    }
    return out.toString();
  }

  @Test
  @DisplayName(
      "T-AG-034 normalization budget: 64 KiB worst-case inputs need at most 3 character reads per"
          + " character (the budget allows 8 plus 4,096), so they are never <unparsed> for time")
  void worstCasesStayWithinTheBudget() {
    for (String sql : worstCases()) {
      int dialect = sql.contains("\"") ? SqlDialect.POSTGRESQL : SqlDialect.UNKNOWN;
      SqlTokenizer tokens =
          new SqlTokenizer(sql, 0, sql.length(), dialect, true, false, Long.MAX_VALUE);
      while (tokens.next() != SqlTokenizer.END) {
        // count only
      }
      String head = sql.substring(0, 24);
      assertThat(tokens.work())
          .as("character reads of %s...", head)
          .isLessThanOrEqualTo((long) OBSERVED_BOUND_PER_CHAR * sql.length());
      assertThat(SqlNormalizer.defaults().normalize(sql, dialect))
          .as("normalized %s...", head)
          .isNotEqualTo(SqlNormalizer.UNPARSED);
    }
  }

  @Test
  @DisplayName(
      "T-AG-034 normalization budget of the ADR-0010 C-183 dialect rules: SQL Server and CUBRID"
          + " \"...\" shape checks and PostgreSQL backslash runs need at most 3 character reads per"
          + " character")
  void dialectRulesStayWithinTheBudget() {
    String id127 = fill("", "a", 127);
    Object[][] inputs = {
      {fill("select ", "\"" + id127 + "\" ", SqlNormalizer.MAX_LENGTH), SqlDialect.SQLSERVER},
      {fill("select ", "\"" + id127 + "\" ", SqlNormalizer.MAX_LENGTH), SqlDialect.CUBRID},
      {fill("select ", "\"a b\" ", SqlNormalizer.MAX_LENGTH), SqlDialect.SQLSERVER},
      {fill("select ", "\"" + id127 + "aa\" ", SqlNormalizer.MAX_LENGTH), SqlDialect.CUBRID},
      {fill("select ", "'\\\\\\\\x' ", SqlNormalizer.MAX_LENGTH), SqlDialect.POSTGRESQL},
    };
    for (Object[] input : inputs) {
      String sql = (String) input[0];
      int dialect = (Integer) input[1];
      SqlTokenizer tokens =
          new SqlTokenizer(sql, 0, sql.length(), dialect, true, false, Long.MAX_VALUE);
      while (tokens.next() != SqlTokenizer.END) {
        // count only
      }
      String what = sql.substring(0, 24) + "... (" + dialect + ")";
      assertThat(tokens.work())
          .as("character reads of %s", what)
          .isLessThanOrEqualTo((long) OBSERVED_BOUND_PER_CHAR * sql.length());
      assertThat(SqlNormalizer.defaults().normalize(sql, dialect))
          .as("normalized %s", what)
          .isNotEqualTo(SqlNormalizer.UNPARSED);
    }
  }

  @Test
  @DisplayName(
      "T-SEC-35 a statement past its budget is <unparsed>: an adversarial dollar tag search, and"
          + " failure injection with no budget at all")
  void budgetOverrunIsUnparsed() {
    // "$abcdefgh$" then 64 KiB of '$': every '$' starts a comparison with the 10-character tag.
    String search = "select $abcdefgh$" + fill("", "$", SqlNormalizer.MAX_LENGTH - 17);
    assertThat(SqlNormalizer.defaults().normalize(search, SqlDialect.POSTGRESQL))
        .isEqualTo(SqlNormalizer.UNPARSED);
    SqlNormalizer none = new SqlNormalizer(true, SqlNormalizer.MAX_LENGTH, false, 0);
    assertThat(none.normalize("select * from t where card = '4111111111111111'"))
        .isEqualTo(SqlNormalizer.UNPARSED);
    // The cut bounds the work as well: only sql.max-length characters are read.
    String huge = fill("select ", "a,", 10 * SqlNormalizer.MAX_LENGTH);
    SqlTokenizer tokens =
        new SqlTokenizer(
            huge, 0, SqlNormalizer.MAX_LENGTH, SqlDialect.UNKNOWN, true, false, 1L << 40);
    while (tokens.next() != SqlTokenizer.END) {
      // count only
    }
    assertThat(tokens.work()).isLessThanOrEqualTo(SqlNormalizer.MAX_LENGTH);
  }
}
