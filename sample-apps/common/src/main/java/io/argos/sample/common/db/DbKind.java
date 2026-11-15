/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.common.db;

import java.util.Locale;

/**
 * Databases selected with SAMPLE_DB (18 §4.1, §6.3 JDBC matrix): H2, PostgreSQL, MySQL, MariaDB,
 * Microsoft SQL Server and Oracle, each with its driver class, default account and URL, schema
 * script and the few SQL dialect differences of {@link ShopJdbc} (sleep, ping, paging, generated
 * keys). The vendor databases without containers (Tibero, Altibase, CUBRID, DB2) are WO-50's.
 */
public enum DbKind {
  /** In-memory H2 (default): no container needed. */
  H2("h2", "org.h2.Driver", "sa"),
  /** PostgreSQL (Testcontainers or a local server). */
  POSTGRES("postgres", "org.postgresql.Driver", "shop"),
  /** MySQL 8 (Testcontainers or a local server). */
  MYSQL("mysql", "com.mysql.cj.jdbc.Driver", "shop"),
  /** MariaDB 11 with MariaDB Connector/J (Testcontainers or a local server). */
  MARIADB("mariadb", "org.mariadb.jdbc.Driver", "shop"),
  /** Microsoft SQL Server 2022 with mssql-jdbc (Testcontainers or a local server). */
  MSSQL("mssql", "com.microsoft.sqlserver.jdbc.SQLServerDriver", "sa"),
  /** Oracle Database (19c and later; Oracle Database Free in containers) with ojdbc. */
  ORACLE("oracle", "oracle.jdbc.OracleDriver", "shop");

  private final String id;
  private final String driverClass;
  private final String defaultUser;

  DbKind(String id, String driverClass, String defaultUser) {
    this.id = id;
    this.driverClass = driverClass;
    this.defaultUser = defaultUser;
  }

  /**
   * Parses SAMPLE_DB; "postgresql" is accepted as an alias of "postgres", "sqlserver" as an alias
   * of "mssql".
   */
  public static DbKind parse(String value) {
    String normalized = value.trim().toLowerCase(Locale.ROOT);
    if (normalized.equals("postgresql")) {
      return POSTGRES;
    }
    if (normalized.equals("sqlserver")) {
      return MSSQL;
    }
    for (DbKind kind : values()) {
      if (kind.id.equals(normalized)) {
        return kind;
      }
    }
    throw new IllegalArgumentException(
        "SAMPLE_DB must be one of h2, postgres, mysql, mariadb, mssql, oracle, got '"
            + value
            + "'");
  }

  /** The SAMPLE_DB value. */
  public String id() {
    return id;
  }

  public String driverClass() {
    return driverClass;
  }

  public String defaultUser() {
    return defaultUser;
  }

  /** Default JDBC URL; the H2 database name is the app name (one in-memory database per app). */
  public String defaultUrl(String appName) {
    switch (this) {
      case H2:
        return "jdbc:h2:mem:" + appName.replaceAll("[^A-Za-z0-9_-]", "_") + ";DB_CLOSE_DELAY=-1";
      case POSTGRES:
        return "jdbc:postgresql://127.0.0.1:5432/shop";
      case MYSQL:
        return "jdbc:mysql://127.0.0.1:3306/shop";
      case MARIADB:
        return "jdbc:mariadb://127.0.0.1:3306/shop";
      case MSSQL:
        return "jdbc:sqlserver://127.0.0.1:1433;databaseName=shop;encrypt=false";
      case ORACLE:
        return "jdbc:oracle:thin:@//127.0.0.1:1521/FREEPDB1";
    }
    throw new AssertionError(this);
  }

  /** Classpath resource of the schema script. */
  public String schemaResource() {
    return "io/argos/sample/common/db/schema-" + id + ".sql";
  }

  /**
   * SQL that sleeps in the database for the bound number of milliseconds (GET /api/slow?sql=1):
   * PostgreSQL pg_sleep, MySQL and MariaDB SLEEP, SQL Server WAITFOR DELAY, an Oracle PL/SQL block
   * with DBMS_SESSION.SLEEP (18c and later), H2 a Java function alias (see {@link H2Functions}).
   */
  public String sleepSql() {
    switch (this) {
      case H2:
        return "CALL ARGOS_SLEEP_MS(?)";
      case POSTGRES:
        return "SELECT pg_sleep(? / 1000.0)";
      case MYSQL:
      case MARIADB:
        return "SELECT SLEEP(? / 1000.0)";
      case MSSQL:
        return "DECLARE @d DATETIME = DATEADD(MILLISECOND, CAST(? AS INT),"
            + " CAST('00:00:00' AS DATETIME)); WAITFOR DELAY @d";
      case ORACLE:
        return "BEGIN DBMS_SESSION.SLEEP(? / 1000); END;";
    }
    throw new AssertionError(this);
  }

  /** The cheapest statement that returns one row ({@code SELECT 1}; Oracle needs DUAL). */
  public String pingSql() {
    return this == ORACLE ? "SELECT 1 FROM DUAL" : "SELECT 1";
  }

  /**
   * The paging clause after {@code ORDER BY}: {@code LIMIT ? OFFSET ?}, or the SQL:2008 {@code
   * OFFSET ? ROWS FETCH NEXT ? ROWS ONLY} on SQL Server and Oracle (see {@link #offsetFirst()}).
   */
  public String pageClause() {
    return offsetFirst() ? " OFFSET ? ROWS FETCH NEXT ? ROWS ONLY" : " LIMIT ? OFFSET ?";
  }

  /** Whether the first parameter of {@link #pageClause()} is the offset (else the limit). */
  public boolean offsetFirst() {
    return this == MSSQL || this == ORACLE;
  }

  /**
   * Column names to ask for generated keys ({@code prepareStatement(sql, columnNames)}), or null
   * for {@code Statement.RETURN_GENERATED_KEYS}: Oracle returns the ROWID for the latter.
   */
  public String[] generatedKeyColumns() {
    return this == ORACLE ? new String[] {"ID"} : null;
  }

  /** Whether {@code $$...$$} dollar-quoted string literals are valid SQL (18 §4.3 seed). */
  public boolean supportsDollarQuotes() {
    return this == H2 || this == POSTGRES;
  }

  /** Whether a backslash in a string literal is an escape character (MySQL, MariaDB defaults). */
  public boolean backslashEscapes() {
    return this == MYSQL || this == MARIADB;
  }
}
