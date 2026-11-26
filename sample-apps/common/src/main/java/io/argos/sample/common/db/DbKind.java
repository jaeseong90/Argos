/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.common.db;

import java.util.Locale;

/**
 * Databases selected with SAMPLE_DB (18 §4.1, §6.3 JDBC matrix): H2, PostgreSQL, MySQL, MariaDB,
 * Microsoft SQL Server and Oracle, each with its driver class, default account and URL, schema
 * script and the few SQL dialect differences of {@link ShopJdbc} (sleep, ping, paging, generated
 * keys).
 *
 * <p>The vendor databases without a container image (WO-50, the nine databases of 18 §6.3): Tibero,
 * Altibase, CUBRID and DB2. Their drivers are not redistributable or not in the build; they come
 * from {@code ARGOS_TEST_JDBC_DRIVER_DIR} ({@link VendorDriver}). Only trusted self-hosted runners
 * reach these databases (ADR-0010 C-149 (4)); their dialect values below are a first version that
 * no run has checked yet (human check), and their schema scripts create plain objects that {@link
 * ShopSchema} skips when the metadata already lists them.
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
  ORACLE("oracle", "oracle.jdbc.OracleDriver", "shop"),
  /** TmaxTibero (6 and later), vendor driver from ARGOS_TEST_JDBC_DRIVER_DIR. */
  TIBERO("tibero", "com.tmax.tibero.jdbc.TbDriver", "shop"),
  /** Altibase (7 and later), vendor driver from ARGOS_TEST_JDBC_DRIVER_DIR. */
  ALTIBASE("altibase", "Altibase.jdbc.driver.AltibaseDriver", "shop"),
  /** CUBRID (10 and later), driver from ARGOS_TEST_JDBC_DRIVER_DIR. */
  CUBRID("cubrid", "cubrid.jdbc.driver.CUBRIDDriver", "shop"),
  /**
   * IBM Db2 for Linux, UNIX and Windows (11.1 and later), driver from ARGOS_TEST_JDBC_DRIVER_DIR.
   */
  DB2("db2", "com.ibm.db2.jcc.DB2Driver", "shop");

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
        "SAMPLE_DB must be one of h2, postgres, mysql, mariadb, mssql, oracle, tibero, altibase,"
            + " cubrid, db2, got '"
            + value
            + "'");
  }

  /**
   * The database of a JDBC {@code DatabaseMetaData.getDatabaseProductName()} (e.g. "Oracle",
   * "Tibero", "DB2/LINUXX8664"), or H2 when no known name matches.
   */
  public static DbKind fromProductName(String product) {
    String lower = product == null ? "" : product.toLowerCase(Locale.ROOT);
    if (lower.contains("oracle")) {
      return ORACLE;
    }
    if (lower.contains("tibero")) {
      return TIBERO;
    }
    if (lower.contains("altibase")) {
      return ALTIBASE;
    }
    if (lower.contains("db2")) {
      return DB2;
    }
    if (lower.contains("cubrid")) {
      return CUBRID;
    }
    return H2;
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

  /**
   * Whether the driver is a vendor jar from ARGOS_TEST_JDBC_DRIVER_DIR (Tibero, Altibase, CUBRID,
   * DB2) instead of an artifact of the app's build.
   */
  public boolean vendorDriver() {
    return this == TIBERO || this == ALTIBASE || this == CUBRID || this == DB2;
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
      case TIBERO:
        return "jdbc:tibero:thin:@127.0.0.1:8629:shop";
      case ALTIBASE:
        return "jdbc:Altibase://127.0.0.1:20300/shop";
      case CUBRID:
        return "jdbc:cubrid:127.0.0.1:33000:shop:::";
      case DB2:
        return "jdbc:db2://127.0.0.1:50000/shop";
    }
    throw new AssertionError(this);
  }

  /** Classpath resource of the schema script. */
  public String schemaResource() {
    return "io/argos/sample/common/db/schema-" + id + ".sql";
  }

  /**
   * Whether the schema script has plain {@code CREATE TABLE} / {@code CREATE INDEX} statements that
   * {@link ShopSchema} runs only for objects the database metadata does not list yet (the vendor
   * databases: no {@code IF NOT EXISTS} and no shared error code to ignore).
   */
  public boolean createsMissingObjectsOnly() {
    return vendorDriver();
  }

  /**
   * SQL that sleeps in the database for the bound number of milliseconds (GET /api/slow?sql=1):
   * PostgreSQL pg_sleep, MySQL and MariaDB SLEEP, SQL Server WAITFOR DELAY, an Oracle PL/SQL block
   * with DBMS_SESSION.SLEEP (18c and later), Tibero DBMS_LOCK.SLEEP, Altibase SLEEP (whole seconds,
   * rounded up), CUBRID SLEEP, Db2 DBMS_LOCK.SLEEP (whole seconds, rounded up), H2 a Java function
   * alias (see {@link H2Functions}).
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
      case TIBERO:
        return "BEGIN DBMS_LOCK.SLEEP(? / 1000); END;";
      case ALTIBASE:
        return "SELECT SLEEP(CEIL(? / 1000)) FROM DUAL";
      case CUBRID:
        return "SELECT SLEEP(? / 1000.0)";
      case DB2:
        return "CALL DBMS_LOCK.SLEEP(CEILING(? / 1000.0))";
    }
    throw new AssertionError(this);
  }

  /**
   * The cheapest statement that returns one row ({@code SELECT 1}; Oracle, Tibero and Altibase need
   * DUAL, Db2 SYSIBM.SYSDUMMY1).
   */
  public String pingSql() {
    switch (this) {
      case ORACLE:
      case TIBERO:
      case ALTIBASE:
        return "SELECT 1 FROM DUAL";
      case DB2:
        return "SELECT 1 FROM SYSIBM.SYSDUMMY1";
      default:
        return "SELECT 1";
    }
  }

  /**
   * The paging clause after {@code ORDER BY}: {@code LIMIT ? OFFSET ?}, or the SQL:2008 {@code
   * OFFSET ? ROWS FETCH NEXT ? ROWS ONLY} on SQL Server, Oracle, Tibero and Db2 (see {@link
   * #offsetFirst()}).
   */
  public String pageClause() {
    return offsetFirst() ? " OFFSET ? ROWS FETCH NEXT ? ROWS ONLY" : " LIMIT ? OFFSET ?";
  }

  /** Whether the first parameter of {@link #pageClause()} is the offset (else the limit). */
  public boolean offsetFirst() {
    return this == MSSQL || this == ORACLE || this == TIBERO || this == DB2;
  }

  /**
   * Column names to ask for generated keys ({@code prepareStatement(sql, columnNames)}), or null
   * for {@code Statement.RETURN_GENERATED_KEYS}: Oracle and Tibero return the ROWID for the latter.
   */
  public String[] generatedKeyColumns() {
    return this == ORACLE || this == TIBERO || this == ALTIBASE ? new String[] {"ID"} : null;
  }

  /**
   * The audit row insert: the identity column fills {@code id}, except on Altibase (no identity
   * column) and Tibero (identity columns only in recent versions), which take the next value of the
   * sequence {@code audit_log_seq} of their schema script.
   */
  public String auditInsertSql() {
    return this == ALTIBASE || this == TIBERO
        ? "INSERT INTO audit_log (id, message, created_at) VALUES (audit_log_seq.NEXTVAL, ?, ?)"
        : "INSERT INTO audit_log (message, created_at) VALUES (?, ?)";
  }

  /**
   * The Hibernate dialect the shop apps' JPA layer is given (shop-boot3), or null to let Hibernate
   * detect it from the connection: Tibero speaks Oracle's SQL, CUBRID close to MySQL's, Altibase
   * close to Oracle's (no dialect of their own in Hibernate's core).
   */
  public String hibernateDialect() {
    switch (this) {
      case TIBERO:
      case ALTIBASE:
        return "org.hibernate.dialect.OracleDialect";
      case CUBRID:
        return "org.hibernate.dialect.MySQLDialect";
      case DB2:
        return "org.hibernate.dialect.DB2Dialect";
      default:
        return null;
    }
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
