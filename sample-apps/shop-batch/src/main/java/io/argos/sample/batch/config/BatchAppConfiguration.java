/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.batch.config;

import io.argos.sample.batch.ShopBatchApplication;
import io.argos.sample.common.BuildInfo;
import io.argos.sample.common.SampleClock;
import io.argos.sample.common.SampleConfig;
import io.argos.sample.common.datasource.SampleDataSource;
import io.argos.sample.common.datasource.SampleDataSources;
import io.argos.sample.common.db.ShopJdbc;
import io.argos.sample.common.http.HttpUrlConnectionClient;
import io.argos.sample.common.spring.SpringJdbcAccess;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Wires the framework-neutral core (common) into the batch app. There is exactly one DataSource
 * (SAMPLE_DB, SAMPLE_POOL, SAMPLE_ROUTING_DS): the shop data, the batch tables of this app, the
 * Spring Batch metadata (JobRepository) and the Quartz JDBC job store all live in it, and the HTTP
 * endpoints use it too (18 §4.1 "shared DataSource", S-09).
 */
@Configuration(proxyBeanMethods = false)
public class BatchAppConfiguration {

  @Bean
  public BuildInfo buildInfo() {
    return BuildInfo.load(ShopBatchApplication.class, ShopBatchApplication.APP_NAME);
  }

  @Bean
  public SampleConfig sampleConfig(BuildInfo buildInfo) {
    return SampleConfig.fromEnvironment(buildInfo);
  }

  @Bean
  public Clock sampleClock(SampleConfig config) {
    return SampleClock.of(config);
  }

  /** The pool (and routing chain), the shop schema and data, and this app's own tables. */
  @Bean(destroyMethod = "close")
  public SampleDataSource sampleDataSource(SampleConfig config) {
    SampleDataSource dataSource = SampleDataSources.create(config);
    try {
      BatchSchema.initialize(dataSource.dataSource(), config.db());
    } catch (RuntimeException e) {
      dataSource.close();
      throw e;
    }
    return dataSource;
  }

  /**
   * The one DataSource of the app; Spring Boot hands it to Spring Batch, Quartz, JdbcTemplate and
   * the transaction manager. Closed through {@link SampleDataSource}.
   */
  @Bean(destroyMethod = "")
  @Primary
  public DataSource dataSource(SampleDataSource sampleDataSource) {
    return sampleDataSource.dataSource();
  }

  @Bean
  public ShopJdbc shopJdbc(DataSource dataSource, SampleConfig config, Clock clock) {
    return new ShopJdbc(new SpringJdbcAccess(dataSource), config.db(), clock);
  }

  /** Client of the Quartz report job's call to ext-stub (HttpURLConnection, "hurl"). */
  @Bean
  public HttpUrlConnectionClient reportPublisher(SampleConfig config) {
    return new HttpUrlConnectionClient(config.extConnectTimeoutMs(), config.extTimeoutMs());
  }
}
