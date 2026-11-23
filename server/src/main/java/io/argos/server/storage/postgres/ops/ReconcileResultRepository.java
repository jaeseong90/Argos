/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.postgres.ops;

import io.argos.server.storage.postgres.ops.AdminDatabase.AdminDatabaseException;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * {@code argos.reconcile_results} (PostgreSQL V005, WO-27): the rows of the nightly consistency
 * check and of {@code argos-admin consistency check}, on the connection of an {@link AdminDatabase}
 * ({@link AdminDatabase#reconcileResults()}). {@code argos_app} may insert and read them only.
 */
public final class ReconcileResultRepository {

  /** Most rows of one run (one summary row and one row per app, check and table). */
  public static final int MAX_ROWS = 20_000;

  private static final String INSERT =
      "INSERT INTO argos.reconcile_results (day, app_id, check_name, table_name, mismatch_minutes,"
          + " rebuilt, rebuild_result, checked_at, detail) VALUES (?, ?, ?, ?, ?, ?, ?, ?,"
          + " CAST(? AS jsonb))";

  private static final String SCHEDULED_RUN =
      "SELECT 1 FROM argos.reconcile_results WHERE day = ? AND check_name = 'summary'"
          + " AND rebuild_result <> 'failed' AND detail->>'trigger' = 'scheduled' LIMIT 1";

  private final Connection connection;

  ReconcileResultRepository(Connection connection) {
    this.connection = connection;
  }

  /**
   * One row.
   *
   * @param day the checked UTC day
   * @param appId the application, null for the summary row
   * @param checkName {@code summary}, {@code d03}, {@code rollup_1m}, {@code rollup_1h}, {@code
   *     d12}
   * @param tableName the checked table, {@code *} for the summary
   * @param mismatchMinutes minutes that did not match on the first check
   * @param rebuilt the day was rebuilt for the table
   * @param rebuildResult {@code clean}, {@code repaired}, {@code mismatch}, {@code failed}, {@code
   *     not_rebuilt}, {@code record_only}
   * @param checkedAt end of the run
   * @param detailJson a JSON object
   */
  public record Row(
      LocalDate day,
      @Nullable Integer appId,
      String checkName,
      String tableName,
      int mismatchMinutes,
      boolean rebuilt,
      String rebuildResult,
      Instant checkedAt,
      String detailJson) {

    public Row {
      Objects.requireNonNull(day, "day");
      Objects.requireNonNull(checkName, "checkName");
      Objects.requireNonNull(tableName, "tableName");
      Objects.requireNonNull(rebuildResult, "rebuildResult");
      Objects.requireNonNull(checkedAt, "checkedAt");
      Objects.requireNonNull(detailJson, "detailJson");
      if (checkName.length() > 32 || tableName.length() > 64 || rebuildResult.length() > 16) {
        throw new IllegalArgumentException("check_name, table_name or rebuild_result too long");
      }
      if (mismatchMinutes < 0) {
        throw new IllegalArgumentException("mismatchMinutes must not be negative");
      }
    }
  }

  /**
   * Inserts the rows of one run in one transaction.
   *
   * @throws IllegalArgumentException for more than {@link #MAX_ROWS} rows
   */
  public void insert(List<Row> rows) throws AdminDatabaseException {
    if (rows.size() > MAX_ROWS) {
      throw new IllegalArgumentException("At most " + MAX_ROWS + " rows per run");
    }
    if (rows.isEmpty()) {
      return;
    }
    try {
      connection.setAutoCommit(false);
      try (PreparedStatement st = connection.prepareStatement(INSERT)) {
        for (Row row : rows) {
          st.setDate(1, Date.valueOf(row.day()));
          if (row.appId() == null) {
            st.setNull(2, Types.INTEGER);
          } else {
            st.setInt(2, row.appId());
          }
          st.setString(3, row.checkName());
          st.setString(4, row.tableName());
          st.setInt(5, row.mismatchMinutes());
          st.setBoolean(6, row.rebuilt());
          st.setString(7, row.rebuildResult());
          st.setTimestamp(8, Timestamp.from(row.checkedAt()));
          st.setString(9, row.detailJson());
          st.addBatch();
        }
        st.executeBatch();
      }
      connection.commit();
    } catch (SQLException e) {
      try {
        connection.rollback();
      } catch (SQLException ignored) {
        // The connection is closed with the command or job; nothing else to undo.
      }
      throw new AdminDatabaseException(
          "PostgreSQL: insert argos.reconcile_results: " + AdminDatabase.oneLine(e), e);
    } finally {
      try {
        connection.setAutoCommit(true);
      } catch (SQLException ignored) {
        // Next statement reports a broken connection.
      }
    }
  }

  /**
   * True when a scheduled run already recorded its summary row for {@code day} (a run that failed
   * before checking does not count: the next node or start may retry).
   */
  public boolean scheduledRunRecorded(LocalDate day) throws AdminDatabaseException {
    try (PreparedStatement st = connection.prepareStatement(SCHEDULED_RUN)) {
      st.setDate(1, Date.valueOf(day));
      try (ResultSet rs = st.executeQuery()) {
        return rs.next();
      }
    } catch (SQLException e) {
      throw new AdminDatabaseException(
          "PostgreSQL: read argos.reconcile_results: " + AdminDatabase.oneLine(e), e);
    }
  }
}
