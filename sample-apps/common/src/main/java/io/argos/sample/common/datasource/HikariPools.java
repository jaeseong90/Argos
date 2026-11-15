/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.common.datasource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.argos.sample.common.SampleConfig;

/** SAMPLE_POOL=hikari (separate class: loaded only when HikariCP is chosen). */
final class HikariPools {

  private HikariPools() {}

  /** A pool of {@code maxSize} connections, named {@code name} (null: Hikari's HikariPool-n). */
  static SampleDataSources.PoolHandle create(SampleConfig config, String name, int maxSize) {
    HikariConfig hikari = new HikariConfig();
    hikari.setJdbcUrl(config.poolUrl());
    hikari.setDriverClassName(config.poolDriverClass());
    hikari.setUsername(config.dbUser());
    hikari.setPassword(config.dbPassword());
    hikari.setMaximumPoolSize(maxSize);
    if (name != null) {
      hikari.setPoolName(name);
    }
    hikari.setConnectionTimeout(30_000);
    HikariDataSource dataSource = new HikariDataSource(hikari);
    return new SampleDataSources.PoolHandle(
        dataSource, dataSource.getDriverClassName(), dataSource::close);
  }
}
