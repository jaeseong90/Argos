/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.batch.quartz;

import io.argos.sample.common.SampleConfig;
import io.argos.sample.common.http.HttpUrlConnectionClient;
import io.argos.sample.common.http.OutboundOutcome;
import java.io.IOException;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.quartz.QuartzJobBean;

/**
 * Quartz job 1 ({@code quartzReportJob}, cron SAMPLE_BATCH_QUARTZ_REPORT_CRON, default every 30 s):
 * aggregates the orders by status (one query), stores the report rows (one JDBC batch) and
 * publishes the summary to ext-stub (one outgoing call inside a BATCH transaction; a failed call is
 * recorded, the job still completes).
 */
@DisallowConcurrentExecution
public class OrderReportJob extends QuartzJobBean {

  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final HttpUrlConnectionClient publisher;
  private final SampleConfig config;

  public OrderReportJob(
      JdbcTemplate jdbc, Clock clock, HttpUrlConnectionClient publisher, SampleConfig config) {
    this.jdbc = jdbc;
    this.clock = clock;
    this.publisher = publisher;
    this.config = config;
  }

  @Override
  protected void executeInternal(JobExecutionContext context) {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT status, COUNT(*) AS order_count, COALESCE(SUM(amount), 0) AS amount"
                + " FROM orders GROUP BY status ORDER BY status");
    Timestamp now = Timestamp.from(clock.instant());
    List<Object[]> inserts = new ArrayList<>();
    long orders = 0;
    for (Map<String, Object> row : rows) {
      long count = ((Number) row.get("order_count")).longValue();
      orders += count;
      inserts.add(new Object[] {now, row.get("status"), count, row.get("amount")});
    }
    jdbc.batchUpdate(
        "INSERT INTO order_reports (reported_at, status, order_count, amount) VALUES (?, ?, ?, ?)",
        inserts);
    OutboundOutcome publish;
    try {
      publish =
          OutboundOutcome.ofStatus(
              publisher
                  .get(
                      URI.create(
                          config.extUrl()
                              + "/reports/orders?statuses="
                              + rows.size()
                              + "&orders="
                              + orders))
                  .status());
    } catch (IOException e) {
      publish = OutboundOutcome.classify(e);
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("statuses", rows.size());
    result.put("orders", orders);
    result.put("publish", publish.name());
    context.setResult(result);
  }
}
