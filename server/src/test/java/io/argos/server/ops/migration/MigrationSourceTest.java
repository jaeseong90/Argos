/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.storage.clickhouse.RepositoryLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The bundled copy of deploy/clickhouse and the file rules of the catalog (13 §13.2). */
@Tag("req:N-22")
class MigrationSourceTest {

  private static final String V001 =
      "-- SPDX-License-Identifier: Apache-2.0\nCREATE DATABASE IF NOT EXISTS {db};\n";

  @Test
  @DisplayName(
      "T-ST-052 the jar resources db/clickhouse are exactly deploy/clickhouse schema, rollups and"
          + " migrations (single source)")
  void classpathCopyEqualsRepository() {
    MigrationSource bundled = MigrationSource.fromClasspath(getClass().getClassLoader());
    MigrationSource repository = MigrationSource.fromDirectory(RepositoryLayout.clickhouseDir());

    assertThat(bundled.files()).isEqualTo(repository.files());
    assertThat(bundled.names("schema")).contains("V001__init.sql");
    assertThat(bundled.names("rollups")).hasSize(13);
    assertThat(bundled.files().keySet()).noneMatch(p -> p.endsWith("README.md"));
    MigrationCatalog catalog = MigrationCatalog.load(bundled, new SchemaTemplates(bundled));
    assertThat(catalog.scripts().get(0).version()).isEqualTo("V001");
    assertThat(catalog.scripts().get(0).description()).isEqualTo("init");
  }

  @Test
  @DisplayName("T-ST-052 file names: V<NNN>__ and M<NNN>__ only, contiguous numbers, no U files")
  void fileRules() {
    assertRefused(
        Map.of("schema/V001__init.sql", V001, "schema/V003__gap.sql", V001), "contiguous");
    assertRefused(Map.of("schema/V001__init.sql", V001, "schema/U001__undo.sql", V001), "U001");
    assertRefused(Map.of("schema/V001__Init.sql", V001), "unexpected file");
    assertRefused(Map.of("schema/V1__init.sql", V001), "unexpected file");
    assertRefused(
        Map.of("schema/V001__init.sql", V001, "schema/V001__again.sql", V001), "share a number");
    assertRefused(
        Map.of("schema/V001__init.sql", V001, "rollups/Bad-Name.sql", "SELECT 1"), "rollups");
    assertRefused(
        Map.of("schema/V001__init.sql", V001, "migrations/M002__x.yaml", "description: x"),
        "contiguous");
    assertRefused(Map.of("rollups/x.sql", "SELECT 1 WHERE true {where}"), "no schema/V001");
  }

  @Test
  @DisplayName("T-ST-052 unknown placeholders are refused, naming the file (C-56)")
  void unknownPlaceholder() {
    assertRefused(
        Map.of("schema/V001__init.sql", "CREATE TABLE IF NOT EXISTS {db}.{p}t {bloom.fpp}"),
        "unknown placeholders [bloom.fpp]");
    assertRefused(
        Map.of("schema/V001__init.sql", "SELECT {retention.forever-days}"),
        "retention.forever-days");
    assertRefused(
        Map.of("schema/V001__init.sql", "SELECT {where}"), "unknown placeholders [where]");
    assertRefused(Map.of("schema/V001__init.sql", "AS {rollup:missing}"), "rollup:missing");
  }

  @Test
  @DisplayName("directory sources skip README.md and refuse subdirectories; overlay replaces")
  void directoryAndOverlay(@TempDir Path dir) throws Exception {
    Files.createDirectories(dir.resolve("schema"));
    Files.writeString(dir.resolve("schema/V001__init.sql"), V001);
    Files.writeString(dir.resolve("schema/README.md"), "doc");
    MigrationSource source = MigrationSource.fromDirectory(dir);
    assertThat(source.files()).containsOnlyKeys("schema/V001__init.sql");

    MigrationSource overlay =
        source.overlay(MigrationSource.of("fixture", Map.of("schema/V001__init.sql", "changed")));
    assertThat(overlay.read("schema/V001__init.sql")).contains("changed");

    Files.createDirectories(dir.resolve("schema/nested"));
    assertThatThrownBy(() -> MigrationSource.fromDirectory(dir))
        .hasMessageContaining("no subdirectories");
  }

  private static void assertRefused(Map<String, String> files, String message) {
    MigrationSource source = MigrationSource.of("test", files);
    assertThatThrownBy(() -> MigrationCatalog.load(source, new SchemaTemplates(source)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(message);
  }
}
