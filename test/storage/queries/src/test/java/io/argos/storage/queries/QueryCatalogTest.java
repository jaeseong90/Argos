/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.queries;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.storage.clickhouse.Account;
import io.argos.storage.queries.QueryFile.Kind;
import io.argos.storage.queries.QueryFile.Section;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The query files of the repository follow the catalog rules (no Docker). */
class QueryCatalogTest {

  private static final QueryCatalog CATALOG = QueryCatalog.fromRepository();

  @Test
  @DisplayName("Q01..Q20 and params.sql parse and pass every catalog rule")
  void repositoryCatalogIsValid() {
    assertThat(CATALOG.validate()).isEmpty();
    assertThat(CATALOG.queries()).hasSize(20);
    for (int i = 1; i <= 20; i++) {
      String id = String.format(Locale.ROOT, "Q%02d", i);
      QueryFile f = CATALOG.query(id);
      assertThat(f.source()).as(id).contains("§15 Q" + i);
      assertThat(f.description()).as(id).isNotBlank();
    }
  }

  @Test
  @DisplayName(
      "Q1..Q11 are timing files run as argos_reader within the reader limits (13 §6.3, §7.3)")
  void timingFilesUseTheReader() {
    for (QueryFile f : CATALOG.queries()) {
      if (f.kind() != Kind.TIMING) {
        continue;
      }
      assertThat(f.id()).isLessThanOrEqualTo("Q11");
      assertThat(f.account()).isEqualTo(Account.READER);
      assertThat(Long.parseLong(f.settings().get("max_execution_time"))).isBetween(1L, 10L);
      for (Section v : f.variants()) {
        assertThat(v.account()).as(f.id() + "/" + v.name()).isNull();
        Criterion c = v.criterion() != null ? v.criterion() : f.criterion();
        assertThat(c.isRecord() || c.terms().get(0).metric().equals("warm_ms")).isTrue();
      }
    }
  }

  @Test
  @DisplayName("13 §15 criteria: Q1 1 s, Q3 2 s, Q5 0.5 s, Q11 2 s; Q12/Q13 0 rows")
  void criteriaOfTheSpecification() {
    assertThat(CATALOG.query("Q01").criterion().toString()).isEqualTo("warm_ms <= 1000");
    assertThat(CATALOG.query("Q03").criterion().toString()).isEqualTo("warm_ms <= 2000");
    assertThat(CATALOG.query("Q05").criterion().toString()).isEqualTo("warm_ms <= 500");
    assertThat(CATALOG.query("Q06").variant("24h").criterion().toString())
        .isEqualTo("warm_ms <= 1000");
    assertThat(CATALOG.query("Q11").criterion().toString()).isEqualTo("warm_ms <= 2000");
    assertThat(CATALOG.query("Q12").kind()).isEqualTo(Kind.CONSISTENCY);
    assertThat(CATALOG.query("Q12").criterion().toString()).isEqualTo("rows == 0");
    assertThat(CATALOG.query("Q13").criterion().toString()).isEqualTo("rows == 0");
    assertThat(CATALOG.query("Q14").criterion().toString())
        .isEqualTo("p50_err <= 0.02, p95_err <= 0.02, p99_err <= 0.05");
    assertThat(CATALOG.query("Q17").uses()).containsExactly("Q01/1h", "Q03/10m", "Q07/1d_live");
  }

  @Test
  @DisplayName("Probes declare their parameters with top-level aliases")
  void probeOutputs() {
    Section range = CATALOG.parameters().probes().get(0);
    assertThat(range.outputs()).contains("end_minute", "first_day", "last_day", "full_day");
    assertThat(CATALOG.query("Q05").probes().get(0).outputs())
        .containsExactly("tx", "tx_ts", "tx_old", "ptx");
    assertThat(
            QueryCatalog.topLevelAliases(
                "SELECT (SELECT x AS y FROM t) AS a, f(b AS c) AS d, 'AS e' FROM t"))
        .containsExactly("a", "d");
  }

  @Test
  @DisplayName("Malformed files are rejected with the file and line")
  void malformedFiles() {
    Path p = Path.of("Q99.sql");
    String head =
        """
        -- SPDX-License-Identifier: Apache-2.0
        -- @id Q99
        -- @title t
        -- @source s
        -- @kind timing
        -- @criterion warm_ms <= 1
        """;
    assertThatThrownBy(() -> QueryCatalog.parse(p, "-- no header\n")).hasMessageContaining("SPDX");
    assertThatThrownBy(() -> QueryCatalog.parse(p, head + "SELECT 1\n"))
        .hasMessageContaining("SQL outside a section");
    assertThatThrownBy(
            () -> QueryCatalog.parse(p, head + "-- @variant a\nSELECT 1\n-- @expect rows >= 1\n"))
        .hasMessageContaining("after the SQL");
    assertThatThrownBy(() -> QueryCatalog.parse(p, head + "-- @variant a\nSELECT 1;\nSELECT 2\n"))
        .hasMessageContaining("several statements");
    assertThatThrownBy(
            () ->
                QueryCatalog.parse(p, head + "-- @variant a\nSELECT 1\n-- @variant a\nSELECT 2\n"))
        .hasMessageContaining("duplicate");
    assertThatThrownBy(() -> QueryCatalog.parse(p, head + "-- @bogus x\n"))
        .hasMessageContaining("unknown directive");
    QueryFile ok =
        QueryCatalog.parse(
            p,
            head
                + "-- @variant a days=7\n-- @expect rows >= 1\n-- comment\nSELECT {days:UInt32};\n");
    assertThat(ok.variant("a").sql()).isEqualTo("SELECT {days:UInt32}");
    assertThat(ok.variant("a").params()).containsEntry("days", "7");
    assertThat(ok.variant("a").expect().toString()).isEqualTo("rows >= 1");
    assertThatThrownBy(
            () -> QueryCatalog.parse(p, head + "-- @variant a\n-- @optional why\nSELECT 1\n"))
        .hasMessageContaining("@optional needs a reason and is for probes only");
    assertThatThrownBy(
            () -> QueryCatalog.parse(p, head + "-- @probe a\n-- @optional\nSELECT 1 AS x\n"))
        .hasMessageContaining("@optional needs a reason");
    QueryFile optional =
        QueryCatalog.parse(p, head + "-- @probe a\n-- @optional no such case\nSELECT 1 AS x\n");
    assertThat(optional.probes().get(0).optional()).isEqualTo("no such case");
    assertThat(CATALOG.query("Q05").probes().get(1).optional()).isNotBlank();
    assertThat(CATALOG.query("Q05").probes().get(1).outputs()).containsExactly("tx_skewed");
  }

  @Test
  @DisplayName("Validation reports missing files, unsourced parameters and unknown placeholders")
  void validationProblems(@TempDir Path dir) throws java.io.IOException {
    String spdx = "-- SPDX-License-Identifier: Apache-2.0\n";
    java.nio.file.Files.writeString(
        dir.resolve("params.sql"), spdx + "-- @probe range\nSELECT 1 AS end_minute\n");
    java.nio.file.Files.writeString(
        dir.resolve("Q01.sql"),
        spdx
            + "-- Q1\n-- @id Q01\n-- @title t\n-- @source s\n-- @kind timing\n"
            + "-- @criterion record\n-- @variant a\n"
            + "SELECT {end_minute:DateTime}, {nothing:UInt32} FROM {db}.{q}xlog\n");
    List<String> problems = QueryCatalog.load(dir).validate();
    assertThat(problems)
        .contains(
            "missing Q02.sql",
            "Q01/a: parameter {nothing} has no source",
            "Q01/a: unknown placeholder {q}")
        .noneMatch(m -> m.contains("end_minute"));
  }
}
