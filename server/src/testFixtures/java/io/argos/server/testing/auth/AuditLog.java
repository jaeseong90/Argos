/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.testing.auth;

import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads {@code audit_logs} of the server under test (as {@code argos_app}, which may read it) for
 * assertions on audit rows (16 §11, SEC-80).
 */
public final class AuditLog {

  private static final String COLUMNS =
      "id, ts, actor_type, actor_id, actor_name, host(ip), user_agent, request_id, action,"
          + " target_type, target_id, target_name, result, reason, before::text, after::text";

  private AuditLog() {}

  /**
   * One {@code audit_logs} row.
   *
   * @param ip the address without mask ({@code host(ip)}), or null
   * @param before {@code before} as JSON text, or null
   * @param after {@code after} as JSON text, or null
   */
  public record Row(
      long id,
      Instant ts,
      String actorType,
      String actorId,
      String actorName,
      String ip,
      String userAgent,
      String requestId,
      String action,
      String targetType,
      String targetId,
      String targetName,
      String result,
      String reason,
      String before,
      String after) {}

  /** Every row, oldest first. */
  public static List<Row> all(ServerTestEnvironment env) {
    return query(env, "SELECT " + COLUMNS + " FROM argos.audit_logs ORDER BY id", null);
  }

  /** The rows of one action (an {@code AuditEvent} name), oldest first. */
  public static List<Row> rows(ServerTestEnvironment env, String action) {
    return query(
        env, "SELECT " + COLUMNS + " FROM argos.audit_logs WHERE action = ? ORDER BY id", action);
  }

  /** The rows of one request id, oldest first. */
  public static List<Row> ofRequest(ServerTestEnvironment env, String requestId) {
    return query(
        env,
        "SELECT " + COLUMNS + " FROM argos.audit_logs WHERE request_id = ? ORDER BY id",
        requestId);
  }

  private static List<Row> query(ServerTestEnvironment env, String sql, String parameter) {
    try (Connection c = env.connect(PgAccount.APP);
        PreparedStatement select = c.prepareStatement(sql)) {
      if (parameter != null) {
        select.setString(1, parameter);
      }
      List<Row> rows = new ArrayList<>();
      try (ResultSet rs = select.executeQuery()) {
        while (rs.next()) {
          rows.add(
              new Row(
                  rs.getLong(1),
                  rs.getTimestamp(2).toInstant(),
                  rs.getString(3),
                  rs.getString(4),
                  rs.getString(5),
                  rs.getString(6),
                  rs.getString(7),
                  rs.getString(8),
                  rs.getString(9),
                  rs.getString(10),
                  rs.getString(11),
                  rs.getString(12),
                  rs.getString(13),
                  rs.getString(14),
                  rs.getString(15),
                  rs.getString(16)));
        }
      }
      return rows;
    } catch (SQLException e) {
      throw new IllegalStateException("Cannot read audit_logs", e);
    }
  }
}
