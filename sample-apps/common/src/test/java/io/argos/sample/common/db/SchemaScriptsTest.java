/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.common.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.Statement;
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
      // CREATE TABLE; Tibero and Altibase add the audit_log sequence.
      int expected =
          db == DbKind.H2 || db == DbKind.TIBERO || db == DbKind.ALTIBASE
              ? 7
              : db == DbKind.MYSQL || db == DbKind.MARIADB ? 5 : 6;
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
    for (DbKind db :
        new DbKind[] {
          DbKind.H2,
          DbKind.POSTGRES,
          DbKind.MYSQL,
          DbKind.MARIADB,
          DbKind.TIBERO,
          DbKind.ALTIBASE,
          DbKind.CUBRID,
          DbKind.DB2
        }) {
      for (String statement : ShopSchema.statements(db)) {
        assertThat(statement).as(db.id()).doesNotEndWith(";").startsWith("CREATE");
      }
    }
  }

  @Test
  @DisplayName(
      "WO-50 vendor databases (Tibero, Altibase, CUBRID, Db2): plain CREATE statements, run only for"
          + " objects the JDBC metadata does not list (checked here on H2's metadata)")
  void vendorScriptsCreateMissingObjectsOnly() throws Exception {
    for (DbKind db : DbKind.values()) {
      assertThat(db.createsMissingObjectsOnly()).as(db.id()).isEqualTo(db.vendorDriver());
      for (String statement : ShopSchema.statements(db)) {
        if (db.vendorDriver()) {
          assertThat(statement.toUpperCase(Locale.ROOT)).doesNotContain("IF NOT EXISTS");
        }
      }
    }
    assertThat(ShopSchema.statements(DbKind.ALTIBASE))
        .anyMatch(s -> s.startsWith("CREATE SEQUENCE audit_log_seq"));
    assertThat(DbKind.ALTIBASE.auditInsertSql()).contains("audit_log_seq.NEXTVAL");
    assertThat(DbKind.DB2.auditInsertSql()).doesNotContain("audit_log_seq");
    try (Connection c =
        DriverManager.getConnection(
            "jdbc:h2:mem:schema-scripts-test;DB_CLOSE_DELAY=-1", "sa", "")) {
      try (Statement st = c.createStatement()) {
        st.execute("CREATE TABLE customers (id BIGINT PRIMARY KEY)");
        st.execute("CREATE TABLE order_items (id BIGINT PRIMARY KEY, order_id BIGINT)");
        st.execute("CREATE INDEX order_items_order_idx ON order_items (order_id)");
      }
      DatabaseMetaData metaData = c.getMetaData();
      String schema = "PUBLIC";
      assertThat(ShopSchema.exists(metaData, schema, "CREATE TABLE customers (\n  id BIGINT\n)"))
          .isTrue();
      assertThat(ShopSchema.exists(metaData, schema, "create table products (id BIGINT)"))
          .isFalse();
      assertThat(
              ShopSchema.exists(
                  metaData, schema, "CREATE INDEX order_items_order_idx ON order_items (order_id)"))
          .isTrue();
      assertThat(
              ShopSchema.exists(
                  metaData, schema, "CREATE INDEX other_idx ON order_items (order_id)"))
          .isFalse();
      assertThat(ShopSchema.exists(metaData, schema, "INSERT INTO customers VALUES (1)")).isFalse();
    }
  }

  @Test
  @DisplayName("SAMPLE_DB accepts the nine databases of 18 §6.3 and picks a ping per product name")
  void nineDatabases() {
    for (String id :
        new String[] {
          "h2",
          "postgres",
          "mysql",
          "mariadb",
          "mssql",
          "oracle",
          "tibero",
          "altibase",
          "cubrid",
          "db2"
        }) {
      assertThat(DbKind.parse(id).id()).isEqualTo(id);
    }
    assertThat(DbKind.fromProductName("Tibero").pingSql()).isEqualTo("SELECT 1 FROM DUAL");
    assertThat(DbKind.fromProductName("DB2/LINUXX8664").pingSql())
        .isEqualTo("SELECT 1 FROM SYSIBM.SYSDUMMY1");
    assertThat(DbKind.fromProductName("Oracle").pingSql()).isEqualTo("SELECT 1 FROM DUAL");
    assertThat(DbKind.fromProductName("PostgreSQL").pingSql()).isEqualTo("SELECT 1");
    assertThat(DbKind.TIBERO.offsetFirst()).isTrue();
    assertThat(DbKind.CUBRID.pageClause()).isEqualTo(" LIMIT ? OFFSET ?");
    assertThat(DbKind.DB2.hibernateDialect()).isEqualTo("org.hibernate.dialect.DB2Dialect");
    assertThat(DbKind.H2.hibernateDialect()).isNull();
  }
}
