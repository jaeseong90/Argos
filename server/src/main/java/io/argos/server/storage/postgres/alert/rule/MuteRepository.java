/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.postgres.alert.rule;

import static io.argos.server.storage.postgres.alert.rule.AlertRuleRepository.instant;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code mutes} (PostgreSQL V007; 14 §4.13 {@code /alerts/mutes}): one-off mutes of the alert
 * engine (WO-25).
 */
@Repository
public class MuteRepository {

  /** Mutes a list returns at most. */
  public static final int MAX_LIST = 1_000;

  private static final String COLUMNS =
      "id, app_id, env, instance_id, rule_id, starts_at, ends_at, reason, created_by, created_at";

  private final JdbcTemplate jdbc;

  public MuteRepository(DataSource dataSource) {
    JdbcTemplate t = new JdbcTemplate(dataSource);
    t.setQueryTimeout(AlertRuleRepository.QUERY_TIMEOUT_SECONDS);
    this.jdbc = t;
  }

  /** Inserts a mute. */
  public void insert(MuteRow m) {
    jdbc.update(
        "INSERT INTO argos.mutes (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, now())",
        m.id(),
        m.appId(),
        m.env(),
        m.instanceId(),
        m.ruleId(),
        Timestamp.from(m.startsAt()),
        Timestamp.from(m.endsAt()),
        m.reason(),
        m.createdBy());
  }

  /** Deletes a mute; false when it does not exist. */
  public boolean delete(UUID id) {
    return jdbc.update("DELETE FROM argos.mutes WHERE id = ?", id) > 0;
  }

  /** The mutes that end after {@code now}, by start, at most {@link #MAX_LIST}. */
  public List<MuteRow> current(Instant now) {
    return jdbc.query(
        "SELECT "
            + COLUMNS
            + " FROM argos.mutes WHERE ends_at > ? ORDER BY starts_at, id LIMIT "
            + MAX_LIST,
        MuteRepository::row,
        Timestamp.from(now));
  }

  /** The mute {@code id}. */
  public List<MuteRow> find(UUID id) {
    return jdbc.query(
        "SELECT " + COLUMNS + " FROM argos.mutes WHERE id = ?", MuteRepository::row, id);
  }

  private static MuteRow row(ResultSet rs, int i) throws SQLException {
    int app = rs.getInt(2);
    Integer appId = rs.wasNull() ? null : app;
    int inst = rs.getInt(4);
    Integer instanceId = rs.wasNull() ? null : inst;
    return new MuteRow(
        rs.getObject(1, UUID.class),
        appId,
        rs.getString(3),
        instanceId,
        rs.getObject(5, UUID.class),
        instant(rs.getTimestamp(6)),
        instant(rs.getTimestamp(7)),
        rs.getString(8),
        rs.getObject(9, UUID.class),
        instant(rs.getTimestamp(10)));
  }

  /**
   * One mute; a null target column matches anything.
   *
   * @param id the mute id
   * @param appId the application, or null
   * @param env the environment, or null
   * @param instanceId the instance, or null
   * @param ruleId the rule, or null
   * @param startsAt start
   * @param endsAt end (exclusive)
   * @param reason operator text, or null
   * @param createdBy the user, or null
   * @param createdAt creation time (null before insert)
   */
  public record MuteRow(
      UUID id,
      Integer appId,
      String env,
      Integer instanceId,
      UUID ruleId,
      Instant startsAt,
      Instant endsAt,
      String reason,
      UUID createdBy,
      Instant createdAt) {}
}
