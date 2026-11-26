/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.jdbc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * The databases of the WO-13 JDBC suite (18 §6.3, ADR-0010 C-149) and how each is reached:
 *
 * <ul>
 *   <li>{@link #H2}: the sample apps' in-memory default, a real JDBC driver in every run (C-149
 *       (1)).
 *   <li>{@link #MYSQL}, {@link #MARIADB}, {@link #POSTGRES}, {@link #MSSQL}: Testcontainers {@code
 *       GenericContainer}s, images pinned by digest, only with {@code -Pagent.jdbc.containers=true}
 *       (or a comma list of these ids), C-149 (2).
 *   <li>{@link #ORACLE}: {@code gvenzl/oracle-free} pinned by digest, only with {@code
 *       -Pagent.jdbc.oracle=true} (C-149 (3)), or the in-house Oracle 19c of {@code
 *       ARGOS_TEST_ORACLE_URL/USER/PASSWORD} (human check, C-149 (4)).
 *   <li>{@link #TIBERO}, {@link #ALTIBASE}, {@link #CUBRID}, {@link #DB2} (WO-50, the nine
 *       databases of 18 §6.3): in-house databases of a trusted host ({@code ARGOS_TEST_<DB>_URL,
 *       _USER, _PASSWORD}) with the vendor driver jar from {@value #DRIVER_DIR}; only {@link
 *       VendorDatabaseTest} reaches them (human check), {@link JdbcStepsTest} lists them as
 *       skipped.
 * </ul>
 *
 * Containers run one at a time, each with a memory limit at or under 5 GiB, a name {@code
 * argos-wo13-jdbc-<db>-<n>} and the label {@code argos.wo=WO-13}; passwords are random per run and
 * never printed (the sample app gets them as {@code SAMPLE_DB_PASSWORD}, which the launcher never
 * logs). The JDBC URLs carry no credentials.
 */
enum JdbcDatabase {
  H2("h2", null, 0, 0, null),
  MYSQL(
      "mysql",
      // mysql:8.4 (MySQL Community Server 8.4.13), linux/amd64 index digest of 2026-11-08.
      "mysql:8.4@sha256:a81d043930e51745522bd5c430961cf8286f0a23f0a9a2a6e0108f7ea71b0902",
      3306,
      1024,
      ".*ready for connections.*port: 3306 .*"),
  MARIADB(
      "mariadb",
      // mariadb:11.4 (MariaDB 11.4.13).
      "mariadb:11.4@sha256:d1891ede5a559f1086f55053bb8cc1569b0298338c4d72d71725a7f74f6ba620",
      3306,
      1024,
      // The real server's line (the init server listens on port 0, MariaDB prints it apart).
      ".*port: 3306 .*"),
  POSTGRES(
      "postgres",
      // The server's pinned PostgreSQL 16 (deploy/postgres/image.properties).
      "postgres:16.15@sha256:5db6dac2ef5dcff512f4f00a009c3c332b9f2f28f24277175bf3e4a3d02ef83a",
      5432,
      1024,
      ".*database system is ready to accept connections.*"),
  MSSQL(
      "mssql",
      // mcr.microsoft.com/mssql/server:2022-latest, Developer edition (Microsoft EULA,
      // dev-only.md).
      "mcr.microsoft.com/mssql/server:2022-latest"
          + "@sha256:cf8f0ab2ebe61feec2eed2629061a07d247f791c2a8ddda74a4ac9f06a291718",
      1433,
      4096,
      ".*SQL Server is now ready for client connections.*"),
  ORACLE(
      "oracle",
      // gvenzl/oracle-free:23-slim-faststart (Oracle Database Free, FUTC, dev-only.md).
      "gvenzl/oracle-free:23-slim-faststart"
          + "@sha256:f5ff19033860d662c821cb04eb10483fa94f14f78eae252d054291ea07028093",
      1521,
      4096,
      ".*DATABASE IS READY TO USE!.*"),
  /** In-house TmaxTibero of a trusted host ({@link VendorDatabaseTest}, human check). */
  TIBERO("tibero", null, 0, 0, null),
  /** In-house Altibase of a trusted host ({@link VendorDatabaseTest}, human check). */
  ALTIBASE("altibase", null, 0, 0, null),
  /** In-house CUBRID of a trusted host ({@link VendorDatabaseTest}, human check). */
  CUBRID("cubrid", null, 0, 0, null),
  /** In-house IBM Db2 of a trusted host ({@link VendorDatabaseTest}, human check). */
  DB2("db2", null, 0, 0, null);

  /**
   * The vendor databases without a container image whose driver jar comes from {@value #DRIVER_DIR}
   * (WO-50, 18 §6.3 nine databases): reached only by {@link VendorDatabaseTest}.
   */
  static final List<JdbcDatabase> VENDORS = List.of(TIBERO, ALTIBASE, CUBRID, DB2);

  /**
   * The trusted runner's folder of vendor driver jars (never committed, dev-only.md condition 4).
   */
  static final String DRIVER_DIR = "ARGOS_TEST_JDBC_DRIVER_DIR";

  /** The database containers of {@code -Pagent.jdbc.containers=true}. */
  static final List<JdbcDatabase> CONTAINERS = List.of(MYSQL, MARIADB, POSTGRES, MSSQL);

  /** Property of {@code -Pagent.jdbc.containers} ({@code true} or a comma list of ids). */
  static final String CONTAINERS_PROPERTY = "argos.test.jdbc.containers";

  /** Property of {@code -Pagent.jdbc.oracle=true}. */
  static final String ORACLE_PROPERTY = "argos.test.jdbc.oracle";

  /** The in-house Oracle 19c of a trusted host (human check, C-149 (4)). */
  static final String IN_HOUSE_ORACLE = "ARGOS_TEST_ORACLE_URL";

  private static final SecureRandom RANDOM = new SecureRandom();
  private static int started;

  private static synchronized int nextStart() {
    return ++started;
  }

  final String id;
  final String image;
  final int port;
  final int memoryMb;
  final String readyLog;

  JdbcDatabase(String id, String image, int port, int memoryMb, String readyLog) {
    this.id = id;
    this.image = image;
    this.port = port;
    this.memoryMb = memoryMb;
    this.readyLog = readyLog;
  }

  /**
   * The databases of this run in suite order: H2, then the enabled containers, then Oracle
   * (container or in-house).
   */
  static List<JdbcDatabase> inRun() {
    List<JdbcDatabase> databases = new ArrayList<>();
    databases.add(H2);
    for (JdbcDatabase db : CONTAINERS) {
      if (db.enabled()) {
        databases.add(db);
      }
    }
    if (ORACLE.enabled()) {
      databases.add(ORACLE);
    }
    return databases;
  }

  /** Whether this run reaches the database (see {@link #skipReason()}). */
  boolean enabled() {
    return skipReason().isEmpty();
  }

  /** Why this run does not reach the database, empty when it does. */
  Optional<String> skipReason() {
    if (this == H2) {
      return Optional.empty();
    }
    if (VENDORS.contains(this)) {
      return Optional.of(
          id
              + ": an in-house database with a vendor driver jar, run by VendorDatabaseTest when "
              + vendorVariable("URL")
              + " and "
              + DRIVER_DIR
              + " are set (ADR-0010 C-149 (4), human check)");
    }
    if (this == ORACLE) {
      if (Boolean.getBoolean(ORACLE_PROPERTY) || inHouseOracle()) {
        return Optional.empty();
      }
      return Optional.of(
          "Oracle: neither -Pagent.jdbc.oracle=true (gvenzl/oracle-free container) nor "
              + IN_HOUSE_ORACLE
              + " (in-house Oracle 19c, human check) is set (ADR-0010 C-149)");
    }
    String flag = System.getProperty(CONTAINERS_PROPERTY, "false").trim().toLowerCase(Locale.ROOT);
    if (flag.equals("true") || List.of(flag.split(",", -1)).contains(id)) {
      return Optional.empty();
    }
    return Optional.of(
        id
            + ": DB containers run with -Pagent.jdbc.containers=true (or a list such as"
            + " -Pagent.jdbc.containers=postgres,mysql), nightly or by hand (ADR-0010 C-149 (2))");
  }

  /** {@code ARGOS_TEST_<DB>_<suffix>} of this database (URL, USER, PASSWORD). */
  String vendorVariable(String suffix) {
    return "ARGOS_TEST_" + id.toUpperCase(Locale.ROOT) + "_" + suffix;
  }

  /** The in-house database of a trusted host ({@link #vendorVariable} URL, USER, PASSWORD). */
  Server inHouse() {
    return new Server(
        this,
        null,
        "in-house",
        0,
        System.getenv(vendorVariable("URL")),
        System.getenv(vendorVariable("USER")),
        System.getenv(vendorVariable("PASSWORD")),
        true,
        Duration.ZERO);
  }

  /** Whether the Oracle runs use the in-house database instead of the container. */
  static boolean inHouseOracle() {
    String url = System.getenv(IN_HOUSE_ORACLE);
    return url != null && !url.isBlank() && !Boolean.getBoolean(ORACLE_PROPERTY);
  }

  /**
   * The {@code DB_TARGET} text the agent sends for this database ({@code <vendor>://<host>:<port>/
   * <database>}, 10 §6.3; H2 in memory: {@code h2:mem:<name>}).
   */
  String expectedTarget(Server server, String appName) {
    String hostPort = server.host + ":" + server.mappedPort;
    return switch (this) {
      case H2 -> "h2:mem:" + appName;
      case MYSQL -> "mysql://" + hostPort + "/shop";
      case MARIADB -> "mariadb://" + hostPort + "/shop";
      case POSTGRES -> "postgresql://" + hostPort + "/shop";
      case MSSQL -> "sqlserver://" + hostPort + "/shop";
      case ORACLE -> server.inHouse ? null : "oracle://" + hostPort + "/FREEPDB1";
      // In-house: host and database of the runner's URL, checked only for shape (no '@', no ';').
      case TIBERO, ALTIBASE, CUBRID, DB2 -> null;
    };
  }

  /** Starts the database (nothing for H2); {@link Server#close()} stops it. */
  Server start() throws Exception {
    if (this == H2) {
      return new Server(this, null, null, 0, null, null, null, false, Duration.ZERO);
    }
    if ((this == ORACLE && inHouseOracle()) || VENDORS.contains(this)) {
      return inHouse();
    }
    String password = password();
    String name = "argos-wo13-jdbc-" + id + "-" + nextStart() + "-" + ProcessHandle.current().pid();
    GenericContainer<?> container =
        new GenericContainer<>(DockerImageName.parse(image))
            .withExposedPorts(port)
            .withLabel("argos.wo", "WO-13")
            .withCreateContainerCmdModifier(
                cmd -> {
                  cmd.withName(name);
                  var host = cmd.getHostConfig();
                  if (host != null) {
                    host.withMemory(memoryMb * 1024L * 1024L);
                    host.withMemorySwap(memoryMb * 1024L * 1024L);
                  }
                })
            .waitingFor(
                // PostgreSQL logs the line for its init server first, then for the real one.
                Wait.forLogMessage(readyLog, this == POSTGRES ? 2 : 1)
                    .withStartupTimeout(Duration.ofMinutes(this == ORACLE ? 8 : 4)));
    String user = "shop";
    switch (this) {
      case MYSQL ->
          container
              .withEnv("MYSQL_ROOT_PASSWORD", password())
              .withEnv("MYSQL_DATABASE", "shop")
              .withEnv("MYSQL_USER", user)
              .withEnv("MYSQL_PASSWORD", password);
      case MARIADB ->
          container
              .withEnv("MARIADB_ROOT_PASSWORD", password())
              .withEnv("MARIADB_DATABASE", "shop")
              .withEnv("MARIADB_USER", user)
              .withEnv("MARIADB_PASSWORD", password);
      case POSTGRES ->
          container
              .withEnv("POSTGRES_DB", "shop")
              .withEnv("POSTGRES_USER", user)
              .withEnv("POSTGRES_PASSWORD", password);
      case MSSQL -> {
        user = "sa";
        container
            .withEnv("ACCEPT_EULA", "Y")
            .withEnv("MSSQL_PID", "Developer")
            .withEnv("MSSQL_SA_PASSWORD", password)
            .withEnv("MSSQL_MEMORY_LIMIT_MB", Integer.toString(memoryMb / 2));
      }
      case ORACLE ->
          container
              .withEnv("ORACLE_PASSWORD", password())
              .withEnv("APP_USER", user)
              .withEnv("APP_USER_PASSWORD", password);
      default -> throw new AssertionError(this);
    }
    long begin = System.nanoTime();
    container.start();
    if (this == MSSQL) {
      createMssqlDatabase(container);
    }
    Duration startup = Duration.ofNanos(System.nanoTime() - begin);
    String host = "127.0.0.1";
    int mapped = container.getMappedPort(port);
    return new Server(
        this, container, host, mapped, url(host, mapped), user, password, false, startup);
  }

  /**
   * SQL Server has no database-on-start variable: {@code CREATE DATABASE shop} with the image's
   * sqlcmd, which reads the password from the container's own {@code MSSQL_SA_PASSWORD} (never on a
   * command line). Retried for 60 s: the server logs "ready" before the system databases are
   * recovered.
   */
  private static void createMssqlDatabase(GenericContainer<?> container) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
    while (true) {
      Container.ExecResult result =
          container.execInContainer(
              "/bin/bash",
              "-c",
              "SQLCMDPASSWORD=\"$MSSQL_SA_PASSWORD\" exec /opt/mssql-tools18/bin/sqlcmd -C -S"
                  + " localhost -U sa -b -Q \"IF DB_ID(N'shop') IS NULL CREATE DATABASE shop\"");
      if (result.getExitCode() == 0) {
        return;
      }
      if (System.nanoTime() > deadline) {
        throw new IllegalStateException(
            "CREATE DATABASE shop failed (exit "
                + result.getExitCode()
                + "): "
                + result.getStdout());
      }
      Thread.sleep(2000);
    }
  }

  /** The JDBC URL of the database on {@code host:port} (no credentials). */
  String url(String host, int mappedPort) {
    String hostPort = host + ":" + mappedPort;
    return switch (this) {
      case MYSQL -> "jdbc:mysql://" + hostPort + "/shop?allowPublicKeyRetrieval=true";
      case MARIADB -> "jdbc:mariadb://" + hostPort + "/shop";
      case POSTGRES -> "jdbc:postgresql://" + hostPort + "/shop";
      case MSSQL ->
          "jdbc:sqlserver://"
              + hostPort
              + ";databaseName=shop;encrypt=true;trustServerCertificate=true";
      case ORACLE -> "jdbc:oracle:thin:@//" + hostPort + "/FREEPDB1";
      default -> throw new AssertionError(this);
    };
  }

  /** A random password of 24 letters and digits plus the classes SQL Server's policy wants. */
  private static String password() {
    String alphabet = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    StringBuilder out = new StringBuilder("Ar9");
    for (int i = 0; i < 24; i++) {
      out.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
    }
    return out.toString();
  }

  /** A started database: what the sample app is given, and the container to stop. */
  static final class Server implements AutoCloseable {
    final JdbcDatabase db;
    final GenericContainer<?> container;
    final String host;
    final int mappedPort;
    final String url;
    final String user;
    final String password;
    final boolean inHouse;
    final Duration startup;

    Server(
        JdbcDatabase db,
        GenericContainer<?> container,
        String host,
        int mappedPort,
        String url,
        String user,
        String password,
        boolean inHouse,
        Duration startup) {
      this.db = db;
      this.container = container;
      this.host = host;
      this.mappedPort = mappedPort;
      this.url = url;
      this.user = user;
      this.password = password;
      this.inHouse = inHouse;
      this.startup = startup;
    }

    /** The memory the container uses now ({@code docker stats}), or "n/a". */
    String memoryUsage() {
      if (container == null || !container.isRunning()) {
        return "n/a";
      }
      try {
        Process process =
            new ProcessBuilder(
                    "docker",
                    "stats",
                    "--no-stream",
                    "--format",
                    "{{.MemUsage}}",
                    container.getContainerId())
                .redirectErrorStream(true)
                .start();
        String out =
            new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        process.waitFor();
        return out.isEmpty() ? "n/a" : out;
      } catch (IOException | RuntimeException e) {
        return "n/a (" + e.getClass().getSimpleName() + ")";
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return "n/a (interrupted)";
      }
    }

    @Override
    public void close() {
      if (container != null) {
        container.stop();
      }
    }

    @Override
    public String toString() {
      return db.id
          + (container == null ? "" : " " + container.getDockerImageName())
          + (url == null ? "" : " at " + host + ":" + mappedPort);
    }
  }
}
