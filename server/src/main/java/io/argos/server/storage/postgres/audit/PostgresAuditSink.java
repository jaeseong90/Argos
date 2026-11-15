/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.postgres.audit;

import io.argos.server.audit.AuditRecord;
import io.argos.server.audit.AuditSink;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The PostgreSQL audit sink (12 §8.1 "PG 필수", 13 §12 {@code audit_logs}, DSN-12-13): one {@code
 * INSERT} per record through the runtime pool of {@code argos_app}, which may only insert into and
 * read {@code audit_logs} (V001 grants, SEC-82). The timestamp is the database's {@code now()}; the
 * monthly partition of the current month exists from V001 and the partition job (WO-28).
 *
 * <p>Each insert is its own transaction (auto-commit), independent of the audited action.
 */
@Repository
public class PostgresAuditSink implements AuditSink {

  static final String INSERT =
      "INSERT INTO argos.audit_logs (actor_type, actor_id, actor_name, ip, user_agent, request_id,"
          + " action, target_type, target_id, target_name, result, reason, before, after)"
          + " VALUES (?, ?, ?, CAST(? AS inet), ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb),"
          + " CAST(? AS jsonb))";

  /** Seconds an insert may take before it counts as failed. */
  static final int QUERY_TIMEOUT_SECONDS = 5;

  private final JdbcTemplate jdbc;

  public PostgresAuditSink(DataSource dataSource) {
    this.jdbc = new JdbcTemplate(dataSource);
    this.jdbc.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
  }

  @Override
  public void write(AuditRecord r) {
    jdbc.update(
        INSERT,
        r.actorType(),
        r.actorId(),
        r.actorName(),
        r.ip(),
        r.userAgent(),
        r.requestId(),
        r.action(),
        r.targetType(),
        r.targetId(),
        r.targetName(),
        r.result(),
        r.reason(),
        r.beforeJson(),
        r.afterJson());
  }
}
