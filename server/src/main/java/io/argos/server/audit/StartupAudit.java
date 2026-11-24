/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.audit;

import io.argos.server.config.ServerBuild;
import io.argos.server.ops.migration.ClickHouseMigrationPhase;
import io.argos.server.ops.migration.ClickHouseMigrationRunner;
import io.argos.server.ops.migration.PostgresMigrationPhase;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The system events of a server start (16 §11.1 group 시스템): when the application is ready (every
 * start phase done, the ports bound), one {@code MIGRATION_APPLIED} per PostgreSQL and ClickHouse
 * schema version this start applied, then {@code SERVER_STARTED} with the version. The migrations
 * run before the audit store can be written to, so they are recorded afterwards from the phases'
 * results; {@link #migrations} is the same path for {@code argos-admin migrate}.
 *
 * <p>Actor {@code system}; {@code MIGRATION_APPLIED}: target {@code schema}, id {@code
 * postgres:<version>} or {@code clickhouse:<version>}, name the description, {@code after} store,
 * version and duration; {@code SERVER_STARTED}: target {@code server}, name the version, {@code
 * after} version, commit and protocol version.
 */
@Component
public class StartupAudit {

  private final ObjectProvider<AuditService> audit;
  private final ObjectProvider<ServerBuild> build;
  private final ObjectProvider<PostgresMigrationPhase> postgres;
  private final ObjectProvider<ClickHouseMigrationPhase> clickhouse;

  public StartupAudit(
      ObjectProvider<AuditService> audit,
      ObjectProvider<ServerBuild> build,
      ObjectProvider<PostgresMigrationPhase> postgres,
      ObjectProvider<ClickHouseMigrationPhase> clickhouse) {
    this.audit = audit;
    this.build = build;
    this.postgres = postgres;
    this.clickhouse = clickhouse;
  }

  /** Records the start's events. */
  @EventListener(ApplicationReadyEvent.class)
  public void ready() {
    AuditService service = audit.getIfAvailable();
    if (service == null) {
      return;
    }
    PostgresMigrationPhase pg = postgres.getIfAvailable();
    ClickHouseMigrationPhase ch = clickhouse.getIfAvailable();
    migrations(
        service,
        pg == null ? null : pg.lastResult(),
        ch == null ? null : ch.lastResult(),
        Actor.system());
    ServerBuild b = build.getIfAvailable();
    String version = b == null ? ServerBuild.UNKNOWN : b.version();
    Map<String, Object> after = new LinkedHashMap<>();
    after.put("version", version);
    if (b != null) {
      after.put("commit", b.commit());
    }
    service.record(
        AuditEvent.SERVER_STARTED,
        Actor.system(),
        new Target("server", null, version),
        AuditResult.success(),
        null,
        after);
  }

  /**
   * One {@code MIGRATION_APPLIED} per applied version of the results (either may be null).
   *
   * @param actor {@code system} for a server start, the operator's actor for the CLI
   */
  public static void migrations(
      AuditService service,
      PostgresMigrationPhase.Result postgresResult,
      ClickHouseMigrationRunner.Result clickhouseResult,
      Actor actor) {
    if (postgresResult != null) {
      for (PostgresMigrationPhase.Applied a : postgresResult.applied()) {
        record(service, actor, "postgres", a.version(), a.description(), a.executionTimeMs());
      }
    }
    if (clickhouseResult != null) {
      for (ClickHouseMigrationRunner.Applied a : clickhouseResult.applied()) {
        record(service, actor, "clickhouse", a.version(), a.description(), a.durationMs());
      }
    }
  }

  private static void record(
      AuditService service,
      Actor actor,
      String store,
      String version,
      String description,
      long durationMs) {
    Map<String, Object> after = new LinkedHashMap<>();
    after.put("store", store);
    after.put("version", version);
    after.put("duration_ms", durationMs);
    service.record(
        AuditEvent.MIGRATION_APPLIED,
        actor,
        new Target("schema", store + ":" + version, description),
        AuditResult.success(),
        null,
        after);
  }
}
