/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.boot2.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.sample.common.BuildInfo;
import io.argos.sample.common.SampleConfig;
import io.argos.sample.common.datasource.SampleDataSource;
import io.argos.sample.common.datasource.SampleDataSources;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The context-close step of the WAR app: SHUTDOWN of the in-memory H2 database removes H2's JVM
 * shutdown hook, which would otherwise keep the web app class loader of shop-war-javax alive after
 * an undeploy. The hook check reads java.lang.ApplicationShutdownHooks by reflection, which works
 * on the app's build JDK (8); on a JDK that denies it only the database part is checked. Slow (a
 * fresh H2 database with the shop data): integrationTest, 18 §2.2; the other databases are covered
 * by EmbeddedDatabaseShutdownTest.
 */
@Tag("slow")
class EmbeddedDatabaseShutdownH2Test {

  @Test
  void shutsTheInMemoryDatabaseDownAndRemovesTheH2ShutdownHook() throws Exception {
    SampleConfig config = config("boot2-shutdown-test", "h2");
    try (SampleDataSource dataSource = SampleDataSources.create(config)) {
      assertThat(count(dataSource, "orders")).isPositive();
      List<String> hooksBefore = h2ShutdownHooks();
      EmbeddedDatabaseShutdown shutdown = new EmbeddedDatabaseShutdown(dataSource, config);
      assertThat(shutdown.applies()).isTrue();

      // SHUTDOWN ran, although the pool's reset of the connection afterwards fails (H2 90121).
      assertThat(shutdown.shutdownDatabase()).isTrue();

      List<String> hooksAfter = h2ShutdownHooks();
      if (hooksBefore != null) {
        assertThat(hooksBefore).as("H2 hook while the database is open").isNotEmpty();
        assertThat(hooksAfter).as("H2 hook after SHUTDOWN").isEmpty();
      }
      // The same URL now opens a new, empty database: the old one (with its data) is gone.
      try (Connection connection =
              DriverManager.getConnection(config.dbUrl(), config.dbUser(), config.dbPassword());
          Statement statement = connection.createStatement()) {
        assertThatThrownBy(() -> statement.executeQuery("select count(*) from orders"))
            .isInstanceOf(SQLException.class);
        statement.execute("SHUTDOWN");
      }
    }
  }

  private static SampleConfig config(String app, String db) {
    Map<String, String> env = new HashMap<>();
    env.put("SAMPLE_DB", db);
    return SampleConfig.from(env, new Properties(), new BuildInfo(app, "test"));
  }

  private static long count(SampleDataSource dataSource, String table) throws SQLException {
    try (Connection connection = dataSource.pool().getConnection();
        Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery("select count(*) from " + table)) {
      rows.next();
      return rows.getLong(1);
    }
  }

  /** Class names of the registered H2 shutdown hooks, or null when the JDK denies the lookup. */
  private static List<String> h2ShutdownHooks() {
    try {
      Class<?> hooksClass = Class.forName("java.lang.ApplicationShutdownHooks");
      Field field = hooksClass.getDeclaredField("hooks");
      field.setAccessible(true);
      List<String> names = new ArrayList<>();
      synchronized (hooksClass) {
        Map<?, ?> hooks = (Map<?, ?>) field.get(null);
        for (Object hook : hooks.keySet()) {
          String name = hook.getClass().getName();
          if (name.startsWith("org.h2.")) {
            names.add(name);
          }
        }
      }
      return names;
    } catch (ReflectiveOperationException | RuntimeException e) {
      // Java 8 (the test JDK of this app) allows the lookup: there it must work.
      if ("1.8".equals(System.getProperty("java.specification.version"))) {
        throw new AssertionError("Cannot read the JVM shutdown hooks on Java 8", e);
      }
      return null;
    }
  }
}
