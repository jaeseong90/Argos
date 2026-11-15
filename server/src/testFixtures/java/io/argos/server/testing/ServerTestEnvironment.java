/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.testing;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import io.argos.storage.clickhouse.Account;
import io.argos.storage.clickhouse.ArgosClickHouse;
import io.argos.storage.clickhouse.ClickHouseHttp;
import io.argos.storage.clickhouse.MemoryBudget;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * PostgreSQL 16 and ClickHouse for server integration tests, configured like an installation
 * (WO-20; reused by WO-21, WO-23 and WO-24).
 *
 * <ul>
 *   <li>PostgreSQL: the pinned image of {@code deploy/postgres/image.properties} with its bootstrap
 *       superuser {@code postgres}, and the reference role SQL {@code deploy/postgres/roles.sql}
 *       run with psql as the installer does: the non-superuser owner {@code argos} (owns the
 *       database {@code argos} and the schema {@code argos}; runs the migrations, C-11) and the
 *       login roles {@code argos_app}, {@code argos_audit}, {@code argos_backup}.
 *   <li>ClickHouse: {@link ArgosClickHouse} (pinned image, {@code deploy/clickhouse} config.d and
 *       users.d, {@code default} user removed); the schema is not applied (the server's migration
 *       runner does that).
 *   <li>Every password is generated at run time (32 random bytes, base64url) and written to a
 *       password file in a private temporary directory; {@link #springProperties()} points the
 *       server at those files, never at plain values.
 *   <li>Ports are published on 127.0.0.1 only when the Docker daemon is local (SEC-75), and every
 *       container start holds the repository-wide Docker start lock ({@link DockerStartLock}).
 * </ul>
 *
 * <pre>{@code
 * try (ServerTestEnvironment env = ServerTestEnvironment.start()) {
 *   SpringApplication app = new SpringApplication(ServerApplication.class);
 *   app.setDefaultProperties(env.springProperties());
 *   try (ConfigurableApplicationContext ctx = app.run()) { ... }
 *   try (Connection c = env.connect(PgAccount.APP)) { ... }
 *   env.clickHouse(Account.READER).scalar("SELECT 1");
 * }
 * }</pre>
 */
public final class ServerTestEnvironment implements AutoCloseable {

  /** Database name and ClickHouse database ({@code {db}}). */
  public static final String DATABASE = "argos";

  /** Label on the containers started here (besides the Testcontainers labels). */
  public static final String CONTAINER_LABEL = "io.argos.test";

  private static final System.Logger LOG = System.getLogger(ServerTestEnvironment.class.getName());
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final String ROLES_IN_CONTAINER = "/tmp/argos-roles.sql";

  /** Bootstrap superuser of the PostgreSQL image when the roles are created (installer layout). */
  static final String SUPERUSER = "postgres";

  /** PostgreSQL accounts of C-11 / ADR-0010 C-97. */
  public enum PgAccount {
    /**
     * Schema owner: runs Flyway and owns every table (created by roles.sql; without roles the
     * image's superuser).
     */
    OWNER("argos"),
    /** Server runtime: DML, {@code audit_logs} INSERT and SELECT only. */
    APP("argos_app"),
    /** Audit partition retention job. */
    AUDIT("argos_audit"),
    /** Read-only dump. */
    BACKUP("argos_backup");

    private final String user;

    PgAccount(String user) {
      this.user = user;
    }

    /** Role name. */
    public String user() {
      return user;
    }
  }

  private final PostgreSQLContainer postgres;
  private final ArgosClickHouse clickHouse;
  private final Map<PgAccount, String> pgPasswords;
  private final Map<Account, String> chPasswords;
  private final Path secretsDir;
  private final Map<String, String> springProperties;

  private ServerTestEnvironment(
      PostgreSQLContainer postgres,
      ArgosClickHouse clickHouse,
      Map<PgAccount, String> pgPasswords,
      Map<Account, String> chPasswords,
      Path secretsDir) {
    this.postgres = postgres;
    this.clickHouse = clickHouse;
    this.pgPasswords = pgPasswords;
    this.chPasswords = chPasswords;
    this.secretsDir = secretsDir;
    this.springProperties = Collections.unmodifiableMap(buildSpringProperties());
  }

  /** Starts both databases with the defaults of {@link Builder}. */
  public static ServerTestEnvironment start() {
    return builder().start();
  }

  /** A builder: ClickHouse 2 GiB, PostgreSQL 1 GiB, roles created. */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Spring properties of a server instance that uses these databases: the {@code argos.postgres.*}
   * and {@code argos.clickhouse.*} keys of ADR-0010 C-96, passwords as {@code password-file}.
   */
  public Map<String, String> springProperties() {
    return springProperties;
  }

  /** {@link #springProperties()} as {@code --key=value} command line arguments. */
  public List<String> commandLineArguments() {
    List<String> args = new ArrayList<>();
    springProperties.forEach((k, v) -> args.add("--" + k + "=" + v));
    return args;
  }

  /** JDBC URL of the {@code argos} database (no credentials). */
  public String postgresJdbcUrl() {
    return "jdbc:postgresql://"
        + postgres.getHost()
        + ":"
        + postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
        + "/"
        + DATABASE;
  }

  /** A new JDBC connection as {@code account}. */
  public Connection connect(PgAccount account) throws SQLException {
    return DriverManager.getConnection(postgresJdbcUrl(), account.user(), password(account));
  }

  /** The generated password of a PostgreSQL account (tests that need it directly). */
  public String password(PgAccount account) {
    return Objects.requireNonNull(pgPasswords.get(account), account.name());
  }

  /** HTTP endpoint of ClickHouse, without a trailing slash. */
  public String clickHouseUrl() {
    String uri = clickHouse.httpUri().toString();
    return uri.endsWith("/") ? uri.substring(0, uri.length() - 1) : uri;
  }

  /** A ClickHouse client of one of the three accounts. */
  public ClickHouseHttp clickHouse(Account account) {
    return clickHouse.client(account.user(), password(account));
  }

  /** The generated password of a ClickHouse account. */
  public String password(Account account) {
    return Objects.requireNonNull(chPasswords.get(account), account.name());
  }

  /** The ClickHouse server (logs, exec). */
  public ArgosClickHouse clickHouseServer() {
    return clickHouse;
  }

  /** The PostgreSQL container (logs, exec). */
  public PostgreSQLContainer postgresContainer() {
    return postgres;
  }

  /** Directory of the password files (deleted by {@link #close()}). */
  public Path secretsDirectory() {
    return secretsDir;
  }

  /** The password file of a PostgreSQL account. */
  public Path passwordFile(PgAccount account) {
    return secretsDir.resolve("pg-" + account.user() + ".password");
  }

  /** The password file of a ClickHouse account. */
  public Path passwordFile(Account account) {
    return secretsDir.resolve("ch-" + account.user() + ".password");
  }

  /** Stops both containers and deletes the password files. */
  @Override
  public void close() {
    try {
      clickHouse.close();
    } finally {
      try {
        postgres.stop();
      } finally {
        deleteRecursively(secretsDir);
      }
    }
  }

  private Map<String, String> buildSpringProperties() {
    Map<String, String> p = new LinkedHashMap<>();
    p.put("argos.postgres.url", postgresJdbcUrl());
    p.put("argos.postgres.user", PgAccount.APP.user());
    p.put("argos.postgres.password-file", path(passwordFile(PgAccount.APP)));
    p.put("argos.postgres.owner.user", PgAccount.OWNER.user());
    p.put("argos.postgres.owner.password-file", path(passwordFile(PgAccount.OWNER)));
    p.put("argos.clickhouse.url", clickHouseUrl());
    p.put("argos.clickhouse.database", DATABASE);
    for (Account account : Account.values()) {
      String key = "argos.clickhouse." + account.name().toLowerCase(Locale.ROOT);
      p.put(key + ".user", account.user());
      p.put(key + ".password-file", path(passwordFile(account)));
    }
    // WO-24: the JWT signing key and the setup token files of the server under test live in this
    // private directory too (deleted by close()), never in conf/ of the working directory, which
    // is the source tree (server/) under Gradle.
    p.put("argos.jwt-key-file", path(secretsDir.resolve("conf").resolve("jwt.key")));
    p.put("argos.auth.setup-token-file", path(secretsDir.resolve("conf").resolve("setup-token")));
    return p;
  }

  private static String path(Path file) {
    return file.toAbsolutePath().toString();
  }

  /** The reference role SQL {@code deploy/postgres/roles.sql} (C-11, ADR-0010 C-97). */
  static String rolesSql() {
    Path reference = PostgresImage.deployDirectory().resolve("roles.sql");
    try {
      return Files.readString(reference, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read " + reference, e);
    }
  }

  /** 32 random bytes, base64url without padding (43 characters, the installer's format). */
  static String newPassword() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static void writeSecret(Path file, String value) {
    try {
      Files.writeString(file, value, StandardCharsets.UTF_8);
      if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot write " + file, e);
    }
  }

  private static Path createSecretsDirectory() {
    try {
      if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
        return Files.createTempDirectory(
            "argos-server-test-",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
      }
      return Files.createTempDirectory("argos-server-test-");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void deleteRecursively(Path dir) {
    if (dir == null || !Files.exists(dir)) {
      return;
    }
    try (Stream<Path> paths = Files.walk(dir)) {
      for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(p);
      }
    } catch (IOException e) {
      LOG.log(System.Logger.Level.WARNING, "Cannot delete {0}: {1}", dir, e.getMessage());
    }
  }

  /** Builder for {@link ServerTestEnvironment}. */
  public static final class Builder {

    private PostgresImage postgresImage = PostgresImage.bundled();
    private long postgresMemoryLimitBytes = MemoryBudget.GIB;
    private long clickHouseMemoryLimitBytes = 2 * MemoryBudget.GIB;
    private boolean createRoles = true;
    private final List<Consumer<ArgosClickHouse.Builder>> clickHouseCustomizers = new ArrayList<>();
    private Duration startupTimeout = Duration.ofMinutes(3);

    private Builder() {}

    /** PostgreSQL image (default: deploy/postgres/image.properties). */
    public Builder postgresImage(PostgresImage value) {
      this.postgresImage = Objects.requireNonNull(value);
      return this;
    }

    /** PostgreSQL container memory limit (default 1 GiB). */
    public Builder postgresMemoryLimit(long bytes) {
      this.postgresMemoryLimitBytes = bytes;
      return this;
    }

    /** ClickHouse container memory limit (default 2 GiB; at most 5 GiB on shared machines). */
    public Builder clickHouseMemoryLimit(long bytes) {
      this.clickHouseMemoryLimitBytes = bytes;
      return this;
    }

    /**
     * Whether to run the role SQL (default true; false leaves only the owner account, which is then
     * the image's superuser).
     */
    public Builder createRoles(boolean value) {
      this.createRoles = value;
      return this;
    }

    /**
     * Further ClickHouse settings (e.g. {@code writerTablePrefixes}, {@code dataVolume}). The
     * account passwords are always set by this environment.
     */
    public Builder clickHouse(Consumer<ArgosClickHouse.Builder> customizer) {
      clickHouseCustomizers.add(Objects.requireNonNull(customizer));
      return this;
    }

    /** Startup timeout of each container (default 3 minutes). */
    public Builder startupTimeout(Duration value) {
      this.startupTimeout = Objects.requireNonNull(value);
      return this;
    }

    /** Starts PostgreSQL, then ClickHouse, then creates the roles. */
    public ServerTestEnvironment start() {
      Map<PgAccount, String> pgPasswords = new EnumMap<>(PgAccount.class);
      for (PgAccount account : PgAccount.values()) {
        pgPasswords.put(account, newPassword());
      }
      Map<Account, String> chPasswords = new EnumMap<>(Account.class);
      for (Account account : Account.values()) {
        chPasswords.put(account, newPassword());
      }
      Path secrets = createSecretsDirectory();
      PostgreSQLContainer postgres = null;
      ArgosClickHouse clickHouse = null;
      try {
        // With roles: the image's bootstrap superuser is "postgres" (generated password, used only
        // inside the container) and roles.sql creates the non-superuser owner, as the installer
        // does. Without roles: the owner is the image's superuser.
        String superuserPassword = newPassword();
        PostgreSQLContainer pg =
            createRoles
                ? postgresContainer(SUPERUSER, superuserPassword)
                : postgresContainer(PgAccount.OWNER.user(), pgPasswords.get(PgAccount.OWNER));
        DockerStartLock.withLock(
            () -> {
              pg.start();
              return pg;
            });
        postgres = pg;
        ArgosClickHouse.Builder ch =
            ArgosClickHouse.builder()
                .memoryLimit(clickHouseMemoryLimitBytes)
                .startupTimeout(startupTimeout);
        clickHouseCustomizers.forEach(c -> c.accept(ch));
        for (Account account : Account.values()) {
          // ArgosClickHouse generates its own passwords; override their hashes with ours so that
          // the password files below are valid (only the SHA-256 reaches the container).
          ch.environment(
              account.passwordHashVariable(), ArgosClickHouse.sha256Hex(chPasswords.get(account)));
        }
        clickHouse = DockerStartLock.withLock(ch::start);
        if (createRoles) {
          createRoles(pg, pgPasswords, superuserPassword);
        }
        ServerTestEnvironment env =
            new ServerTestEnvironment(pg, clickHouse, pgPasswords, chPasswords, secrets);
        for (PgAccount account : PgAccount.values()) {
          writeSecret(env.passwordFile(account), pgPasswords.get(account));
        }
        for (Account account : Account.values()) {
          writeSecret(env.passwordFile(account), chPasswords.get(account));
        }
        LOG.log(
            System.Logger.Level.INFO,
            "Server test environment: PostgreSQL {0} at {1}, ClickHouse {2} at {3}",
            postgresImage.version(),
            env.postgresJdbcUrl(),
            clickHouse.image().version(),
            env.clickHouseUrl());
        return env;
      } catch (RuntimeException | Error e) {
        if (clickHouse != null) {
          clickHouse.close();
        }
        if (postgres != null) {
          postgres.stop();
        }
        deleteRecursively(secrets);
        throw e;
      }
    }

    @SuppressWarnings("resource") // stopped by ServerTestEnvironment.close()
    private PostgreSQLContainer postgresContainer(String superuser, String superuserPassword) {
      long limit = postgresMemoryLimitBytes;
      boolean loopback = ArgosClickHouse.publishesOnLoopback();
      return new PostgreSQLContainer(
              DockerImageName.parse(postgresImage.image()).asCompatibleSubstituteFor("postgres"))
          .withDatabaseName(DATABASE)
          .withUsername(superuser)
          .withPassword(superuserPassword)
          .withLabel(CONTAINER_LABEL, "server-test-environment")
          .withStartupTimeoutSeconds((int) startupTimeout.toSeconds())
          .withCreateContainerCmdModifier(
              cmd -> {
                HostConfig host = Objects.requireNonNull(cmd.getHostConfig());
                host.withMemory(limit).withMemorySwap(limit);
                if (loopback) {
                  host.withPortBindings(
                      new PortBinding(
                          Ports.Binding.bindIp(ArgosClickHouse.LOOPBACK),
                          ExposedPort.tcp(PostgreSQLContainer.POSTGRESQL_PORT)));
                }
              });
    }

    /**
     * Runs {@code deploy/postgres/roles.sql} with psql in the container as the bootstrap superuser
     * (local socket), passwords as psql variables, as the installer does (WO-40).
     */
    private static void createRoles(
        PostgreSQLContainer pg, Map<PgAccount, String> passwords, String superuserPassword) {
      pg.copyFileToContainer(
          Transferable.of(rolesSql().getBytes(StandardCharsets.UTF_8)), ROLES_IN_CONTAINER);
      ExecResult result;
      try {
        result =
            pg.execInContainer(
                "psql",
                "-X",
                "-q",
                "-v",
                "ON_ERROR_STOP=1",
                "-v",
                "owner_role=" + PgAccount.OWNER.user(),
                "-v",
                "owner_password=" + passwords.get(PgAccount.OWNER),
                "-v",
                "app_password=" + passwords.get(PgAccount.APP),
                "-v",
                "audit_password=" + passwords.get(PgAccount.AUDIT),
                "-v",
                "backup_password=" + passwords.get(PgAccount.BACKUP),
                "-U",
                SUPERUSER,
                "-d",
                DATABASE,
                "-f",
                ROLES_IN_CONTAINER);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Interrupted while creating roles", e);
      }
      if (result.getExitCode() != 0) {
        // psql echoes neither the variables nor the statements (-q, no -a/-e), but a syntax error
        // quotes the failing statement: mask every password before reporting.
        String error = result.getStderr().strip();
        for (String password : passwords.values()) {
          error = error.replace(password, "****");
        }
        error = error.replace(superuserPassword, "****");
        throw new IllegalStateException(
            "Role SQL failed (exit " + result.getExitCode() + "): " + error);
      }
    }
  }
}
