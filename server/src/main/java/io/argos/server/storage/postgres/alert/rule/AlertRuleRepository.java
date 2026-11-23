/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.postgres.alert.rule;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code alert_rules} and {@code alert_rule_history} (PostgreSQL V001, V007 {@code deleted_at}; 12
 * §5.1, ADR-0010 C-111 (3)): the rules of the alert engine and the REST API (WO-25). Every change
 * writes the new version to the history in the same transaction. Deleting a rule sets {@code
 * deleted_at} (its events keep referring to it); the reads below skip deleted rules unless they say
 * otherwise.
 */
@Repository
public class AlertRuleRepository {

  /** Seconds a statement may take. */
  static final int QUERY_TIMEOUT_SECONDS = 10;

  /** Rules a list returns at most (14 §4.13: 1,000). */
  public static final int MAX_LIST = 1_000;

  private static final String COLUMNS =
      "id, app_id, env, name, template_key, enabled, spec::text, created_by, created_at,"
          + " updated_at, version, deleted_at";

  private static final RowMapper<AlertRuleRow> ROW = AlertRuleRepository::row;

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;

  public AlertRuleRepository(DataSource dataSource) {
    JdbcTemplate t = new JdbcTemplate(dataSource);
    t.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
    this.jdbc = t;
    TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    tx.setTimeout(QUERY_TIMEOUT_SECONDS);
    this.transactions = tx;
  }

  /**
   * Inserts a rule (version 1) and its first history row.
   *
   * @param createdBy the user, or null (server: default templates)
   */
  public void insert(NewRule rule, UUID createdBy) {
    transactions.executeWithoutResult(
        status -> {
          jdbc.update(
              "INSERT INTO argos.alert_rules (id, app_id, env, name, template_key, enabled, spec,"
                  + " created_by, created_at, updated_at, version)"
                  + " VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, now(), now(), 1)",
              rule.id(),
              rule.appId(),
              rule.env(),
              rule.name(),
              rule.templateKey(),
              rule.enabled(),
              rule.specJson(),
              createdBy);
          history(rule.id(), 1, rule.specJson(), createdBy);
        });
  }

  /**
   * Replaces the rule's spec and index columns, raises its version and writes the history.
   *
   * @return the new version, or empty when the rule does not exist (or is deleted)
   */
  public Optional<Integer> update(NewRule rule, UUID changedBy) {
    return Optional.ofNullable(
        transactions.execute(
            status -> {
              List<Integer> v =
                  jdbc.query(
                      "UPDATE argos.alert_rules SET app_id = ?, env = ?, name = ?, template_key ="
                          + " ?, enabled = ?, spec = CAST(? AS jsonb), updated_at = now(), version"
                          + " = version + 1 WHERE id = ? AND deleted_at IS NULL RETURNING version",
                      (rs, i) -> rs.getInt(1),
                      rule.appId(),
                      rule.env(),
                      rule.name(),
                      rule.templateKey(),
                      rule.enabled(),
                      rule.specJson(),
                      rule.id());
              if (v.isEmpty()) {
                return null;
              }
              history(rule.id(), v.get(0), rule.specJson(), changedBy);
              return v.get(0);
            }));
  }

  /** Marks the rule deleted; false when it does not exist or is deleted already. */
  public boolean softDelete(UUID id, UUID deletedBy) {
    return Boolean.TRUE.equals(
        transactions.execute(
            status -> {
              List<Object[]> v =
                  jdbc.query(
                      "UPDATE argos.alert_rules SET deleted_at = now(), enabled = FALSE,"
                          + " updated_at = now(), version = version + 1"
                          + " WHERE id = ? AND deleted_at IS NULL RETURNING version, spec::text",
                      (rs, i) -> new Object[] {rs.getInt(1), rs.getString(2)},
                      id);
              if (v.isEmpty()) {
                return false;
              }
              history(id, (Integer) v.get(0)[0], (String) v.get(0)[1], deletedBy);
              return true;
            }));
  }

  /** The rule, unless it is deleted. */
  public Optional<AlertRuleRow> find(UUID id) {
    return jdbc
        .query(
            "SELECT " + COLUMNS + " FROM argos.alert_rules WHERE id = ? AND deleted_at IS NULL",
            ROW,
            id)
        .stream()
        .findFirst();
  }

  /** The rule, deleted or not. */
  public Optional<AlertRuleRow> findAny(UUID id) {
    return jdbc
        .query("SELECT " + COLUMNS + " FROM argos.alert_rules WHERE id = ?", ROW, id)
        .stream()
        .findFirst();
  }

  /**
   * The rules of one application, or every rule, newest first, at most {@link #MAX_LIST}.
   *
   * @param appId the application, or null for every rule (server rules included)
   * @param env the environment, or null for any
   * @param enabled only enabled or only disabled rules, or null for both
   */
  public List<AlertRuleRow> list(Integer appId, String env, Boolean enabled) {
    StringBuilder sql =
        new StringBuilder("SELECT " + COLUMNS + " FROM argos.alert_rules WHERE deleted_at IS NULL");
    List<Object> args = new ArrayList<>();
    if (appId != null) {
      sql.append(" AND app_id = ?");
      args.add(appId);
    }
    if (env != null) {
      sql.append(" AND (env = ? OR env IS NULL)");
      args.add(env);
    }
    if (enabled != null) {
      sql.append(" AND enabled = ?");
      args.add(enabled);
    }
    sql.append(" ORDER BY created_at DESC, id LIMIT ").append(MAX_LIST);
    return jdbc.query(sql.toString(), ROW, args.toArray());
  }

  /** Every rule that is not deleted (the engine's load). */
  public List<AlertRuleRow> listLive() {
    return jdbc.query(
        "SELECT " + COLUMNS + " FROM argos.alert_rules WHERE deleted_at IS NULL ORDER BY id", ROW);
  }

  /** The history of a rule, newest first. */
  public List<HistoryRow> history(UUID ruleId, int limit) {
    return jdbc.query(
        "SELECT h.version, h.changed_by, h.changed_at, u.username"
            + " FROM argos.alert_rule_history h LEFT JOIN argos.users u ON u.id = h.changed_by"
            + " WHERE h.rule_id = ? ORDER BY h.version DESC LIMIT ?",
        (rs, i) ->
            new HistoryRow(
                rs.getInt(1),
                rs.getObject(2, UUID.class),
                instant(rs.getTimestamp(3)),
                rs.getString(4)),
        ruleId,
        limit);
  }

  /** The given channel ids that exist in {@code notification_channels}. */
  public Set<UUID> existingChannels(Collection<UUID> ids) {
    if (ids.isEmpty()) {
      return Set.of();
    }
    return new HashSet<>(
        jdbc.query(
            "SELECT id FROM argos.notification_channels WHERE id = ANY(CAST(? AS uuid[]))",
            (rs, i) -> rs.getObject(1, UUID.class),
            (Object) ids.stream().map(UUID::toString).toArray(String[]::new)));
  }

  /**
   * Whether a rule of the template part exists for the application (or the server when {@code
   * appId} is null), deleted ones included (a deleted default rule is not created again).
   */
  public boolean templateRuleExists(Integer appId, String templateKey, String metric) {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM argos.alert_rules WHERE app_id IS NOT DISTINCT FROM ?"
                + " AND template_key = ? AND spec->>'metric' = ?",
            Integer.class,
            appId,
            templateKey,
            metric);
    return n != null && n > 0;
  }

  /** Whether {@code appId} is an application. */
  public boolean appExists(int appId) {
    Integer n =
        jdbc.queryForObject("SELECT count(*) FROM argos.apps WHERE id = ?", Integer.class, appId);
    return n != null && n > 0;
  }

  private void history(UUID ruleId, int version, String spec, UUID changedBy) {
    jdbc.update(
        "INSERT INTO argos.alert_rule_history (rule_id, version, spec, changed_by, changed_at)"
            + " VALUES (?, ?, CAST(? AS jsonb), ?, now())",
        ruleId,
        version,
        spec,
        changedBy);
  }

  private static AlertRuleRow row(ResultSet rs, int i) throws SQLException {
    int app = rs.getInt(2);
    Integer appId = rs.wasNull() ? null : app;
    return new AlertRuleRow(
        rs.getObject(1, UUID.class),
        appId,
        rs.getString(3),
        rs.getString(4),
        rs.getString(5),
        rs.getBoolean(6),
        rs.getString(7),
        rs.getObject(8, UUID.class),
        instant(rs.getTimestamp(9)),
        instant(rs.getTimestamp(10)),
        rs.getInt(11),
        instant(rs.getTimestamp(12)));
  }

  static Instant instant(Timestamp t) {
    return t == null ? null : t.toInstant();
  }

  /**
   * A rule to insert or update.
   *
   * @param id the rule id
   * @param appId the application, null for a server rule
   * @param env the environment, or null for any
   * @param name the rule name
   * @param templateKey the default template, or null
   * @param enabled whether it is evaluated
   * @param specJson the canonical rule JSON
   */
  public record NewRule(
      UUID id,
      Integer appId,
      String env,
      String name,
      String templateKey,
      boolean enabled,
      String specJson) {}

  /**
   * One stored rule.
   *
   * @param id the rule id
   * @param appId the application, null for a server rule
   * @param env the environment, or null
   * @param name the name
   * @param templateKey the template, or null
   * @param enabled whether it is evaluated
   * @param specJson the canonical rule JSON
   * @param createdBy the creating user, null for the server
   * @param createdAt creation time
   * @param updatedAt last change
   * @param version the version (1 on creation)
   * @param deletedAt the deletion time, or null
   */
  public record AlertRuleRow(
      UUID id,
      Integer appId,
      String env,
      String name,
      String templateKey,
      boolean enabled,
      String specJson,
      UUID createdBy,
      Instant createdAt,
      Instant updatedAt,
      int version,
      Instant deletedAt) {}

  /**
   * One history row.
   *
   * @param version the version
   * @param changedBy the user, null for the server
   * @param changedAt the time of the change
   * @param changedByName that user's name (operator text), null for the server or a deleted user
   */
  public record HistoryRow(int version, UUID changedBy, Instant changedAt, String changedByName) {}
}
