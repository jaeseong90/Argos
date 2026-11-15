/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import io.argos.sample.common.datasource.SampleDataSource;
import io.argos.sample.common.datasource.SampleDataSources;
import io.argos.sample.common.datasource.SampleRoutingDataSource;
import io.argos.sample.common.db.JdbcAccess;
import io.argos.sample.common.db.ShopJdbc;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;

/**
 * SAMPLE_POOL x SAMPLE_ROUTING_DS (18 §4.1, §4.4 four-level chain). Slow (every case creates a
 * fresh H2 database with the shop data; the first one in a JVM also loads H2, 2.9 to 3.1 s in a
 * parallel build): integrationTest, 18 §2.2.
 */
@Tag("slow")
class SampleDataSourcesTest {

  @ParameterizedTest(name = "SAMPLE_POOL={0} SAMPLE_ROUTING_DS={1}")
  @CsvSource({
    "hikari, false, HikariDataSource -> org.h2.Driver",
    "tomcat, false, org.apache.tomcat.jdbc.pool.DataSource -> org.h2.Driver",
    "dbcp2, false, BasicDataSource -> org.h2.Driver",
    "hikari, true, SampleRoutingDataSource (AbstractRoutingDataSource) -> "
        + "TransactionAwareDataSourceProxy -> HikariDataSource -> org.h2.Driver",
  })
  void createsPoolChainAndData(String pool, boolean routing, String chain) {
    SampleConfig config = config(pool, routing);
    try (SampleDataSource dataSource = SampleDataSources.create(config)) {
      assertThat(String.join(" -> ", dataSource.chain())).isEqualTo(chain);
      assertThat(dataSource.routing()).isEqualTo(routing);
      assertThat(dataSource.maxPoolSize()).isEqualTo(2);
      assertThat(dataSource.routingLookups()).isZero();
      ShopJdbc jdbc =
          new ShopJdbc(JdbcAccess.of(dataSource.dataSource()), config.db(), Clock.systemUTC());
      assertThat(jdbc.findOrder(1)).isPresent();
      assertThat(jdbc.orderItemsNPlusOne(1)).containsEntry("items", 20);
      if (routing) {
        assertThat(dataSource.routingLookups()).isPositive();
      } else {
        assertThat(dataSource.routingLookups()).isZero();
        assertThat(dataSource.dataSource()).isSameAs(dataSource.pool());
      }
    }
  }

  @Test
  @DisplayName("SAMPLE_ROUTING_DS=true: the app's DataSource is the four-level object chain")
  void routingChainObjects() {
    SampleConfig config = config("hikari", true);
    try (SampleDataSource dataSource = SampleDataSources.create(config)) {
      DataSource outer = dataSource.dataSource();
      assertThat(outer).isInstanceOf(SampleRoutingDataSource.class);
      DataSource second = ((SampleRoutingDataSource) outer).getResolvedDefaultDataSource();
      assertThat(second).isInstanceOf(TransactionAwareDataSourceProxy.class);
      DataSource third = ((TransactionAwareDataSourceProxy) second).getTargetDataSource();
      assertThat(third).isInstanceOf(HikariDataSource.class).isSameAs(dataSource.pool());
      assertThat(((HikariDataSource) third).getDriverClassName()).isEqualTo("org.h2.Driver");

      ShopJdbc jdbc = new ShopJdbc(JdbcAccess.of(outer), config.db(), Clock.systemUTC());
      long before = dataSource.routingLookups();
      assertThat(jdbc.findOrder(1)).isPresent();
      assertThat(dataSource.routingLookups()).isGreaterThan(before);
      assertThat(((SampleRoutingDataSource) outer).lookups())
          .isEqualTo(dataSource.routingLookups());
    }
  }

  @ParameterizedTest(name = "SAMPLE_POOL={0} SAMPLE_ROUTING_DS={1} SAMPLE_DRIVER_WRAPPER=true")
  @CsvSource({
    "hikari, false, HikariDataSource -> WrappingDriver (java.sql.Driver wrapper) -> org.h2.Driver",
    "tomcat, false, org.apache.tomcat.jdbc.pool.DataSource -> WrappingDriver (java.sql.Driver"
        + " wrapper) -> org.h2.Driver",
    "dbcp2, true, SampleRoutingDataSource (AbstractRoutingDataSource) ->"
        + " TransactionAwareDataSourceProxy -> BasicDataSource -> WrappingDriver (java.sql.Driver"
        + " wrapper) -> org.h2.Driver",
    "hikari, true, SampleRoutingDataSource (AbstractRoutingDataSource) ->"
        + " TransactionAwareDataSourceProxy -> HikariDataSource -> WrappingDriver (java.sql.Driver"
        + " wrapper) -> org.h2.Driver",
  })
  @DisplayName(
      "SAMPLE_DRIVER_WRAPPER=true (ADR-0004 C-40): the pool connects through the delegating driver"
          + " wrapper, alone and as the fifth level under SAMPLE_ROUTING_DS")
  void driverWrapper(String pool, boolean routing, String chain) throws Exception {
    Map<String, String> env = env(pool, routing);
    env.put("SAMPLE_DRIVER_WRAPPER", "true");
    SampleConfig config =
        SampleConfig.from(
            env, new Properties(), new BuildInfo("wrap-" + pool + "-" + routing, "t"));
    try (SampleDataSource dataSource = SampleDataSources.create(config)) {
      assertThat(String.join(" -> ", dataSource.chain())).isEqualTo(chain);
      assertThat(dataSource.driverWrapper()).isTrue();
      long before = dataSource.driverWrapperConnections();
      assertThat(before).as("schema and data went through the wrapper").isPositive();
      ShopJdbc jdbc =
          new ShopJdbc(JdbcAccess.of(dataSource.dataSource()), config.db(), Clock.systemUTC());
      assertThat(jdbc.findOrder(1)).isPresent();
      assertThat(jdbc.listOrders(0)).containsEntry("rows", 100);
      try (java.sql.Connection connection = dataSource.dataSource().getConnection()) {
        assertThat(connection.isWrapperFor(example.jdbcwrapper.WrappedConnection.class))
            .as("pool connection over the wrapper")
            .isTrue();
        try (java.sql.PreparedStatement statement =
            connection.prepareStatement(config.db().pingSql())) {
          assertThat(statement.isWrapperFor(example.jdbcwrapper.WrappedPreparedStatement.class))
              .isTrue();
          assertThat(statement.executeQuery().next()).isTrue();
        }
      }
      if (routing) {
        assertThat(dataSource.routingLookups()).isPositive();
      }
    }
  }

  @ParameterizedTest(name = "SAMPLE_POOL={0} SAMPLE_SECOND_POOL=true")
  @CsvSource({"hikari, shop-primary", "tomcat, shop-primary", "dbcp2, ''"})
  @DisplayName(
      "SAMPLE_SECOND_POOL=true: the schema comes through the restarted second pool, the app's pool"
          + " is named shop-primary (Hikari, Tomcat JDBC) and serves the data")
  void secondPool(String pool, String primaryName) throws Exception {
    Map<String, String> env = env(pool, false);
    env.put("SAMPLE_SECOND_POOL", "true");
    SampleConfig config =
        SampleConfig.from(env, new Properties(), new BuildInfo("second-" + pool, "t"));
    try (SampleDataSource dataSource = SampleDataSources.create(config)) {
      assertThat(dataSource.secondPool()).isTrue();
      DataSource app = dataSource.pool();
      if (app instanceof HikariDataSource) {
        assertThat(((HikariDataSource) app).getPoolName()).isEqualTo(primaryName);
      } else if (app instanceof org.apache.tomcat.jdbc.pool.DataSource) {
        assertThat(((org.apache.tomcat.jdbc.pool.DataSource) app).getName()).isEqualTo(primaryName);
      }
      ShopJdbc jdbc = new ShopJdbc(JdbcAccess.of(app), config.db(), Clock.systemUTC());
      assertThat(jdbc.findOrder(1)).as("data set up through the second pool").isPresent();
      assertThat(jdbc.listOrders(0)).containsEntry("rows", 100);
    }
  }

  private static Map<String, String> env(String pool, boolean routing) {
    Map<String, String> env = new HashMap<>();
    env.put("SAMPLE_POOL", pool);
    env.put("SAMPLE_POOL_MAX", "2");
    env.put("SAMPLE_ROUTING_DS", Boolean.toString(routing));
    return env;
  }

  private static SampleConfig config(String pool, boolean routing) {
    Map<String, String> env = new HashMap<>();
    env.put("SAMPLE_POOL", pool);
    env.put("SAMPLE_POOL_MAX", "2");
    env.put("SAMPLE_ROUTING_DS", Boolean.toString(routing));
    return SampleConfig.from(
        env, new Properties(), new BuildInfo("ds-" + pool + "-" + routing, "t"));
  }
}
