/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.migration;

import io.argos.server.ServerApplication;
import io.argos.server.bootstrap.StartupAbortedException;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.storage.clickhouse.Account;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;

/**
 * Starts the real server ({@link ServerApplication}, every start phase, embedded web server on
 * ephemeral ports) against a {@link ServerTestEnvironment}, optionally with a fixture {@link
 * MigrationSource} registered as the primary source (no test hook in the server: the bundled source
 * bean is simply outranked in the test's application context).
 */
final class MigrationApps {

  private MigrationApps() {}

  /** The bundled ClickHouse migration files (jar resources db/clickhouse). */
  static MigrationSource bundled() {
    return MigrationSource.fromClasspath(MigrationApps.class.getClassLoader());
  }

  /** The bundled files with the fixture {@code migration-fixtures/<name>} on top. */
  static MigrationSource withFixture(String name) {
    URL url = MigrationApps.class.getResource("/migration-fixtures/" + name);
    if (url == null) {
      throw new IllegalArgumentException("No fixture " + name);
    }
    try {
      return bundled().overlay(MigrationSource.fromDirectory(Path.of(url.toURI())));
    } catch (URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Starts the server; the caller closes the context. */
  static ConfigurableApplicationContext start(
      ServerTestEnvironment env, MigrationSource source, Map<String, String> extra) {
    SpringApplication app = new SpringApplication(ServerApplication.class);
    Map<String, Object> properties = new LinkedHashMap<>(env.springProperties());
    properties.put("server.address", "127.0.0.1");
    properties.put("server.port", "0");
    properties.put("management.server.address", "127.0.0.1");
    properties.put("management.server.port", "0");
    properties.put("argos.web.public-url", "http://127.0.0.1:8080");
    properties.putAll(extra);
    app.setDefaultProperties(properties);
    if (source != null) {
      app.addInitializers(
          context ->
              ((GenericApplicationContext) context)
                  .registerBean(
                      "fixtureMigrationSource",
                      MigrationSource.class,
                      () -> source,
                      definition -> definition.setPrimary(true)));
    }
    return app.run();
  }

  /** Starts the server with the bundled files. */
  static ConfigurableApplicationContext start(ServerTestEnvironment env) {
    return start(env, null, Map.of());
  }

  /** Starts the server expecting a refused start; returns the abort. */
  static StartupAbortedException refused(
      ServerTestEnvironment env, MigrationSource source, Map<String, String> extra) {
    try (ConfigurableApplicationContext context = start(env, source, extra)) {
      throw new AssertionError("The start was not refused: " + context.getId());
    } catch (RuntimeException e) {
      Throwable t = e;
      while (t != null) {
        if (t instanceof StartupAbortedException aborted) {
          return aborted;
        }
        t = t.getCause();
      }
      throw e;
    }
  }

  /** One column of a PostgreSQL query as text, as {@code account}. */
  static List<String> pg(ServerTestEnvironment env, PgAccount account, String sql)
      throws SQLException {
    try (Connection c = env.connect(account);
        Statement st = c.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      List<String> values = new ArrayList<>();
      while (rs.next()) {
        values.add(rs.getString(1));
      }
      return values;
    }
  }

  /** A single PostgreSQL value as text. */
  static String pgScalar(ServerTestEnvironment env, PgAccount account, String sql)
      throws SQLException {
    List<String> values = pg(env, account, sql);
    if (values.size() != 1) {
      throw new AssertionError("Expected one row from " + sql + ", got " + values);
    }
    return values.get(0);
  }

  /** Executes a PostgreSQL statement as {@code account}. */
  static void pgExecute(ServerTestEnvironment env, PgAccount account, String sql)
      throws SQLException {
    try (Connection c = env.connect(account);
        Statement st = c.createStatement()) {
      st.execute(sql);
    }
  }

  /** A ClickHouse count as the reader account. */
  static long chCount(ServerTestEnvironment env, String sql) {
    return env.clickHouse(Account.READER).scalarLong(sql);
  }

  /** The secrets of the environment (to assert that none leaks into a message). */
  static List<String> secrets(ServerTestEnvironment env) {
    List<String> secrets = new ArrayList<>();
    for (PgAccount a : PgAccount.values()) {
      secrets.add(env.password(a));
    }
    for (Account a : Account.values()) {
      secrets.add(env.password(a));
    }
    return secrets;
  }
}
