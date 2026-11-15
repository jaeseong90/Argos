/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.migration;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.storage.clickhouse.RepositoryLayout;
import io.argos.storage.schema.SchemaRenderer;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The server's ClickHouse renderer is a port of the WO-03 reference ({@code
 * io.argos.storage.schema.SchemaRenderer} of test/storage/clickhouse-support): for every file of
 * {@code deploy/clickhouse/schema}, both produce the same expansion, checksum (C-56) and
 * statements, with the default parameters and with another database, prefix and retention; and the
 * checksum does not depend on those parameters.
 */
@Tag("req:N-22")
class SchemaTemplatesEquivalenceTest {

  private static final Path DEPLOY = RepositoryLayout.clickhouseDir();

  @Test
  @DisplayName(
      "T-ST-052 every deploy/clickhouse/schema file: same expansion, checksum and statements as"
          + " the reference renderer")
  void sameAsReference() {
    SchemaRenderer reference = new SchemaRenderer(DEPLOY);
    MigrationSource source = MigrationSource.fromDirectory(DEPLOY);
    SchemaTemplates templates = new SchemaTemplates(source);
    MigrationCatalog catalog = MigrationCatalog.load(source, templates);

    List<Path> referenceFiles = reference.migrations();
    assertThat(catalog.scripts()).hasSameSizeAs(referenceFiles).isNotEmpty();
    assertThat(templates.rollupTables()).isEqualTo(reference.rollupTables());
    for (int i = 0; i < referenceFiles.size(); i++) {
      Path file = referenceFiles.get(i);
      SchemaScript script = catalog.scripts().get(i);
      assertThat(script.path()).isEqualTo("schema/" + file.getFileName());
      assertThat(script.expanded()).as(script.path()).isEqualTo(reference.expand(file));
      assertThat(script.checksum()).as(script.path()).isEqualTo(reference.checksum(file));
      for (Variant v : variants()) {
        assertThat(SchemaTemplates.render(script.expanded(), v.server()))
            .as(script.path() + " " + v.name())
            .isNotEmpty()
            .isEqualTo(reference.render(file, v.reference()));
      }
    }
  }

  @Test
  @DisplayName(
      "T-ST-052 rollup SELECTs with a WHERE extension render like the reference (backfill, D-14)")
  void rollupSelectsSameAsReference() {
    SchemaRenderer reference = new SchemaRenderer(DEPLOY);
    SchemaTemplates templates = new SchemaTemplates(MigrationSource.fromDirectory(DEPLOY));
    for (String table : reference.rollupTables()) {
      for (Variant v : variants()) {
        assertThat(templates.rollupSelect(table, v.server(), "AND (toDate(ts) = '2026-01-01')"))
            .as(table + " " + v.name())
            .isEqualTo(
                reference.rollupSelect(table, v.reference(), "AND (toDate(ts) = '2026-01-01')"));
        assertThat(templates.rollupSelect(table, v.server(), ""))
            .isEqualTo(reference.rollupSelect(table, v.reference(), ""));
      }
    }
  }

  @Test
  @DisplayName("T-ST-052 C-56: database, prefix and retention never change a checksum")
  void checksumIndependentOfParameters() {
    MigrationSource source = MigrationSource.fromDirectory(DEPLOY);
    SchemaTemplates templates = new SchemaTemplates(source);
    MigrationCatalog catalog = MigrationCatalog.load(source, templates);
    SchemaScript v001 = catalog.scripts().get(0);
    List<String> defaults = SchemaTemplates.render(v001.expanded(), SchemaParameters.defaults());
    List<String> other = SchemaTemplates.render(v001.expanded(), variants().get(1).server());

    assertThat(other).isNotEqualTo(defaults);
    assertThat(String.join(";", other)).contains("argos_t.t1_xlog").doesNotContain("{");
    assertThat(v001.checksum())
        .isEqualTo(SchemaTemplates.checksum(v001.expanded()))
        .matches("[0-9a-f]{64}");
  }

  private record Variant(
      String name, SchemaParameters server, io.argos.storage.schema.SchemaParameters reference) {}

  private static List<Variant> variants() {
    Map<String, Integer> days = new LinkedHashMap<>();
    days.put("xlog-days", 3);
    days.put("profile-days", 2);
    days.put("raw-days", 5);
    days.put("rollup-1m-days", 10);
    days.put("rollup-1h-days", 20);
    io.argos.storage.schema.SchemaParameters reference =
        io.argos.storage.schema.SchemaParameters.defaults()
            .withDatabase("argos_t")
            .withTablePrefix("t1_");
    for (Map.Entry<String, Integer> e : days.entrySet()) {
      reference = reference.withRetentionDays(e.getKey(), e.getValue());
    }
    return List.of(
        new Variant(
            "defaults",
            SchemaParameters.defaults(),
            io.argos.storage.schema.SchemaParameters.defaults()),
        new Variant(
            "argos_t/t1_/3-2-5-10-20", new SchemaParameters("argos_t", "t1_", days), reference));
  }
}
