/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.common.datasource;

import io.argos.sample.common.SampleConfig;
import org.apache.tomcat.jdbc.pool.DataSource;
import org.apache.tomcat.jdbc.pool.PoolProperties;

/** SAMPLE_POOL=tomcat: Tomcat JDBC pool (loaded only when chosen). */
final class TomcatPools {

  private TomcatPools() {}

  static SampleDataSources.PoolHandle create(SampleConfig config) {
    PoolProperties properties = new PoolProperties();
    properties.setUrl(config.dbUrl());
    properties.setDriverClassName(config.db().driverClass());
    properties.setUsername(config.dbUser());
    properties.setPassword(config.dbPassword());
    properties.setMaxActive(config.poolMaxSize());
    properties.setMaxIdle(config.poolMaxSize());
    properties.setInitialSize(0);
    properties.setMinIdle(0);
    properties.setMaxWait(30_000);
    DataSource dataSource = new DataSource(properties);
    return new SampleDataSources.PoolHandle(
        dataSource, dataSource.getDriverClassName(), dataSource::close);
  }
}
