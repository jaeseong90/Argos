/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.batch.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.sample.common.db.DbKind;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * The H2 batch schema script can run again on an existing schema (restart on a kept database). Slow
 * in a fresh JVM (H2 and Spring JDBC start): integrationTest, 18 §2.2.
 */
@Tag("slow")
class BatchSchemaH2Test {

  @Test
  void theH2ScriptCanRunAgainOnAnExistingSchema() {
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource("jdbc:h2:mem:batch-schema-test;DB_CLOSE_DELAY=-1", "sa", "");

    BatchSchema.initialize(dataSource, DbKind.H2);
    BatchSchema.initialize(dataSource, DbKind.H2);

    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    jdbc.update(
        "INSERT INTO settlement_lines (job_execution_id, item_id, order_id, amount)"
            + " VALUES (1, 1, 1, 10.00)");
    jdbc.update(
        "INSERT INTO order_reports (reported_at, status, order_count, amount)"
            + " VALUES (CURRENT_TIMESTAMP, 'NEW', 1, 10.00)");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM settlement_lines", Long.class))
        .isEqualTo(1L);
    assertThat(jdbc.queryForObject("SELECT MAX(id) FROM order_reports", Long.class)).isEqualTo(1L);
    jdbc.execute("SHUTDOWN");
  }
}
