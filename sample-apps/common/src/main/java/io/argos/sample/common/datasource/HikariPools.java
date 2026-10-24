/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.common.datasource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.argos.sample.common.SampleConfig;

/** SAMPLE_POOL=hikari (separate class: loaded only when HikariCP is chosen). */
final class HikariPools {

  private HikariPools() {}

  static SampleDataSources.PoolHandle create(SampleConfig config) {
    HikariConfig hikari = new HikariConfig();
    hikari.setJdbcUrl(config.dbUrl());
    hikari.setDriverClassName(config.db().driverClass());
    hikari.setUsername(config.dbUser());
    hikari.setPassword(config.dbPassword());
    hikari.setMaximumPoolSize(config.poolMaxSize());
    hikari.setConnectionTimeout(30_000);
    HikariDataSource dataSource = new HikariDataSource(hikari);
    return new SampleDataSources.PoolHandle(
        dataSource, dataSource.getDriverClassName(), dataSource::close);
  }
}
