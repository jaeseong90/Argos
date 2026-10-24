/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.batch.scheduled;

import io.argos.sample.common.db.ShopSchema;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The three {@code @Scheduled} kinds of 18 §4.1 (F-A24 service names {@code BATCH
 * ScheduledJobs#method}):
 *
 * <ul>
 *   <li>{@link #refreshOrderStats()}: fixed rate (SAMPLE_BATCH_FIXED_RATE_MS, default 5 s), one
 *       query.
 *   <li>{@link #rollupTopCustomers()}: cron (SAMPLE_BATCH_CRON, default every 10 s), one aggregate
 *       query.
 *   <li>{@link #settlementScan()}: long running (S-09). It starts
 *       SAMPLE_BATCH_LONG_INITIAL_DELAY_MS after startup (default 1 s), holds one transaction and
 *       its connection for SAMPLE_BATCH_LONG_DURATION_MS (default 20 s) while it scans the orders
 *       in slices of 100 rows every SAMPLE_BATCH_LONG_SLICE_MS (default 500 ms), and runs again
 *       SAMPLE_BATCH_LONG_INTERVAL_MS after it ended (default 40 s).
 * </ul>
 *
 * The scheduler has several threads (spring.task.scheduling.pool.size), so the long-running method
 * does not hold back the other two; with SAMPLE_VT=true every run gets its own virtual thread.
 */
@Component
public class ScheduledJobs {

  private static final Logger log = LoggerFactory.getLogger(ScheduledJobs.class);
  private static final int SLICE_ROWS = 100;

  private final JdbcTemplate jdbc;
  private final TransactionTemplate readOnlyTransaction;
  private final ScheduledStats stats;
  private final long longDurationMs;
  private final long longSliceMs;

  public ScheduledJobs(
      JdbcTemplate jdbc,
      PlatformTransactionManager transactionManager,
      ScheduledStats stats,
      @Value("${sample.batch.long.duration-ms}") long longDurationMs,
      @Value("${sample.batch.long.slice-ms}") long longSliceMs) {
    if (longDurationMs < 0 || longSliceMs < 1) {
      throw new IllegalArgumentException(
          "SAMPLE_BATCH_LONG_DURATION_MS must be >= 0 and SAMPLE_BATCH_LONG_SLICE_MS >= 1");
    }
    this.jdbc = jdbc;
    TransactionTemplate template = new TransactionTemplate(transactionManager);
    template.setReadOnly(true);
    this.readOnlyTransaction = template;
    this.stats = stats;
    this.longDurationMs = longDurationMs;
    this.longSliceMs = longSliceMs;
  }

  @Scheduled(
      fixedRateString = "${sample.batch.fixed-rate-ms}",
      initialDelayString = "${sample.batch.fixed-rate-initial-delay-ms}")
  public void refreshOrderStats() {
    Long open =
        jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE status = ?", Long.class, "NEW");
    stats.fixedRateRan();
    log.debug("refreshOrderStats: {} new orders", open);
  }

  @Scheduled(cron = "${sample.batch.cron}")
  public void rollupTopCustomers() {
    List<Map<String, Object>> top =
        jdbc.queryForList(
            "SELECT customer_id, COUNT(*) AS order_count, SUM(amount) AS amount FROM orders"
                + " GROUP BY customer_id ORDER BY amount DESC, customer_id LIMIT 5");
    stats.cronRan();
    log.debug("rollupTopCustomers: {} customers", top.size());
  }

  @Scheduled(
      initialDelayString = "${sample.batch.long.initial-delay-ms}",
      fixedDelayString = "${sample.batch.long.interval-ms}")
  public void settlementScan() {
    stats.longStarted();
    long[] slices = {0};
    boolean completed = false;
    try {
      long deadline = System.nanoTime() + longDurationMs * 1_000_000L;
      completed =
          Boolean.TRUE.equals(
              readOnlyTransaction.execute(
                  status -> {
                    long from = 1;
                    while (System.nanoTime() < deadline) {
                      jdbc.queryForMap(
                          "SELECT COUNT(*) AS order_count, COALESCE(SUM(amount), 0) AS amount"
                              + " FROM orders WHERE id BETWEEN ? AND ?",
                          from,
                          from + SLICE_ROWS - 1);
                      slices[0]++;
                      from = from + SLICE_ROWS > ShopSchema.ORDERS ? 1 : from + SLICE_ROWS;
                      try {
                        Thread.sleep(longSliceMs);
                      } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false; // shutdown: stop scanning, end the transaction
                      }
                    }
                    return true;
                  }));
    } finally {
      stats.longFinished(slices[0], completed);
    }
    log.info("settlementScan: {} slices, completed={}", slices[0], completed);
  }
}
