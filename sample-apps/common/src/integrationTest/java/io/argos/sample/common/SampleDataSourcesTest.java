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

  private static SampleConfig config(String pool, boolean routing) {
    Map<String, String> env = new HashMap<>();
    env.put("SAMPLE_POOL", pool);
    env.put("SAMPLE_POOL_MAX", "2");
    env.put("SAMPLE_ROUTING_DS", Boolean.toString(routing));
    return SampleConfig.from(
        env, new Properties(), new BuildInfo("ds-" + pool + "-" + routing, "t"));
  }
}
