/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link MigrationStore} on one PostgreSQL connection of the schema owner (autocommit). The lock is
 * a session-level advisory lock, so a second server that starts against the same databases waits
 * until the first one has finished its ClickHouse migration.
 */
final class PostgresMigrationStore implements MigrationStore {

  /** Advisory lock key of the ClickHouse migration ("ARGOSCH1"). */
  static final long LOCK_KEY = 0x4152474f53434831L;

  private static final Logger LOG = LoggerFactory.getLogger(PostgresMigrationStore.class);

  private final Connection connection;

  PostgresMigrationStore(Connection connection) {
    this.connection = connection;
  }

  @Override
  public void lock() throws MigrationException {
    try (PreparedStatement st = connection.prepareStatement("SELECT pg_advisory_lock(?)")) {
      st.setLong(1, LOCK_KEY);
      st.execute();
    } catch (SQLException e) {
      throw failure("take the ClickHouse migration lock", e);
    }
  }

  @Override
  public void unlock() {
    try (PreparedStatement st = connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
      st.setLong(1, LOCK_KEY);
      st.execute();
    } catch (SQLException e) {
      // The lock is released with the session anyway (the connection is closed after the phase).
      LOG.debug("Cannot release the ClickHouse migration lock: {}", e.getMessage());
    }
  }

  @Override
  public List<HistoryEntry> history() throws MigrationException {
    String sql =
        "SELECT version, description, checksum, applied_at, duration_ms, success, error"
            + " FROM argos.ch_schema_history ORDER BY version";
    List<HistoryEntry> entries = new ArrayList<>();
    try (PreparedStatement st = connection.prepareStatement(sql);
        ResultSet rs = st.executeQuery()) {
      while (rs.next()) {
        String version = rs.getString(1);
        String description = rs.getString(2);
        String checksum = rs.getString(3);
        Timestamp applied = rs.getTimestamp(4);
        int duration = rs.getInt(5);
        Integer durationMs = rs.wasNull() ? null : duration;
        entries.add(
            new HistoryEntry(
                version,
                description,
                checksum,
                applied == null ? null : applied.toInstant(),
                durationMs,
                rs.getBoolean(6),
                rs.getString(7)));
      }
    } catch (SQLException e) {
      throw failure("read argos.ch_schema_history", e);
    }
    return entries;
  }

  @Override
  public void record(
      String version,
      String description,
      String checksum,
      long durationMs,
      boolean success,
      String error)
      throws MigrationException {
    String sql =
        "INSERT INTO argos.ch_schema_history"
            + " (version, description, checksum, applied_at, duration_ms, success, error)"
            + " VALUES (?, ?, ?, now(), ?, ?, ?)"
            + " ON CONFLICT (version) DO UPDATE SET description = EXCLUDED.description,"
            + " checksum = EXCLUDED.checksum, applied_at = EXCLUDED.applied_at,"
            + " duration_ms = EXCLUDED.duration_ms, success = EXCLUDED.success,"
            + " error = EXCLUDED.error";
    try (PreparedStatement st = connection.prepareStatement(sql)) {
      st.setString(1, version);
      st.setString(2, truncate(description, HistoryEntry.MAX_DESCRIPTION));
      st.setString(3, checksum);
      st.setInt(4, (int) Math.min(Integer.MAX_VALUE, durationMs));
      st.setBoolean(5, success);
      st.setString(6, error == null ? null : truncate(error, HistoryEntry.MAX_ERROR));
      st.executeUpdate();
    } catch (SQLException e) {
      throw failure("write argos.ch_schema_history (" + version + ")", e);
    }
  }

  @Override
  public int updateChecksum(String version, String checksum) throws MigrationException {
    String sql =
        "UPDATE argos.ch_schema_history SET checksum = ? WHERE version = ? OR version LIKE ?";
    try (PreparedStatement st = connection.prepareStatement(sql)) {
      st.setString(1, checksum);
      st.setString(2, version);
      st.setString(3, version + "/%");
      return st.executeUpdate();
    } catch (SQLException e) {
      throw failure("update argos.ch_schema_history (" + version + ")", e);
    }
  }

  @Override
  public void setRollupAlias(String alias, String table) throws MigrationException {
    String sql =
        "INSERT INTO argos.settings (key, value, updated_at)"
            + " VALUES ('rollup_alias', jsonb_build_object(?::text, ?::text), now())"
            + " ON CONFLICT (key) DO UPDATE SET value = argos.settings.value || EXCLUDED.value,"
            + " updated_at = now()";
    try (PreparedStatement st = connection.prepareStatement(sql)) {
      st.setString(1, alias);
      st.setString(2, table);
      st.executeUpdate();
    } catch (SQLException e) {
      throw failure("write argos.settings rollup_alias", e);
    }
  }

  private static String truncate(String text, int max) {
    return text.length() <= max ? text : text.substring(0, max - 3) + "...";
  }

  private static MigrationException failure(String what, SQLException e) {
    return new MigrationException(
        "PostgreSQL: cannot " + what + ": " + MigrationException.oneLine(e, 300), e);
  }
}
