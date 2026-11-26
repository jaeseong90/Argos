/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.postgres.secrets;

import io.argos.server.secrets.SecretField;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Reads the stored secret columns ({@link SecretField}) in row id order for {@code argos-admin
 * secrets verify} (WO-43): the JDBC side of the command (SRV-01), read only. The row id is the one
 * of the associated data ({@link SecretField#aad}), as the writers and {@code secrets rekey} build
 * it; rows are read in key set pages under the C collation.
 */
public final class StoredSecretScan {

  /** Seconds a statement may take. */
  private static final int QUERY_TIMEOUT_SECONDS = 300;

  /** One stored value. */
  @FunctionalInterface
  public interface RowVisitor {
    /**
     * Receives one stored value.
     *
     * @param rowId the row id of the associated data
     * @param stored the stored bytes (not null, not empty)
     */
    void row(String rowId, byte[] stored);
  }

  /** A failed statement; the message names the failure class and SQL state only. */
  public static final class ScanException extends Exception {

    private static final long serialVersionUID = 1L;

    ScanException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  private final JdbcTemplate jdbc;

  /** The scan over {@code dataSource} (the runtime account). */
  public StoredSecretScan(DataSource dataSource) {
    this.jdbc = new JdbcTemplate(dataSource);
    this.jdbc.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
  }

  /**
   * Whether the database has the column (a restored backup may be of an older schema version).
   *
   * @throws ScanException when the statement fails
   */
  public boolean present(SecretField field) throws ScanException {
    try {
      Long n =
          jdbc.queryForObject(
              "SELECT count(*) FROM information_schema.columns WHERE table_schema = 'argos'"
                  + " AND table_name = ? AND column_name = ?",
              Long.class,
              field.table(),
              field.column());
      return n != null && n > 0;
    } catch (DataAccessException e) {
      throw failure(e);
    }
  }

  /**
   * Visits every stored value of {@code field}, {@code batch} rows per statement.
   *
   * @throws ScanException when a statement fails
   */
  public void forEach(SecretField field, int batch, RowVisitor visitor) throws ScanException {
    String sql = select(field);
    String[] after = {""};
    try {
      while (true) {
        Integer read =
            jdbc.execute(
                (Connection c) -> {
                  try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                    ps.setString(1, after[0]);
                    ps.setInt(2, batch);
                    int n = 0;
                    try (ResultSet rs = ps.executeQuery()) {
                      while (rs.next()) {
                        n++;
                        String rowId = rs.getString(1);
                        after[0] = rowId;
                        visitor.row(rowId, rs.getBytes(2));
                      }
                    }
                    return n;
                  }
                });
        if (read == null || read < batch) {
          return;
        }
      }
    } catch (DataAccessException e) {
      throw failure(e);
    }
  }

  /**
   * The stored values of {@code field} after a row id, in row id order (parameters: the last row
   * id, the page size).
   */
  public static String select(SecretField field) {
    String key =
        switch (field) {
          case CHANNEL_CONFIG -> "id::text";
          case WEBHOOK_SIGNING_KEY -> "(channel_id::text || '/' || key_id)";
          case COMMAND_RESULT -> "command_id::text";
          case APP_USER_ID_SALT -> "id::text";
        };
    String value = field.column();
    return "SELECT k, v FROM (SELECT "
        + key
        + " AS k, "
        + value
        + " AS v FROM argos."
        + field.table()
        + " WHERE "
        + value
        + " IS NOT NULL AND octet_length("
        + value
        + ") > 0) s WHERE k COLLATE \"C\" > ? ORDER BY k COLLATE \"C\" LIMIT ?";
  }

  /** The failure class and SQL state (the message may quote a statement). */
  private static ScanException failure(DataAccessException e) {
    Throwable cause = e.getMostSpecificCause();
    String state =
        cause instanceof SQLException s && s.getSQLState() != null ? " " + s.getSQLState() : "";
    return new ScanException("PostgreSQL: " + cause.getClass().getSimpleName() + state, e);
  }
}
