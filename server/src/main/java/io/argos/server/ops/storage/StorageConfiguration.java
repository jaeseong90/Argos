/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.storage;

import io.argos.server.audit.AuditService;
import io.argos.server.config.ArgosProperties;
import io.argos.server.config.TestingHooks;
import io.argos.server.storage.clickhouse.ClickHouseBatchWriter;
import io.argos.server.storage.clickhouse.DeadLetterStore;
import io.argos.server.storage.postgres.ops.StorageOpsRepository;
import io.argos.server.storage.query.QueryExecutor;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Path;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The storage health beans (WO-28): the {@link DiskUsageProbe} ({@code system.disks}, or the file
 * of {@code argos.testing.disk-usage-file} in a development build), the {@link EmergencyRetention}
 * and the {@link StorageHealthJob}.
 */
@Configuration(proxyBeanMethods = false)
public class StorageConfiguration {

  private static final Logger LOG = LoggerFactory.getLogger(StorageConfiguration.class);

  /** The ClickHouse disk usage. */
  @Bean
  public DiskUsageProbe diskUsageProbe(ObjectProvider<TestingHooks> hooks) {
    TestingHooks h = hooks.getIfAvailable();
    String file = h == null ? null : h.active().map(t -> t.diskUsageFile()).orElse(null);
    if (file != null) {
      LOG.warn(
          "argos.testing.disk-usage-file is set: the ClickHouse disk usage is read from that file"
              + " while it exists (development build, 18 §15)");
      return DiskUsageProbe.file(Path.of(file), DiskUsageProbe.systemDisks());
    }
    return DiskUsageProbe.systemDisks();
  }

  /** The emergency retention (F-S23). */
  @Bean
  public EmergencyRetention emergencyRetention(
      ArgosProperties properties,
      ObjectProvider<StorageOpsRepository> events,
      AuditService audit,
      ObjectProvider<ClickHouseBatchWriter> writer,
      MeterRegistry registry,
      ObjectProvider<Clock> clock) {
    EmergencyRetention.InsertGate gate =
        new EmergencyRetention.InsertGate() {
          @Override
          public void pause() {
            ClickHouseBatchWriter w = writer.getIfAvailable();
            if (w != null) {
              w.pauseInserts();
            }
          }

          @Override
          public void resume() {
            ClickHouseBatchWriter w = writer.getIfAvailable();
            if (w != null) {
              w.resumeInserts();
            }
          }
        };
    return new EmergencyRetention(
        properties.ops(),
        EmergencyRetention.clickHouse(properties.clickhouse()),
        events.getIfAvailable(),
        audit,
        gate,
        registry,
        clock.getIfAvailable(Clock::systemUTC));
  }

  /** The storage health job ({@code argos.ops.storage-health-interval}). */
  @Bean
  public StorageHealthJob storageHealthJob(
      ArgosProperties properties,
      QueryExecutor queries,
      DiskUsageProbe probe,
      EmergencyRetention emergency,
      ObjectProvider<DeadLetterStore> deadLetter,
      ObjectProvider<StorageOpsRepository> repository,
      MeterRegistry registry,
      ObjectProvider<Clock> clock) {
    return new StorageHealthJob(
        queries,
        properties.clickhouse().database(),
        properties.clickhouse().tablePrefix(),
        Path.of(properties.wal().dir()),
        probe,
        emergency,
        deadLetter.getIfAvailable(),
        repository.getIfAvailable(),
        registry,
        clock.getIfAvailable(Clock::systemUTC),
        properties.ops().storageHealthInterval(),
        properties.ops().emergency().dropsPerMinute());
  }
}
