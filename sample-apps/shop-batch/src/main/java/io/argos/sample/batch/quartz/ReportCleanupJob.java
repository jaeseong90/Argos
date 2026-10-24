/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.batch.quartz;

import java.util.Map;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.quartz.QuartzJobBean;

/**
 * Quartz job 2 ({@code quartzCleanupJob}, simple trigger every SAMPLE_BATCH_QUARTZ_CLEANUP_MS,
 * default 60 s): keeps the newest {@value #KEEP_ROWS} report rows (two statements: the newest id,
 * then the delete), so the report table stays bounded however long the app runs.
 */
@DisallowConcurrentExecution
public class ReportCleanupJob extends QuartzJobBean {

  static final int KEEP_ROWS = 200;

  private final JdbcTemplate jdbc;

  public ReportCleanupJob(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  protected void executeInternal(JobExecutionContext context) {
    Long newest = jdbc.queryForObject("SELECT MAX(id) FROM order_reports", Long.class);
    int deleted =
        newest == null
            ? 0
            : jdbc.update("DELETE FROM order_reports WHERE id <= ?", newest - KEEP_ROWS);
    context.setResult(Map.of("deleted", deleted));
  }
}
