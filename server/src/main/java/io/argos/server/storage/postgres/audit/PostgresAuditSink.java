/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.postgres.audit;

import io.argos.server.audit.AuditRecord;
import io.argos.server.audit.AuditSink;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The PostgreSQL audit sink (12 §8.1 "PG 필수", 13 §12 {@code audit_logs}, DSN-12-13): one {@code
 * INSERT} per record through the runtime pool of {@code argos_app}, which may only insert into and
 * read {@code audit_logs} (V001 grants, SEC-82). The timestamp is the record's ({@link
 * AuditRecord#ts()}, stamped by the audit service), so the row and the other sinks' copies agree;
 * the monthly partitions exist from V001 and the partition job (WO-28).
 *
 * <p>Each insert is its own transaction (auto-commit), independent of the audited action. It is the
 * {@linkplain #primary() primary} sink: a failed insert sends the record to the fallback file.
 */
@Repository
public class PostgresAuditSink implements AuditSink {

  static final String INSERT =
      "INSERT INTO argos.audit_logs (ts, actor_type, actor_id, actor_name, ip, user_agent,"
          + " request_id, action, target_type, target_id, target_name, result, reason, before,"
          + " after, subject_user_id, subject_tx_id)"
          + " VALUES (?, ?, ?, ?, CAST(? AS inet), ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb),"
          + " CAST(? AS jsonb), ?, ?)";

  /** Seconds an insert may take before it counts as failed. */
  static final int QUERY_TIMEOUT_SECONDS = 5;

  private final JdbcTemplate jdbc;

  public PostgresAuditSink(DataSource dataSource) {
    this.jdbc = new JdbcTemplate(dataSource);
    this.jdbc.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
  }

  @Override
  public boolean primary() {
    return true;
  }

  @Override
  public void write(AuditRecord r) {
    jdbc.update(
        INSERT,
        ps -> {
          ps.setObject(1, OffsetDateTime.ofInstant(r.ts(), ZoneOffset.UTC));
          ps.setString(2, r.actorType());
          ps.setString(3, r.actorId());
          ps.setString(4, r.actorName());
          ps.setString(5, r.ip());
          ps.setString(6, r.userAgent());
          ps.setString(7, r.requestId());
          ps.setString(8, r.action());
          ps.setString(9, r.targetType());
          ps.setString(10, r.targetId());
          ps.setString(11, r.targetName());
          ps.setString(12, r.result());
          ps.setString(13, r.reason());
          ps.setString(14, r.beforeJson());
          ps.setString(15, r.afterJson());
          ps.setString(16, r.subjectUserId());
          if (r.subjectTxId() == null) {
            ps.setNull(17, Types.BIGINT);
          } else {
            ps.setLong(17, r.subjectTxId());
          }
        });
  }
}
