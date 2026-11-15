/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.common.datasource;

import io.argos.sample.common.SampleConfig;
import org.apache.tomcat.jdbc.pool.DataSource;
import org.apache.tomcat.jdbc.pool.PoolProperties;

/** SAMPLE_POOL=tomcat: Tomcat JDBC pool (loaded only when chosen). */
final class TomcatPools {

  private TomcatPools() {}

  /**
   * A pool of {@code maxSize} connections, named {@code name} (null: Tomcat's own "Tomcat
   * Connection Pool[n-hash]").
   */
  static SampleDataSources.PoolHandle create(SampleConfig config, String name, int maxSize) {
    PoolProperties properties = new PoolProperties();
    properties.setUrl(config.poolUrl());
    properties.setDriverClassName(config.poolDriverClass());
    properties.setUsername(config.dbUser());
    properties.setPassword(config.dbPassword());
    properties.setMaxActive(maxSize);
    properties.setMaxIdle(maxSize);
    if (name != null) {
      properties.setName(name);
    }
    properties.setInitialSize(0);
    properties.setMinIdle(0);
    properties.setMaxWait(30_000);
    DataSource dataSource = new DataSource(properties);
    return new SampleDataSources.PoolHandle(
        dataSource, dataSource.getDriverClassName(), dataSource::close);
  }
}
