/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.migration;

import com.zaxxer.hikari.HikariDataSource;
import io.argos.server.bootstrap.PhaseOrder;
import io.argos.server.bootstrap.StartupPhase;
import io.argos.server.bootstrap.StartupPhaseException;
import io.argos.server.config.ArgosProperties;
import io.argos.server.storage.postgres.PostgresDataSources;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.flywaydb.core.api.output.MigrateOutput;
import org.flywaydb.core.api.output.MigrateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

/**
 * The PostgreSQL schema step of the start sequence ({@code POSTGRES_MIGRATION}, DSN-12-15; 13
 * §13.1; C-11, ADR-0010 C-97): Flyway with the schema owner account, then the owner data source is
 * closed. Spring Boot's Flyway auto-configuration is not used ({@code ServerApplication} excludes
 * it), so Flyway runs here and nowhere else.
 *
 * <p>Settings: location {@value #LOCATION} ({@code V<NNN>__<lower_snake>.sql}, C-28, checked by
 * {@code checkFlywayNames} at build time and by Flyway's naming validation here), schema {@value
 * #SCHEMA}, {@code validateOnMigrate} on, no baseline, {@code clean} disabled, no placeholder
 * replacement, no out-of-order migrations, and no ignored validation patterns: a changed checksum
 * of an applied file, an applied version that this server does not bundle (a newer server applied
 * it) and a missing file all refuse the start.
 */
@Component
public class PostgresMigrationPhase implements StartupPhase, Ordered {

  /** Flyway location of the PostgreSQL migrations (13 §13.1). */
  public static final String LOCATION = "classpath:db/pg";

  /** The schema of every Argos table (13 §12). */
  public static final String SCHEMA = "argos";

  /** Monthly partitions kept ahead of the current month (V001 partition rule). */
  static final int PARTITION_MONTHS_AHEAD = 2;

  private static final Logger LOG = LoggerFactory.getLogger(PostgresMigrationPhase.class);

  private final ArgosProperties properties;
  private volatile Result lastResult;

  public PostgresMigrationPhase(ArgosProperties properties) {
    this.properties = properties;
  }

  @Override
  public PhaseOrder order() {
    return PhaseOrder.POSTGRES_MIGRATION;
  }

  @Override
  public String name() {
    return "postgres-flyway";
  }

  @Override
  public int getOrder() {
    return 0;
  }

  @Override
  public void run() throws StartupPhaseException {
    HikariDataSource owner;
    try {
      owner = PostgresDataSources.openOwner(properties.postgres());
    } catch (IllegalStateException e) {
      throw new StartupPhaseException(e.getMessage(), e);
    }
    try (owner) {
      MigrateResult result = flyway(owner).migrate();
      List<Applied> applied = new ArrayList<>();
      for (MigrateOutput m : result.migrations) {
        applied.add(new Applied(m.version, m.description, m.executionTime));
      }
      lastResult =
          new Result(
              result.targetSchemaVersion == null
                  ? result.initialSchemaVersion
                  : result.targetSchemaVersion,
              result.migrationsExecuted,
              applied);
      LOG.info(
          "PostgreSQL schema {} at version {} ({} migrations applied in {} ms, owner {})",
          SCHEMA,
          result.targetSchemaVersion == null
              ? result.initialSchemaVersion
              : result.targetSchemaVersion,
          result.migrationsExecuted,
          result.getTotalMigrationTime(),
          properties.postgres().owner().user());
      ensurePartitions(owner);
    } catch (FlywayValidateException e) {
      throw new StartupPhaseException(
          "PostgreSQL schema validation failed, nothing was changed: "
              + summary(e)
              + ". A released migration file was changed, removed or comes from a newer server;"
              + " restore the released files",
          e);
    } catch (FlywayException e) {
      throw new StartupPhaseException("PostgreSQL migration failed: " + summary(e), e);
    }
  }

  /** The Flyway configuration of the server (owner data source). */
  static Flyway flyway(DataSource owner) {
    return Flyway.configure(PostgresMigrationPhase.class.getClassLoader())
        .dataSource(owner)
        .locations(LOCATION)
        .schemas(SCHEMA)
        .defaultSchema(SCHEMA)
        .createSchemas(true)
        .validateOnMigrate(true)
        .validateMigrationNaming(true)
        .baselineOnMigrate(false)
        .cleanDisabled(true)
        .outOfOrder(false)
        .ignoreMigrationPatterns(new String[0])
        .failOnMissingLocations(true)
        .placeholderReplacement(false)
        .skipDefaultCallbacks(true)
        .loggers("slf4j")
        .load();
  }

  /**
   * Creates the monthly partitions of {@code alert_events} and {@code audit_logs} for the current
   * and the next {@value #PARTITION_MONTHS_AHEAD} months if missing (V001 partition rule; expired
   * months are dropped by the partition job of WO-28, not here).
   */
  static void ensurePartitions(DataSource owner) throws StartupPhaseException {
    String sql =
        "SELECT action, partition_name FROM argos.maintain_monthly_partitions(?::regclass, ?, NULL)";
    try (Connection c = owner.getConnection();
        PreparedStatement st = c.prepareStatement(sql)) {
      for (String table : List.of("argos.alert_events", "argos.audit_logs")) {
        st.setString(1, table);
        st.setInt(2, PARTITION_MONTHS_AHEAD);
        try (ResultSet rs = st.executeQuery()) {
          while (rs.next()) {
            LOG.info("PostgreSQL partition {}: {}", rs.getString(2), rs.getString(1));
          }
        }
      }
    } catch (SQLException e) {
      throw new StartupPhaseException(
          "PostgreSQL: cannot create the monthly partitions: " + MigrationException.oneLine(e, 300),
          e);
    }
  }

  /** The result of the last run (tests, start summary), or null. */
  public Result lastResult() {
    return lastResult;
  }

  /**
   * What one Flyway run did.
   *
   * @param schemaVersion schema version after the run
   * @param migrationsExecuted number of migrations applied
   * @param applied the applied migrations, in order
   */
  public record Result(String schemaVersion, int migrationsExecuted, List<Applied> applied) {

    public Result {
      applied = List.copyOf(applied);
    }
  }

  /**
   * One applied PostgreSQL migration.
   *
   * @param version Flyway version
   * @param description description from the file name
   * @param executionTimeMs execution time
   */
  public record Applied(String version, String description, int executionTimeMs) {}

  /**
   * The Flyway message reduced to its facts: one line, without the advice and web links Flyway
   * appends.
   */
  static String summary(FlywayException e) {
    String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    List<String> parts = new ArrayList<>();
    for (String line : message.lines().toList()) {
      String l = line.strip();
      String lower = l.toLowerCase(Locale.ROOT);
      if (l.isEmpty()
          || lower.contains("http://")
          || lower.contains("https://")
          || lower.startsWith("either ")
          || lower.startsWith("need more flexibility")
          || lower.startsWith("to ignore")
          || lower.startsWith("to fix")) {
        continue;
      }
      parts.add(l.replaceAll("\\s+", " "));
    }
    String text = String.join(" ", parts);
    return text.length() <= 400 ? text : text.substring(0, 397) + "...";
  }
}
