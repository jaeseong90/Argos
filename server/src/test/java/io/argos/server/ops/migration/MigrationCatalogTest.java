/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The ordering rule between schema scripts and procedures (13 §13.2: a new installation and an
 * upgrade must reach the same schema): a {@code V} script does not reference an object that a
 * procedure creates, backfills, switches to or drops, except the first script that creates a
 * dropped object. The fixtures are those of {@link ClickHouseMigrationRunnerTest}: {@code V001}
 * creates {@code xlog}, {@code r_1m} and {@code r_1m_mv}; {@code M001} creates {@code r_1m_v2} and
 * {@code r_1m_v2_mv}, backfills and switches to {@code r_1m_v2} and drops {@code r_1m_mv} and
 * {@code r_1m}.
 */
@Tag("req:N-22")
class MigrationCatalogTest {

  private static MigrationSource source(String v002) {
    Map<String, String> files = new LinkedHashMap<>();
    files.put("schema/V001__init.sql", ClickHouseMigrationRunnerTest.V001);
    files.put("schema/V002__next.sql", v002);
    files.put("rollups/r_1m.sql", ClickHouseMigrationRunnerTest.ROLLUP);
    files.put("rollups/r_1m_v2.sql", ClickHouseMigrationRunnerTest.ROLLUP);
    files.put("migrations/M001__r_v2.yaml", ClickHouseMigrationRunnerTest.M001);
    return MigrationSource.of("test", files);
  }

  private static MigrationCatalog load(MigrationSource source) {
    return MigrationCatalog.load(source, new SchemaTemplates(source));
  }

  @Test
  @DisplayName(
      "T-ST-052 a V script that alters a table a procedure creates is refused, naming the script,"
          + " the object and the ddl alternative")
  void scriptAltersProcedureTable() {
    MigrationSource source =
        source("ALTER TABLE {db}.{p}r_1m_v2 ADD COLUMN IF NOT EXISTS note String DEFAULT '';\n");

    assertThatThrownBy(() -> load(source))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("schema/V002__next.sql references r_1m_v2")
        .hasMessageContaining("M001.create_v2 creates it")
        .hasMessageContaining("M001.backfill backfills it")
        .hasMessageContaining("M001.switch switches to it")
        .hasMessageContaining("ADD COLUMN IF NOT EXISTS")
        .hasMessageNotContaining("schema/V001__init.sql references");
  }

  @Test
  @DisplayName(
      "T-ST-052 a V script that alters a table a procedure drops is refused; the script that"
          + " creates it (V001) is not")
  void scriptAltersDroppedTable() {
    MigrationSource source =
        source("ALTER TABLE {db}.{p}r_1m ADD COLUMN IF NOT EXISTS note String DEFAULT '';\n");

    assertThatThrownBy(() -> load(source))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("schema/V002__next.sql references r_1m (M001.drop_old drops it)")
        .hasMessageNotContaining("schema/V001__init.sql references");
  }

  @Test
  @DisplayName(
      "T-ST-052 a V script and a procedure on different tables load; comments and quoted text"
          + " naming a procedure object do not count")
  void differentTables() {
    MigrationSource source =
        source(
            """
            -- M001 replaces {db}.{p}r_1m by {db}.{p}r_1m_v2; this script does not touch them
            ALTER TABLE {db}.{p}xlog ADD COLUMN IF NOT EXISTS note String DEFAULT '' \
            COMMENT '{db}.{p}r_1m_v2';
            """);

    MigrationCatalog catalog = load(source);

    assertThat(catalog.scripts()).extracting(SchemaScript::version).containsExactly("V001", "V002");
    assertThat(catalog.steps())
        .extracting(Procedure.Step::version)
        .containsExactly("M001.create_v2", "M001.backfill", "M001.switch", "M001.drop_old");
    assertThat(MigrationCatalog.objects(catalog.scripts().get(1).expanded()))
        .containsExactly("xlog");
  }

  @Test
  @DisplayName(
      "T-ST-052 a later V script that creates a dropped object again is refused (only the first"
          + " creator is exempt)")
  void laterScriptRecreatesDroppedTable() {
    MigrationSource source =
        source(
            """
            CREATE TABLE IF NOT EXISTS {db}.{p}r_1m (minute DateTime('UTC'), c UInt64)
            ENGINE = SummingMergeTree PARTITION BY toDate(minute) ORDER BY minute;
            """);

    assertThatThrownBy(() -> load(source))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("schema/V002__next.sql references r_1m (M001.drop_old drops it)");
  }
}
