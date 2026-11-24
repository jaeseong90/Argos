/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.postgres.app;

import java.util.List;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code apps.auto_register} of one application (ADR-0010 C-231 (7), V013): TRUE {@code auto},
 * FALSE {@code approval}, NULL none (the environment default applies). Read by the Hello's
 * automatic registration ({@code io.argos.server.session.AppRegistrationPolicies}, WO-28).
 */
@Repository
public class AppRegistrationPolicyRepository {

  /** Seconds the read may take (the Hello waits on it). */
  static final int QUERY_TIMEOUT_SECONDS = 5;

  private static final String FIND =
      "SELECT auto_register FROM argos.apps WHERE id = ? AND deleted_at IS NULL";

  private final JdbcTemplate jdbc;

  public AppRegistrationPolicyRepository(DataSource dataSource) {
    JdbcTemplate t = new JdbcTemplate(dataSource);
    t.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
    this.jdbc = t;
  }

  /**
   * The stored policy of {@code appId}.
   *
   * @return null when the application has none, does not exist or is deleted
   */
  public @Nullable Boolean autoRegister(int appId) {
    List<Boolean> rows =
        jdbc.query(
            FIND,
            (rs, n) -> {
              boolean value = rs.getBoolean(1);
              return rs.wasNull() ? null : value;
            },
            appId);
    return rows.isEmpty() ? null : rows.get(0);
  }
}
