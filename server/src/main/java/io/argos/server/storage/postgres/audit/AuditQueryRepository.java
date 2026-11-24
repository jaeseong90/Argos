/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.postgres.audit;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Reads {@code audit_logs} for {@code GET /api/v1/admin/audit} and its export (14 §4.14, 16 §11.5)
 * through the runtime account {@code argos_app} (SELECT only besides INSERT, SEC-82): a [from, to)
 * range with optional exact filters, newest first by {@code (ts, id)}, keyset paging. Indexes: V001
 * {@code (ts)} and {@code (actor_id, ts)}, V013 {@code (action, ts, id)} and {@code (target_type,
 * target_id, ts, id)}.
 */
@Repository
public class AuditQueryRepository {

  /** Seconds a query may take. */
  public static final int QUERY_TIMEOUT_SECONDS = 10;

  private static final String COLUMNS =
      "id, ts, actor_type, actor_id, actor_name, host(ip), user_agent, request_id, action,"
          + " target_type, target_id, target_name, result, reason, before::text, after::text,"
          + " subject_user_id, subject_tx_id";

  private final JdbcTemplate jdbc;

  public AuditQueryRepository(DataSource dataSource) {
    this.jdbc = new JdbcTemplate(dataSource);
    this.jdbc.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
  }

  /**
   * The filters of a query.
   *
   * @param from range start (inclusive)
   * @param to range end (exclusive)
   * @param actor actor id or actor name (exact), or null
   * @param action event name (exact), or null
   * @param targetType target type (exact), or null
   * @param targetId target id (exact), or null
   */
  public record Filter(
      Instant from, Instant to, String actor, String action, String targetType, String targetId) {}

  /**
   * The position after the last row of a page.
   *
   * @param ts the row's time
   * @param id the row's id
   */
  public record Key(Instant ts, long id) {}

  /** One row ({@code ip} without mask, {@code before}/{@code after} as JSON text). */
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
      String after,
      String subjectUserId,
      Long subjectTxId) {

    /** The keyset position of this row. */
    public Key key() {
      return new Key(ts, id);
    }
  }

  /**
   * At most {@code limit} rows of {@code filter} after {@code after}, newest first.
   *
   * @param after the last row of the previous page, or null for the first page
   */
  public List<Row> find(Filter filter, Key after, int limit) {
    StringBuilder sql =
        new StringBuilder("SELECT ")
            .append(COLUMNS)
            .append(" FROM argos.audit_logs WHERE ts >= ? AND ts < ?");
    List<Object> args = new ArrayList<>();
    args.add(time(filter.from()));
    args.add(time(filter.to()));
    if (filter.actor() != null) {
      sql.append(" AND (actor_id = ? OR actor_name = ?)");
      args.add(filter.actor());
      args.add(filter.actor());
    }
    if (filter.action() != null) {
      sql.append(" AND action = ?");
      args.add(filter.action());
    }
    if (filter.targetType() != null) {
      sql.append(" AND target_type = ?");
      args.add(filter.targetType());
    }
    if (filter.targetId() != null) {
      sql.append(" AND target_id = ?");
      args.add(filter.targetId());
    }
    if (after != null) {
      sql.append(" AND (ts, id) < (?, ?)");
      args.add(time(after.ts()));
      args.add(after.id());
    }
    sql.append(" ORDER BY ts DESC, id DESC LIMIT ?");
    args.add(limit);
    return jdbc.query(sql.toString(), (rs, n) -> row(rs), args.toArray());
  }

  private static OffsetDateTime time(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  private static Row row(ResultSet rs) throws SQLException {
    Timestamp ts = rs.getTimestamp(2);
    long tx = rs.getLong(18);
    Long subjectTx = rs.wasNull() ? null : tx;
    return new Row(
        rs.getLong(1),
        ts.toInstant(),
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
        rs.getString(16),
        rs.getString(17),
        subjectTx);
  }
}
