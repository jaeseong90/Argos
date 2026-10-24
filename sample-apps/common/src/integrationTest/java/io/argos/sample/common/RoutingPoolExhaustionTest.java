/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.common;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.sample.common.datasource.SampleDataSource;
import io.argos.sample.common.datasource.SampleDataSources;
import io.argos.sample.common.db.PoolExhauster;
import io.argos.sample.common.spring.SpringJdbcAccess;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Pool exhaustion through the SAMPLE_ROUTING_DS chain with Spring's DataSourceUtils. Slow (a fresh
 * H2 database with the shop data, then holders that wait): integrationTest, 18 §2.2.
 */
@Tag("slow")
class RoutingPoolExhaustionTest {

  @Test
  @DisplayName("Routing -> TransactionAwareDataSourceProxy -> pool: holders take real connections")
  void exhaustsThroughRoutingChain() throws Exception {
    Map<String, String> env = new HashMap<>();
    env.put("SAMPLE_POOL_MAX", "2");
    env.put("SAMPLE_ROUTING_DS", "true");
    SampleConfig config =
        SampleConfig.from(env, new Properties(), new BuildInfo("routing-exhaust", "t"));
    try (SampleDataSource dataSource = SampleDataSources.create(config)) {
      PoolExhauster exhauster =
          new PoolExhauster(
              new SpringJdbcAccess(dataSource.dataSource()), dataSource.maxPoolSize());
      Map<String, Object> result = exhauster.exhaust(400);
      assertThat(result)
          .containsEntry("allHeld", true)
          .containsEntry("held", 2)
          .containsEntry("failedHolders", 0);
      assertThat((Long) result.get("waitedMs")).isGreaterThanOrEqualTo(300L);
    }
  }
}
