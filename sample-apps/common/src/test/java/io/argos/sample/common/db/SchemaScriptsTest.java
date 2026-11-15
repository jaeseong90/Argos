/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.common.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The schema script of every SAMPLE_DB value splits into the same objects (five tables and the
 * order item index; H2 adds its sleep alias); Oracle's PL/SQL blocks keep their own semicolons and
 * end at the '/' line, the other dialects' statements lose their final ';'.
 */
class SchemaScriptsTest {

  @Test
  @DisplayName("Every database's script creates the five tables and the index")
  void sixObjectsPerDatabase() {
    for (DbKind db : DbKind.values()) {
      List<String> statements = ShopSchema.statements(db);
      // H2 adds its sleep function alias (CREATE ALIAS); MySQL and MariaDB declare the index inside
      // CREATE TABLE.
      int expected = db == DbKind.H2 ? 7 : db == DbKind.MYSQL || db == DbKind.MARIADB ? 5 : 6;
      assertThat(statements).as(db.id()).hasSize(expected);
      String all = String.join("\n", statements).toLowerCase(Locale.ROOT);
      for (String table :
          new String[] {"customers", "products", "orders", "order_items", "audit_log"}) {
        assertThat(all)
            .as("%s creates %s", db.id(), table)
            .containsPattern("create table (if not exists )?" + table + " ");
      }
      assertThat(all).as(db.id()).contains("order_items_order_idx");
    }
  }

  @Test
  @DisplayName(
      "Oracle: one PL/SQL block per object, ending in END; (ORA-00955 ignored); SQL Server checks"
          + " OBJECT_ID; no other statement keeps its ';'")
  void dialects() {
    for (String block : ShopSchema.statements(DbKind.ORACLE)) {
      assertThat(block).startsWith("BEGIN").endsWith("END;").contains("EXECUTE IMMEDIATE");
      assertThat(block).contains("-955");
    }
    List<String> mssql = ShopSchema.statements(DbKind.MSSQL);
    assertThat(mssql.get(0)).startsWith("IF OBJECT_ID(N'customers', N'U') IS NULL CREATE TABLE");
    assertThat(String.join("\n", mssql)).doesNotContain("TIMESTAMP").contains("DATETIME2");
    for (DbKind db : new DbKind[] {DbKind.H2, DbKind.POSTGRES, DbKind.MYSQL, DbKind.MARIADB}) {
      for (String statement : ShopSchema.statements(db)) {
        assertThat(statement).as(db.id()).doesNotEndWith(";").startsWith("CREATE");
      }
    }
  }
}
