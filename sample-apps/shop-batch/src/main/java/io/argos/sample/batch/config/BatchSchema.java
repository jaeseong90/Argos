/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.batch.config;

import io.argos.sample.common.db.DbKind;
import javax.sql.DataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/**
 * Tables of the batch app itself (settlement output, Quartz report rows), created next to the shop
 * schema in the shared DataSource. The scripts are idempotent ({@code CREATE TABLE IF NOT EXISTS});
 * Spring Batch and Quartz create their own tables (spring.batch / spring.quartz initialize-schema).
 */
public final class BatchSchema {

  private BatchSchema() {}

  /** Classpath location of the script for {@code db}. */
  public static String scriptOf(DbKind db) {
    return "io/argos/sample/batch/schema-batch-" + db.id() + ".sql";
  }

  public static void initialize(DataSource dataSource, DbKind db) {
    ResourceDatabasePopulator populator =
        new ResourceDatabasePopulator(new ClassPathResource(scriptOf(db)));
    populator.setSqlScriptEncoding("UTF-8");
    populator.execute(dataSource);
  }
}
