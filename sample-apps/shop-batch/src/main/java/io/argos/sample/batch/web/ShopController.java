/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.batch.web;

import io.argos.sample.batch.jobs.BatchJobs;
import io.argos.sample.batch.scheduled.ScheduledStats;
import io.argos.sample.common.SampleConfig;
import io.argos.sample.common.datasource.SampleDataSource;
import io.argos.sample.common.db.ShopJdbc;
import io.argos.sample.common.diag.RuntimeInfo;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.springframework.boot.SpringBootVersion;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP side of the batch app: two order endpoints on the DataSource the jobs use (S-09: HTTP and
 * batch share one database; D-20 keeps their statistics apart), GET /api/version and GET
 * /api/runtime (switches plus scheduler and job store facts).
 */
@RestController
public class ShopController {

  private final ShopJdbc shop;
  private final SampleConfig config;
  private final SampleDataSource dataSource;
  private final Clock clock;
  private final Scheduler quartz;
  private final TaskScheduler taskScheduler;
  private final ScheduledStats scheduledStats;
  private final BatchJobs batchJobs;
  private final JdbcTemplate jdbc;

  public ShopController(
      ShopJdbc shop,
      SampleConfig config,
      SampleDataSource dataSource,
      Clock clock,
      Scheduler quartz,
      TaskScheduler taskScheduler,
      ScheduledStats scheduledStats,
      BatchJobs batchJobs,
      JdbcTemplate jdbc) {
    this.shop = shop;
    this.config = config;
    this.dataSource = dataSource;
    this.clock = clock;
    this.quartz = quartz;
    this.taskScheduler = taskScheduler;
    this.scheduledStats = scheduledStats;
    this.batchJobs = batchJobs;
    this.jdbc = jdbc;
  }

  @GetMapping("/api/orders/{id}")
  public ResponseEntity<Map<String, Object>> order(@PathVariable("id") long id) {
    return ResponseEntity.of(shop.findOrder(id));
  }

  @GetMapping("/api/orders")
  public Map<String, Object> page(@RequestParam(name = "page", defaultValue = "0") int page) {
    return shop.listOrders(page);
  }

  @GetMapping("/api/version")
  public Map<String, Object> version() {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("app", config.appName());
    result.put("version", config.appVersion());
    return result;
  }

  @GetMapping("/api/runtime")
  public Map<String, Object> runtime(HttpServletRequest request) throws SchedulerException {
    Map<String, Object> info =
        new LinkedHashMap<>(
            RuntimeInfo.describe(
                config,
                dataSource,
                List.of("hurl"),
                false,
                clock,
                new RuntimeInfo.WebLayer(
                    "Spring Boot " + SpringBootVersion.getVersion(),
                    "jakarta",
                    request.getServletContext().getServerInfo(),
                    request.getContextPath())));
    info.put("batchJobs", batchJobs.names());
    info.put("taskScheduler", taskScheduler.getClass().getSimpleName());
    info.put("scheduledVirtualThreads", scheduledStats.lastRunVirtual());
    info.put("quartzScheduler", quartz.getSchedulerName());
    info.put("quartzJobStore", quartz.getMetaData().getJobStoreClass().getSimpleName());
    info.put("quartzThreads", quartz.getMetaData().getThreadPoolSize());
    info.put("quartzStoreInAppDataSource", countsInAppDataSource("QRTZ_JOB_DETAILS") > 0);
    info.put("batchMetadataInAppDataSource", countsInAppDataSource("BATCH_JOB_INSTANCE") >= 0);
    return info;
  }

  /** Row count of a framework table read through the app's DataSource, -1 if it is not there. */
  private long countsInAppDataSource(String table) {
    try {
      Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
      return count == null ? -1 : count;
    } catch (DataAccessException e) {
      return -1;
    }
  }
}
