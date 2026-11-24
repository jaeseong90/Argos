/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.postgres.privacyjob;

import io.argos.server.storage.postgres.privacyjob.PrivacyJob.Status;
import io.argos.server.storage.postgres.privacyjob.PrivacyJob.TableMutation;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code argos.privacy_jobs} (V001, V014) with the runtime account: the privacy deletion jobs of
 * {@code POST /api/v1/admin/privacy/deletions} (13 §9). No subject value is ever written: the
 * caller passes its SHA-256.
 */
@Repository
public class PrivacyJobRepository {

  /** Seconds a statement may take. */
  static final int QUERY_TIMEOUT_SECONDS = 10;

  /** Most application ids read for a deletion over every application. */
  public static final int MAX_APPS = 2_000;

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static final String COLUMNS =
      "id, subject_kind, subject_hash, app_id, from_ts, to_ts, status, created_by, created_at,"
          + " started_at, finished_at, rows_affected, tables::text, warnings, error";

  private final JdbcTemplate jdbc;

  public PrivacyJobRepository(DataSource dataSource) {
    JdbcTemplate t = new JdbcTemplate(dataSource);
    t.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
    this.jdbc = t;
  }

  /** Inserts a new queued job. */
  public void insert(PrivacyJob job) {
    jdbc.update(
        "INSERT INTO argos.privacy_jobs (id, subject_kind, subject_hash, app_id, from_ts, to_ts,"
            + " status, created_by, created_at, warnings) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        ps -> {
          ps.setObject(1, job.id());
          ps.setString(2, job.subjectKind());
          ps.setString(3, job.subjectHash());
          ps.setObject(4, job.appId());
          ps.setTimestamp(5, Timestamp.from(job.from()));
          ps.setTimestamp(6, Timestamp.from(job.to()));
          ps.setString(7, job.status().value());
          ps.setObject(8, job.createdBy());
          ps.setTimestamp(9, Timestamp.from(job.createdAt()));
          ps.setArray(10, ps.getConnection().createArrayOf("text", job.warnings().toArray()));
        });
  }

  /** The job, or empty. */
  public Optional<PrivacyJob> find(UUID id) {
    return jdbc
        .query("SELECT " + COLUMNS + " FROM argos.privacy_jobs WHERE id = ?", MAPPER, id)
        .stream()
        .findFirst();
  }

  /** The queued and running jobs, oldest first. */
  public List<PrivacyJob> unfinished() {
    return jdbc.query(
        "SELECT "
            + COLUMNS
            + " FROM argos.privacy_jobs WHERE status IN ('queued', 'running')"
            + " ORDER BY created_at, id",
        MAPPER);
  }

  /** Number of jobs by state. */
  public Map<Status, Long> countByStatus() {
    Map<Status, Long> out = new EnumMap<>(Status.class);
    for (Status s : Status.values()) {
      out.put(s, 0L);
    }
    jdbc.query(
        "SELECT status, count(*) FROM argos.privacy_jobs GROUP BY status",
        rs -> {
          out.put(Status.of(rs.getString(1)), rs.getLong(2));
        });
    return out;
  }

  /** Marks a job running. */
  public void markRunning(UUID id, Instant startedAt) {
    jdbc.update(
        "UPDATE argos.privacy_jobs SET status = 'running', started_at = ? WHERE id = ?",
        Timestamp.from(startedAt),
        id);
  }

  /**
   * Stores the issued mutations, the warnings and (once every mutation is issued) the matching
   * rows.
   */
  public void progress(
      UUID id, List<TableMutation> tables, List<String> warnings, Long rowsAffected) {
    jdbc.update(
        "UPDATE argos.privacy_jobs SET tables = CAST(? AS jsonb), warnings = ?, rows_affected = ?,"
            + " mutation_ids = ? WHERE id = ?",
        ps -> {
          ps.setString(1, tablesJson(tables));
          ps.setArray(2, ps.getConnection().createArrayOf("text", warnings.toArray()));
          ps.setObject(3, rowsAffected);
          ps.setArray(
              4,
              ps.getConnection()
                  .createArrayOf("text", tables.stream().map(TableMutation::mutationId).toArray()));
          ps.setObject(5, id);
        });
  }

  /** Ends a job. */
  public void finish(UUID id, Status status, Instant finishedAt, String error) {
    jdbc.update(
        "UPDATE argos.privacy_jobs SET status = ?, finished_at = ?, error = ? WHERE id = ?",
        status.value(),
        Timestamp.from(finishedAt),
        error,
        id);
  }

  /** Every application id (deleted ones included: their rows stay until the TTL), at most 2,000. */
  public List<Integer> appIds() {
    return jdbc.queryForList(
        "SELECT id FROM argos.apps ORDER BY id LIMIT " + MAX_APPS, Integer.class);
  }

  /** Whether the application exists (deleted ones included). */
  public boolean appExists(int appId) {
    Integer n =
        jdbc.queryForObject("SELECT count(*) FROM argos.apps WHERE id = ?", Integer.class, appId);
    return n != null && n > 0;
  }

  static String tablesJson(List<TableMutation> tables) {
    ArrayNode array = JSON.createArrayNode();
    for (TableMutation t : tables) {
      ObjectNode o = array.addObject();
      o.put("name", t.name());
      o.put("partition", t.partition());
      o.put("mutation_id", t.mutationId());
      o.put("done", t.done());
    }
    return JSON.writeValueAsString(array);
  }

  static List<TableMutation> tables(String json) {
    List<TableMutation> out = new ArrayList<>();
    if (json == null) {
      return out;
    }
    JsonNode array = JSON.readTree(json);
    for (JsonNode o : array) {
      out.add(
          new TableMutation(
              o.path("name").asString(""),
              o.path("partition").asString(""),
              o.path("mutation_id").asString(""),
              o.path("done").asBoolean(false)));
    }
    return out;
  }

  private static final RowMapper<PrivacyJob> MAPPER =
      (ResultSet rs, int row) ->
          new PrivacyJob(
              rs.getObject(1, UUID.class),
              rs.getString(2),
              rs.getString(3),
              (Integer) rs.getObject(4),
              rs.getTimestamp(5).toInstant(),
              rs.getTimestamp(6).toInstant(),
              Status.of(rs.getString(7)),
              rs.getObject(8, UUID.class),
              rs.getTimestamp(9).toInstant(),
              instant(rs.getTimestamp(10)),
              instant(rs.getTimestamp(11)),
              (Long) rs.getObject(12),
              tables(rs.getString(13)),
              strings(rs.getArray(14)),
              rs.getString(15));

  private static Instant instant(Timestamp t) {
    return t == null ? null : t.toInstant();
  }

  private static List<String> strings(Array array) throws SQLException {
    if (array == null) {
      return List.of();
    }
    Object[] values = (Object[]) array.getArray();
    List<String> out = new ArrayList<>(values.length);
    for (Object v : values) {
      out.add(String.valueOf(v));
    }
    return out;
  }
}
