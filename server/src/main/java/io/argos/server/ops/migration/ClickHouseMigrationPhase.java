/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.migration;

import com.clickhouse.client.api.Client;
import com.zaxxer.hikari.HikariDataSource;
import io.argos.server.bootstrap.PhaseOrder;
import io.argos.server.bootstrap.StartupPhase;
import io.argos.server.bootstrap.StartupPhaseException;
import io.argos.server.config.ArgosProperties;
import io.argos.server.storage.clickhouse.ClickHouseClients;
import io.argos.server.storage.postgres.PostgresDataSources;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The ClickHouse schema step of the start sequence ({@code CLICKHOUSE_MIGRATION}, DSN-12-15; 12
 * §9.3; 13 §13.2): {@link ClickHouseMigrationRunner} with the migration account {@code
 * argos.clickhouse.migrator.*} and one PostgreSQL connection of the schema owner for {@code
 * ch_schema_history} and {@code settings} (16 §10.8: history is written by the migration account;
 * {@code argos_app} only reads it). Both connections are closed at the end of the step.
 *
 * <p>{@value #REPAIR_KEY} = {@code true} ({@link ArgosProperties.ClickHouse.Migration}) is a
 * one-shot setting: it re-records the checksums of changed applied files (after a WARN per version
 * and with one {@code repair.*} history row) instead of refusing the start, and should be removed
 * afterwards.
 */
@Component
public class ClickHouseMigrationPhase implements StartupPhase {

  /** One-shot checksum repair (default false). */
  public static final String REPAIR_KEY = "argos.clickhouse.migration.repair-checksums";

  private static final Logger LOG = LoggerFactory.getLogger(ClickHouseMigrationPhase.class);

  private final ArgosProperties properties;
  private final MigrationSource source;
  private volatile ClickHouseMigrationRunner.Result lastResult;

  public ClickHouseMigrationPhase(ArgosProperties properties, MigrationSource source) {
    this.properties = properties;
    this.source = source;
  }

  @Override
  public PhaseOrder order() {
    return PhaseOrder.CLICKHOUSE_MIGRATION;
  }

  @Override
  public String name() {
    return "clickhouse-migration";
  }

  @Override
  public void run() throws StartupPhaseException {
    SchemaParameters params;
    try {
      params = SchemaParameters.from(properties);
    } catch (IllegalArgumentException e) {
      throw new StartupPhaseException(e.getMessage(), e);
    }
    boolean repair = properties.clickhouse().migration().repairChecksums();
    HikariDataSource owner;
    Client client;
    try {
      owner = PostgresDataSources.openOwner(properties.postgres());
    } catch (IllegalStateException e) {
      throw new StartupPhaseException(e.getMessage(), e);
    }
    try (owner) {
      try {
        client =
            ClickHouseClients.open(
                properties.clickhouse(),
                ClickHouseClients.Account.MIGRATOR,
                ClickHouseMigrationRunner.STATEMENT_TIMEOUT.plusMinutes(1));
      } catch (IllegalStateException e) {
        throw new StartupPhaseException(e.getMessage(), e);
      }
      try (client;
          Connection connection = owner.getConnection()) {
        connection.setAutoCommit(true);
        ClickHouseMigrationRunner runner =
            new ClickHouseMigrationRunner(
                source,
                params,
                new ClickHouseClientStatements(client, ClickHouseMigrationRunner.STATEMENT_TIMEOUT),
                new PostgresMigrationStore(connection),
                Clock.systemUTC(),
                repair);
        ClickHouseMigrationRunner.Result result = runner.run();
        lastResult = result;
        LOG.info(
            "ClickHouse migration finished in {} ms: {} applied, {} checksums repaired",
            result.durationMs(),
            result.applied().size(),
            result.repaired().size());
      } catch (SQLException e) {
        throw new StartupPhaseException(
            "PostgreSQL: cannot connect as the schema owner "
                + properties.postgres().owner().user()
                + ": "
                + MigrationException.oneLine(e, 300),
            e);
      } catch (MigrationException e) {
        throw new StartupPhaseException(e.getMessage(), e);
      }
    }
  }

  /** The result of the last run (tests, start summary), or null. */
  public ClickHouseMigrationRunner.Result lastResult() {
    return lastResult;
  }
}
