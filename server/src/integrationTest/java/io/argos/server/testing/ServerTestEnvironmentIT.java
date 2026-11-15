/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.testing;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Ports;
import io.argos.server.config.ArgosProperties;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.storage.clickhouse.Account;
import io.argos.storage.clickhouse.ArgosClickHouse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * The shared server test fixture (WO-20, reused by WO-21, WO-23, WO-24): PostgreSQL 16 and
 * ClickHouse 26.8 from the pinned images, the C-11 accounts with generated passwords, and Spring
 * properties that bind to {@link ArgosProperties}.
 */
@Tag("req:SEC-74")
@Tag("req:SEC-76")
class ServerTestEnvironmentIT {

  private static ServerTestEnvironment env;

  @BeforeAll
  static void start() {
    env = ServerTestEnvironment.start();
  }

  @AfterAll
  static void stop() {
    if (env != null) {
      env.close();
    }
  }

  @Test
  @DisplayName("PostgreSQL: pinned 16.x image, owner owns the database, C-11 roles log in")
  void postgresAccounts() throws SQLException {
    assertThat(env.postgresContainer().getDockerImageName())
        .isEqualTo(PostgresImage.bundled().image());
    try (Connection owner = env.connect(PgAccount.OWNER);
        Statement st = owner.createStatement()) {
      assertThat(scalar(st, "SHOW server_version")).startsWith(PostgresImage.bundled().version());
      assertThat(
              scalar(
                  st,
                  "SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname = current_database()"))
          .isEqualTo("argos");
      assertThat(
              scalar(
                  st,
                  "SELECT string_agg(rolname, ',' ORDER BY rolname) FROM pg_roles"
                      + " WHERE rolname LIKE 'argos\\_%' AND rolcanlogin"))
          .isEqualTo("argos_app,argos_audit,argos_backup");
      assertThat(
              scalar(
                  st,
                  "SELECT bool_or(rolsuper OR rolcreaterole OR rolcreatedb) FROM pg_roles"
                      + " WHERE rolname IN ('argos_app','argos_audit','argos_backup')"))
          .isEqualTo("f");
    }
    for (PgAccount account : List.of(PgAccount.APP, PgAccount.AUDIT, PgAccount.BACKUP)) {
      try (Connection c = env.connect(account);
          Statement st = c.createStatement()) {
        assertThat(scalar(st, "SELECT current_user")).isEqualTo(account.user());
      }
    }
  }

  @Test
  @DisplayName("ClickHouse: the three accounts authenticate with the generated passwords")
  void clickHouseAccounts() {
    for (Account account : Account.values()) {
      assertThat(env.clickHouse(account).scalar("SELECT currentUser()")).isEqualTo(account.user());
    }
    assertThat(env.clickHouse(Account.MIGRATOR).scalar("SELECT version()"))
        .isEqualTo(env.clickHouseServer().image().version());
    assertThat(env.clickHouseServer().client("default", "").failure("SELECT 1")).isPresent();
    assertThat(
            env.clickHouse(Account.MIGRATOR)
                .scalar("SELECT count() FROM system.tables WHERE database = 'argos'"))
        .as("schema not applied by the fixture")
        .isEqualTo("0");
  }

  @Test
  @DisplayName("Spring properties bind to ArgosProperties and point at password files")
  void springProperties() throws Exception {
    ArgosProperties p =
        new Binder(new MapConfigurationPropertySource(env.springProperties()))
            .bindOrCreate("argos", ArgosProperties.class);

    assertThat(p.postgres().url()).isEqualTo(env.postgresJdbcUrl()).doesNotContain("password");
    assertThat(p.postgres().app().user()).isEqualTo("argos_app");
    assertThat(p.postgres().owner().user()).isEqualTo("argos");
    assertThat(read(p.postgres().app().passwordFile())).isEqualTo(env.password(PgAccount.APP));
    assertThat(read(p.postgres().owner().passwordFile())).isEqualTo(env.password(PgAccount.OWNER));
    assertThat(p.clickhouse().url()).isEqualTo(env.clickHouseUrl());
    assertThat(read(p.clickhouse().writer().passwordFile()))
        .isEqualTo(env.password(Account.WRITER));
    assertThat(read(p.clickhouse().reader().passwordFile()))
        .isEqualTo(env.password(Account.READER));
    assertThat(read(p.clickhouse().migrator().passwordFile()))
        .isEqualTo(env.password(Account.MIGRATOR));
    assertThat(env.springProperties().values())
        .as("no plain password in the properties")
        .noneMatch(
            v -> Arrays.stream(PgAccount.values()).anyMatch(a -> v.contains(env.password(a))))
        .noneMatch(v -> Arrays.stream(Account.values()).anyMatch(a -> v.contains(env.password(a))));
    assertThat(env.password(PgAccount.APP)).hasSizeGreaterThanOrEqualTo(12);
    assertThat(env.commandLineArguments()).hasSize(env.springProperties().size());
  }

  @Test
  @DisplayName("ports are published on 127.0.0.1 only (local Docker daemon)")
  void loopbackOnly() {
    if (!ArgosClickHouse.publishesOnLoopback()) {
      return; // remote Docker daemon: documented exception of ArgosClickHouse
    }
    assertThat(env.clickHouseServer().httpPublication().effective()).containsOnly("127.0.0.1");
    Ports.Binding[] pg =
        env.postgresContainer()
            .getContainerInfo()
            .getNetworkSettings()
            .getPorts()
            .getBindings()
            .get(ExposedPort.tcp(5432));
    assertThat(pg).isNotEmpty().allSatisfy(b -> assertThat(b.getHostIp()).isEqualTo("127.0.0.1"));
  }

  @Test
  @DisplayName("close stops both containers and deletes the password files")
  void closeCleansUp() {
    ServerTestEnvironment second =
        ServerTestEnvironment.builder().clickHouseMemoryLimit(1L << 30).createRoles(false).start();
    Path secrets = second.secretsDirectory();
    assertThat(secrets).isDirectory();
    second.close();
    assertThat(secrets).doesNotExist();
    assertThat(second.postgresContainer().isRunning()).isFalse();
  }

  private static String read(String file) throws Exception {
    return Files.readString(Path.of(file), StandardCharsets.UTF_8);
  }

  private static String scalar(Statement st, String sql) throws SQLException {
    try (ResultSet rs = st.executeQuery(sql)) {
      assertThat(rs.next()).isTrue();
      return rs.getString(1);
    }
  }
}
