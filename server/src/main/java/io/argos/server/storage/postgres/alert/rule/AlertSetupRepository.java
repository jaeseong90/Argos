/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.postgres.alert.rule;

import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The default rule bookkeeping of the alert engine (12 §5.6, F-S24; WO-25): {@code
 * apps.recommended_rules_applied} (V001) and the server settings flag of the server self rules
 * ({@code settings}).
 */
@Repository
public class AlertSetupRepository {

  /** {@code settings} key: the server self rules were created (once, at the first start). */
  public static final String SERVER_RULES_APPLIED = "alert_server_rules_applied";

  private final JdbcTemplate jdbc;

  public AlertSetupRepository(DataSource dataSource) {
    JdbcTemplate t = new JdbcTemplate(dataSource);
    t.setQueryTimeout(AlertRuleRepository.QUERY_TIMEOUT_SECONDS);
    this.jdbc = t;
  }

  /** Applications whose recommended rules are not applied yet, oldest first, at most 100. */
  public List<PendingApp> appsWithoutRecommendedRules() {
    return jdbc.query(
        "SELECT id, env FROM argos.apps WHERE NOT recommended_rules_applied ORDER BY id LIMIT 100",
        (rs, i) -> new PendingApp(rs.getInt(1), rs.getString(2)));
  }

  /**
   * Marks the application's recommended rules applied; false when another server (or an earlier
   * run) marked it first.
   */
  public boolean claimRecommended(int appId) {
    return jdbc.update(
            "UPDATE argos.apps SET recommended_rules_applied = TRUE"
                + " WHERE id = ? AND NOT recommended_rules_applied",
            appId)
        > 0;
  }

  /** Clears the mark again (the rules could not be created). */
  public void unclaimRecommended(int appId) {
    jdbc.update("UPDATE argos.apps SET recommended_rules_applied = FALSE WHERE id = ?", appId);
  }

  /** Whether the application's recommended rules are applied. */
  public boolean recommendedApplied(int appId) {
    List<Boolean> v =
        jdbc.query(
            "SELECT recommended_rules_applied FROM argos.apps WHERE id = ?",
            (rs, i) -> rs.getBoolean(1),
            appId);
    return !v.isEmpty() && v.get(0);
  }

  /**
   * Sets the server self rule flag; false when it was set already (by another server or an earlier
   * start).
   */
  public boolean claimServerRules() {
    return jdbc.update(
            "INSERT INTO argos.settings (key, value, updated_at) VALUES (?, 'true'::jsonb, now())"
                + " ON CONFLICT (key) DO NOTHING",
            SERVER_RULES_APPLIED)
        > 0;
  }

  /** Clears the server self rule flag (the rules could not be created). */
  public void unclaimServerRules() {
    jdbc.update("DELETE FROM argos.settings WHERE key = ?", SERVER_RULES_APPLIED);
  }

  /**
   * An application without its recommended rules.
   *
   * @param appId {@code apps.id}
   * @param env its environment
   */
  public record PendingApp(int appId, String env) {}
}
