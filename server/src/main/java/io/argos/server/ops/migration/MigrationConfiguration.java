/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.migration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The bundled ClickHouse migration files ({@link MigrationSource#fromClasspath}), read once when
 * the context starts (no database access). Integration tests replace this bean with a
 * {@code @Primary} source that adds fixture scripts.
 */
@Configuration(proxyBeanMethods = false)
public class MigrationConfiguration {

  /** The files under {@code db/clickhouse} of the server jar. */
  @Bean
  public MigrationSource clickHouseMigrationSource() {
    return MigrationSource.fromClasspath(MigrationConfiguration.class.getClassLoader());
  }
}
