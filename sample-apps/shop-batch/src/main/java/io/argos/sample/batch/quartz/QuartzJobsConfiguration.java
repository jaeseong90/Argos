/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.batch.quartz;

import io.argos.sample.common.SampleConfig;
import io.argos.sample.common.catalog.BatchCatalog;
import io.argos.sample.common.db.DbKind;
import java.util.Properties;
import org.quartz.CronScheduleBuilder;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.impl.jdbcjobstore.PostgreSQLDelegate;
import org.quartz.impl.jdbcjobstore.StdJDBCDelegate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.quartz.QuartzProperties;
import org.springframework.boot.autoconfigure.quartz.SchedulerFactoryBeanCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The two Quartz jobs and their triggers. Spring Boot registers every JobDetail and Trigger bean
 * with the scheduler; the job store is Quartz's JDBC store in the app's one DataSource
 * (spring.quartz.job-store-type=jdbc), so Quartz's own polling SQL runs there as well.
 */
@Configuration(proxyBeanMethods = false)
public class QuartzJobsConfiguration {

  @Bean
  public JobDetail quartzReportJob() {
    return JobBuilder.newJob(OrderReportJob.class)
        .withIdentity(BatchCatalog.QUARTZ_REPORT_JOB, BatchCatalog.QUARTZ_GROUP)
        .withDescription("Order report by status, published to ext-stub")
        .storeDurably()
        .build();
  }

  @Bean
  public Trigger quartzReportTrigger(
      @Qualifier("quartzReportJob") JobDetail job,
      @Value("${sample.batch.quartz.report-cron}") String cron) {
    return TriggerBuilder.newTrigger()
        .forJob(job)
        .withIdentity(BatchCatalog.QUARTZ_REPORT_JOB + "Trigger", BatchCatalog.QUARTZ_GROUP)
        .withSchedule(
            CronScheduleBuilder.cronSchedule(cron).withMisfireHandlingInstructionDoNothing())
        .build();
  }

  @Bean
  public JobDetail quartzCleanupJob() {
    return JobBuilder.newJob(ReportCleanupJob.class)
        .withIdentity(BatchCatalog.QUARTZ_CLEANUP_JOB, BatchCatalog.QUARTZ_GROUP)
        .withDescription("Keeps the report table bounded")
        .storeDurably()
        .build();
  }

  @Bean
  public Trigger quartzCleanupTrigger(
      @Qualifier("quartzCleanupJob") JobDetail job,
      @Value("${sample.batch.quartz.cleanup-interval-ms}") long intervalMs) {
    return TriggerBuilder.newTrigger()
        .forJob(job)
        .withIdentity(BatchCatalog.QUARTZ_CLEANUP_JOB + "Trigger", BatchCatalog.QUARTZ_GROUP)
        .withSchedule(
            SimpleScheduleBuilder.simpleSchedule()
                .withIntervalInMilliseconds(intervalMs)
                .repeatForever()
                .withMisfireHandlingInstructionNextWithRemainingCount())
        .build();
  }

  /**
   * Quartz driver delegate of SAMPLE_DB (PostgreSQL stores job data as bytea and needs its own
   * delegate) on top of the spring.quartz.properties, and the run tracker as a global job listener.
   */
  @Bean
  public SchedulerFactoryBeanCustomizer quartzCustomizer(
      QuartzProperties quartzProperties, SampleConfig config, QuartzRunTracker tracker) {
    return factory -> {
      Properties properties = new Properties();
      properties.putAll(quartzProperties.getProperties());
      properties.setProperty(
          "org.quartz.jobStore.driverDelegateClass",
          config.db() == DbKind.POSTGRES
              ? PostgreSQLDelegate.class.getName()
              : StdJDBCDelegate.class.getName());
      factory.setQuartzProperties(properties);
      factory.setGlobalJobListeners(tracker);
    };
  }
}
