/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.migration;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.query.QueryResponse;
import com.clickhouse.client.api.query.QuerySettings;
import com.clickhouse.data.ClickHouseFormat;
import com.zaxxer.hikari.HikariDataSource;
import io.argos.server.bootstrap.PhaseOrder;
import io.argos.server.bootstrap.StartupPhase;
import io.argos.server.config.ArgosProperties;
import io.argos.server.storage.clickhouse.ClickHouseClients;
import io.argos.server.storage.postgres.PostgresDataSources;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

/**
 * Start step {@code id-sequence-guard} (ADR-0010 C-259 (1), T-CH-008): the PostgreSQL sequences of
 * {@code apps.id} and {@code instances.id} never hand out an id that ClickHouse still holds rows
 * for. An empty PostgreSQL database next to an earlier ClickHouse (the database recreated, a
 * PostgreSQL backup older than the ClickHouse data) numbers from 1 again, and a new app or instance
 * would show the rows of one that is gone.
 *
 * <p>The step reads the largest app id and instance id of the hourly rollups (the longest-kept
 * tables; every raw row reaches them through the cascading materialized views, so the raw tables
 * are not scanned) with the reader account, and moves a sequence with {@code setval} only when its
 * next value would be one of those ids: the same rule as {@code deploy/compose/restore.sh} (C-247
 * (7)), which stays as a check before the server starts. One INFO line per moved sequence. A
 * ClickHouse read failure logs one WARN and the start continues (the rows of ClickHouse stay where
 * they are; the next start checks again); a PostgreSQL failure does the same.
 *
 * <p>Order: {@code LISTENERS}, before every other step of that phase ({@code setup-token}, the app
 * bootstrap of {@code argos.bootstrap.apps-file} and the gRPC listener), so it runs after both
 * migrations and the WAL replay (whose rows reach the rollups synchronously) and before any app is
 * created or any agent registers. The sequences need {@code UPDATE} for {@code setval}, which the
 * runtime account does not have: the step uses the schema owner, like the migrations, and closes
 * the connection at the end.
 */
@Component
public class IdSequenceGuardPhase implements StartupPhase, Ordered {

  /** Before {@code setup-token} (highest precedence by name) and the app bootstrap. */
  static final int ORDER = Ordered.HIGHEST_PRECEDENCE;

  /** Upper bound of the ClickHouse read. */
  static final Duration READ_TIMEOUT = Duration.ofSeconds(20);

  /**
   * {@code max_execution_time} of the read: the reader account's profile allows at most 10 s
   * ({@code deploy/clickhouse/users.d/argos.xml} constraint); the five rollup maxima take far less.
   */
  static final int MAX_EXECUTION_SECONDS = 10;

  private static final Logger LOG = LoggerFactory.getLogger(IdSequenceGuardPhase.class);

  private final ArgosProperties properties;

  public IdSequenceGuardPhase(ArgosProperties properties) {
    this.properties = properties;
  }

  @Override
  public PhaseOrder order() {
    return PhaseOrder.LISTENERS;
  }

  @Override
  public String name() {
    return "id-sequence-guard";
  }

  @Override
  public int getOrder() {
    return ORDER;
  }

  @Override
  public void run() {
    guard(properties);
  }

  /**
   * Runs the check once.
   *
   * @return what was done; never throws for a database failure
   */
  public static Outcome guard(ArgosProperties properties) {
    MaxIds ids;
    try {
      ids = readClickHouse(properties);
    } catch (RuntimeException e) {
      String reason = MigrationException.oneLine(e, 300);
      LOG.warn(
          "Could not read the largest app and instance ids of ClickHouse, the id sequences were"
              + " not checked: {}. If ClickHouse holds rows of apps or instances that PostgreSQL"
              + " no longer has, a new app or instance may show them; the next start checks again",
          reason);
      return Outcome.failed("clickhouse: " + reason);
    }
    HikariDataSource owner;
    try {
      owner = PostgresDataSources.openOwner(properties.postgres());
    } catch (RuntimeException e) {
      String reason = MigrationException.oneLine(e, 300);
      LOG.warn("Could not check the app and instance id sequences: {}", reason);
      return Outcome.failed("postgres: " + reason);
    }
    try (owner) {
      return apply(owner, ids);
    } catch (SQLException e) {
      String reason = MigrationException.oneLine(e, 300);
      LOG.warn(
          "Could not move the app and instance id sequences past the ids of ClickHouse (apps {},"
              + " instances {}): {}",
          ids.appId(),
          ids.instanceId(),
          reason);
      return Outcome.failed("postgres: " + reason);
    }
  }

  /** The largest ids of the hourly rollups (0 when ClickHouse holds no rows). */
  static MaxIds readClickHouse(ArgosProperties properties) {
    SchemaParameters schema = SchemaParameters.from(properties);
    String sql =
        "SELECT greatest((SELECT max(app_id) FROM "
            + schema.table("app_stat_1h")
            + "), (SELECT max(app_id) FROM "
            + schema.table("metric_1h")
            + "), (SELECT max(app_id) FROM "
            + schema.table("sql_stat_1h")
            + "), (SELECT max(app_id) FROM "
            + schema.table("ext_stat_1h")
            + ")), greatest((SELECT max(instance_id) FROM "
            + schema.table("instance_stat_1h")
            + "), (SELECT max(instance_id) FROM "
            + schema.table("metric_1h")
            + "))";
    try (Client client =
        ClickHouseClients.open(
            properties.clickhouse(),
            ClickHouseClients.Account.READER,
            READ_TIMEOUT,
            READ_TIMEOUT.plusSeconds(5))) {
      QuerySettings settings =
          new QuerySettings()
              .setFormat(ClickHouseFormat.TabSeparated)
              .setMaxExecutionTime(MAX_EXECUTION_SECONDS);
      String text;
      QueryResponse response = null;
      try {
        response = client.query(sql, settings).get(READ_TIMEOUT.toSeconds() + 5, TimeUnit.SECONDS);
        try (InputStream in = response.getInputStream()) {
          text = new String(in.readNBytes(256), StandardCharsets.UTF_8).strip();
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("interrupted", e);
      } catch (Exception e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        throw new IllegalStateException(
            cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage(), e);
      } finally {
        if (response != null) {
          try {
            response.close();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } catch (Exception e) {
            // the answer was read or the failure is reported; nothing to add
          }
        }
      }
      return MaxIds.parse(text);
    }
  }

  /**
   * Moves each sequence whose next value would be at most the matching ClickHouse id to that id
   * ({@code is_called = true}: the next value is the id + 1). Logs one INFO line per moved
   * sequence.
   */
  static Outcome apply(DataSource owner, MaxIds ids) throws SQLException {
    List<String> moved = new ArrayList<>();
    try (Connection c = owner.getConnection()) {
      c.setAutoCommit(true);
      move(c, "argos.apps_id_seq", "app", ids.appId(), moved);
      move(c, "argos.instances_id_seq", "instance", ids.instanceId(), moved);
    }
    return new Outcome(true, moved, "");
  }

  private static void move(
      Connection c, String sequence, String what, long chMax, List<String> moved)
      throws SQLException {
    if (chMax <= 0) {
      return;
    }
    String sql =
        "SELECT setval('"
            + sequence
            + "', ?, true) FROM "
            + sequence
            + " WHERE ? >= CASE WHEN is_called THEN last_value + 1 ELSE last_value END";
    try (PreparedStatement st = c.prepareStatement(sql)) {
      st.setLong(1, chMax);
      st.setLong(2, chMax);
      try (ResultSet rs = st.executeQuery()) {
        if (rs.next()) {
          moved.add(sequence);
          LOG.info(
              "The {} id sequence now continues after {}: ClickHouse holds rows of {} ids up to"
                  + " {}, which are not handed out again (ADR-0010 C-259 (1))",
              what,
              rs.getLong(1),
              what,
              chMax);
        }
      }
    }
  }

  /**
   * The largest ids of ClickHouse.
   *
   * @param appId largest {@code app_id}, 0 without rows
   * @param instanceId largest {@code instance_id}, 0 without rows
   */
  record MaxIds(long appId, long instanceId) {

    static MaxIds parse(String tsv) {
      String[] cells = tsv.split("\t", -1);
      if (cells.length != 2) {
        throw new IllegalStateException("unexpected answer: " + abbreviate(tsv));
      }
      try {
        return new MaxIds(Long.parseLong(cells[0].strip()), Long.parseLong(cells[1].strip()));
      } catch (NumberFormatException e) {
        throw new IllegalStateException("unexpected answer: " + abbreviate(tsv), e);
      }
    }

    private static String abbreviate(String s) {
      String one = s.replaceAll("\\s+", " ");
      return one.length() <= 80 ? one : one.substring(0, 77) + "...";
    }
  }

  /**
   * What one check did.
   *
   * @param checked both databases answered
   * @param moved the moved sequences, e.g. {@code argos.instances_id_seq}
   * @param failure why the check did not run, empty when it ran
   */
  public record Outcome(boolean checked, List<String> moved, String failure) {

    public Outcome {
      moved = List.copyOf(moved);
    }

    static Outcome failed(String failure) {
      return new Outcome(false, List.of(), failure);
    }
  }
}
