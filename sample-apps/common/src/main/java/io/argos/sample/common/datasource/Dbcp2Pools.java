/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.common.datasource;

import io.argos.sample.common.SampleConfig;
import java.sql.SQLException;
import org.apache.commons.dbcp2.BasicDataSource;

/** SAMPLE_POOL=dbcp2: Apache Commons DBCP2 (loaded only when chosen). */
final class Dbcp2Pools {

  private Dbcp2Pools() {}

  // setMaxWaitMillis is the Java 8 era API (DBCP 2.9); later versions keep it as deprecated.
  @SuppressWarnings("deprecation")
  static SampleDataSources.PoolHandle create(SampleConfig config) {
    BasicDataSource dataSource = new BasicDataSource();
    dataSource.setUrl(config.dbUrl());
    dataSource.setDriverClassName(config.db().driverClass());
    dataSource.setUsername(config.dbUser());
    dataSource.setPassword(config.dbPassword());
    dataSource.setMaxTotal(config.poolMaxSize());
    dataSource.setMaxIdle(config.poolMaxSize());
    dataSource.setMaxWaitMillis(30_000);
    return new SampleDataSources.PoolHandle(
        dataSource,
        dataSource.getDriverClassName(),
        () -> {
          try {
            dataSource.close();
          } catch (SQLException e) {
            throw new IllegalStateException("Closing the DBCP2 pool failed", e);
          }
        });
  }
}
