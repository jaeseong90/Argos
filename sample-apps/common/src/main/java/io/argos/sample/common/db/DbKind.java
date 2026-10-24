/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.common.db;

import java.util.Locale;

/**
 * Databases selected with SAMPLE_DB (18 §4.1). Further values (mariadb, mssql, oracle and the
 * vendor databases of the JDBC matrix, 18 §6.3) are added with their schema script by WO-13/WO-50.
 */
public enum DbKind {
  /** In-memory H2 (default): no container needed. */
  H2("h2", "org.h2.Driver", "sa"),
  /** PostgreSQL (Testcontainers or a local server). */
  POSTGRES("postgres", "org.postgresql.Driver", "shop"),
  /** MySQL 8 (Testcontainers or a local server). */
  MYSQL("mysql", "com.mysql.cj.jdbc.Driver", "shop");

  private final String id;
  private final String driverClass;
  private final String defaultUser;

  DbKind(String id, String driverClass, String defaultUser) {
    this.id = id;
    this.driverClass = driverClass;
    this.defaultUser = defaultUser;
  }

  /** Parses SAMPLE_DB; "postgresql" is accepted as an alias of "postgres". */
  public static DbKind parse(String value) {
    String normalized = value.trim().toLowerCase(Locale.ROOT);
    if (normalized.equals("postgresql")) {
      return POSTGRES;
    }
    for (DbKind kind : values()) {
      if (kind.id.equals(normalized)) {
        return kind;
      }
    }
    throw new IllegalArgumentException(
        "SAMPLE_DB must be one of h2, postgres, mysql, got '" + value + "'");
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
    }
    throw new AssertionError(this);
  }

  /** Classpath resource of the schema script. */
  public String schemaResource() {
    return "io/argos/sample/common/db/schema-" + id + ".sql";
  }

  /**
   * SQL that sleeps in the database for the bound number of milliseconds (GET /api/slow?sql=1):
   * PostgreSQL pg_sleep, MySQL SLEEP, H2 a Java function alias (see {@link H2Functions}).
   */
  public String sleepSql() {
    switch (this) {
      case H2:
        return "CALL ARGOS_SLEEP_MS(?)";
      case POSTGRES:
        return "SELECT pg_sleep(? / 1000.0)";
      case MYSQL:
        return "SELECT SLEEP(? / 1000.0)";
    }
    throw new AssertionError(this);
  }

  /** Whether {@code $$...$$} dollar-quoted string literals are valid SQL (18 §4.3 seed). */
  public boolean supportsDollarQuotes() {
    return this != MYSQL;
  }

  /** Whether a backslash in a string literal is an escape character (MySQL default mode). */
  public boolean backslashEscapes() {
    return this == MYSQL;
  }
}
