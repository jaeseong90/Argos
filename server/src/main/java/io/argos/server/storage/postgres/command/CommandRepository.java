/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.postgres.command;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link CommandStore} on PostgreSQL ({@code argos.commands}, {@code argos.command_results}; V001,
 * V011). The result column holds the envelope string as ASCII bytes; nothing here sees a plaintext
 * result.
 */
@Repository
public class CommandRepository implements CommandStore {

  /** Seconds a statement may take. */
  static final int QUERY_TIMEOUT_SECONDS = 10;

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final TypeReference<LinkedHashMap<String, String>> ARGS = new TypeReference<>() {};

  private static final String COLUMNS =
      "c.id, c.instance_id, c.type, c.args::text, c.requested_by, c.requested_at, c.expires_at,"
          + " c.status, c.sent_at, c.finished_at, c.duration_ms, c.error_code, r.body_size,"
          + " r.truncated, r.content_type, u.username";

  /**
   * The command, its result's metadata and the requester's name (WO-37 {@code requested_by_name}):
   * a deleted user's row stays with the anonymised name {@code deleted-<id>} (C-231 (5)).
   */
  private static final String FROM =
      " FROM argos.commands c LEFT JOIN argos.command_results r ON r.command_id = c.id"
          + " LEFT JOIN argos.users u ON u.id = c.requested_by";

  private static final String INFLIGHT = "status IN ('" + QUEUED + "', '" + SENT + "')";

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;

  public CommandRepository(DataSource dataSource) {
    JdbcTemplate t = new JdbcTemplate(dataSource);
    t.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
    this.jdbc = t;
    TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    tx.setTimeout(QUERY_TIMEOUT_SECONDS);
    this.transactions = tx;
  }

  @Override
  public void insert(NewCommand c) {
    jdbc.update(
        "INSERT INTO argos.commands (id, instance_id, type, args, requested_by, requested_at,"
            + " expires_at, status) VALUES (?, ?, ?, ?::jsonb, ?, ?, ?, '"
            + QUEUED
            + "')",
        c.id(),
        c.instanceId(),
        c.type(),
        JSON.writeValueAsString(c.args()),
        c.requestedBy(),
        Timestamp.from(c.requestedAt()),
        Timestamp.from(c.expiresAt()));
  }

  @Override
  public boolean markSent(UUID id, Instant sentAt) {
    return jdbc.update(
            "UPDATE argos.commands SET status = '"
                + SENT
                + "', sent_at = ? WHERE id = ? AND status = '"
                + QUEUED
                + "'",
            Timestamp.from(sentAt),
            id)
        == 1;
  }

  @Override
  public boolean finish(
      UUID id, String status, String errorCode, Instant finishedAt, @Nullable Integer durationMs) {
    return finishRow(id, status, errorCode, finishedAt, durationMs);
  }

  private boolean finishRow(
      UUID id,
      String status,
      @Nullable String errorCode,
      Instant finishedAt,
      @Nullable Integer durationMs) {
    return jdbc.update(
            "UPDATE argos.commands SET status = ?, error_code = ?, finished_at = ?, duration_ms = ?"
                + " WHERE id = ? AND "
                + INFLIGHT,
            status,
            errorCode,
            Timestamp.from(finishedAt),
            durationMs,
            id)
        == 1;
  }

  @Override
  public boolean complete(
      UUID id, Instant finishedAt, @Nullable Integer durationMs, StoredResult result) {
    Boolean done =
        transactions.execute(
            s -> {
              if (!finishRow(id, DONE, null, finishedAt, durationMs)) {
                return false;
              }
              jdbc.update(
                  "INSERT INTO argos.command_results (command_id, body_enc, body_size, truncated,"
                      + " content_type, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                  id,
                  result.envelope().getBytes(StandardCharsets.US_ASCII),
                  result.size(),
                  result.truncated(),
                  result.contentType(),
                  Timestamp.from(finishedAt));
              return true;
            });
    return Boolean.TRUE.equals(done);
  }

  @Override
  public Optional<CommandRow> find(UUID id) {
    return jdbc.query("SELECT " + COLUMNS + FROM + " WHERE c.id = ?", ROW, id).stream().findFirst();
  }

  @Override
  public List<CommandRow> listByInstance(int instanceId, int limit) {
    return jdbc.query(
        "SELECT "
            + COLUMNS
            + FROM
            + " WHERE c.instance_id = ? ORDER BY c.requested_at DESC, c.id LIMIT ?",
        ROW,
        instanceId,
        limit);
  }

  @Override
  public Optional<StoredResult> result(UUID id) {
    return jdbc
        .query(
            "SELECT body_enc, body_size, truncated, content_type FROM argos.command_results"
                + " WHERE command_id = ?",
            (rs, n) ->
                new StoredResult(
                    new String(rs.getBytes(1), StandardCharsets.US_ASCII),
                    rs.getInt(2),
                    rs.getBoolean(3),
                    rs.getString(4)),
            id)
        .stream()
        .findFirst();
  }

  @Override
  public List<Expired> expireOverdue(Instant now, Instant sentBefore) {
    return jdbc.query(
        "UPDATE argos.commands SET status = '"
            + EXPIRED
            + "', error_code = CASE WHEN status = '"
            + QUEUED
            + "' THEN 'ttl' ELSE 'timeout' END, finished_at = ?"
            + " WHERE (status = '"
            + QUEUED
            + "' AND expires_at <= ?) OR (status = '"
            + SENT
            + "' AND sent_at <= ?) RETURNING id, instance_id, type, error_code",
        (rs, n) ->
            new Expired(
                rs.getObject(1, UUID.class), rs.getInt(2), rs.getString(3), rs.getString(4)),
        Timestamp.from(now),
        Timestamp.from(now),
        Timestamp.from(sentBefore));
  }

  @Override
  public int deleteOlderThan(Instant cutoff) {
    return jdbc.update(
        "DELETE FROM argos.commands WHERE requested_at < ? AND NOT " + INFLIGHT,
        Timestamp.from(cutoff));
  }

  @Override
  public int trimPerInstance(int keep) {
    return jdbc.update(
        "DELETE FROM argos.commands WHERE id IN (SELECT id FROM (SELECT id, status, row_number()"
            + " OVER (PARTITION BY instance_id ORDER BY requested_at DESC, id) AS rn FROM"
            + " argos.commands) t WHERE t.rn > ? AND t.status NOT IN ('"
            + QUEUED
            + "', '"
            + SENT
            + "'))",
        keep);
  }

  @Override
  public boolean instanceExists(int instanceId) {
    Boolean exists =
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM argos.instances WHERE id = ?)",
            Boolean.class,
            instanceId);
    return Boolean.TRUE.equals(exists);
  }

  private static final RowMapper<CommandRow> ROW =
      (ResultSet rs, int n) ->
          new CommandRow(
              rs.getObject(1, UUID.class),
              rs.getInt(2),
              rs.getString(3),
              args(rs.getString(4)),
              rs.getObject(5, UUID.class),
              instant(rs, 6),
              instant(rs, 7),
              rs.getString(8),
              instant(rs, 9),
              instant(rs, 10),
              (Integer) rs.getObject(11),
              rs.getString(12),
              (Integer) rs.getObject(13),
              (Boolean) rs.getObject(14),
              rs.getString(15),
              rs.getString(16));

  private static Map<String, String> args(@Nullable String json) {
    if (json == null || json.isBlank()) {
      return Map.of();
    }
    return JSON.readValue(json, ARGS);
  }

  private static @Nullable Instant instant(ResultSet rs, int column) throws SQLException {
    Timestamp t = rs.getTimestamp(column);
    return t == null ? null : t.toInstant();
  }
}
