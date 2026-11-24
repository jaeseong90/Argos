/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.postgres.privacy;

import io.argos.server.secrets.SecretField;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The stored user id salt of an application ({@code apps.user_id_salt_enc}, V013; 16 §6.3): an
 * envelope string ({@link SecretField#APP_USER_ID_SALT}) or none yet. This class sees envelopes
 * only, never a salt.
 */
@Repository
public class UserIdSaltRepository {

  /** Seconds a statement may take (the agent Hello waits on a first read). */
  static final int QUERY_TIMEOUT_SECONDS = 5;

  private static final String FIND =
      "SELECT user_id_salt_enc FROM argos.apps WHERE id = ? AND deleted_at IS NULL";

  /** Only the first writer wins: a concurrent server or request keeps its salt. */
  private static final String SET_IF_ABSENT =
      "UPDATE argos.apps SET user_id_salt_enc = ? WHERE id = ? AND user_id_salt_enc IS NULL"
          + " AND deleted_at IS NULL";

  private final JdbcTemplate jdbc;

  public UserIdSaltRepository(DataSource dataSource) {
    JdbcTemplate t = new JdbcTemplate(dataSource);
    t.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
    this.jdbc = t;
  }

  /**
   * The stored salt of an application.
   *
   * @return empty when the application does not exist or is deleted; a {@link Stored} with a null
   *     envelope when it has no salt yet
   */
  public Optional<Stored> find(int appId) {
    List<Stored> rows =
        jdbc.query(FIND, (rs, n) -> new Stored(SecretField.envelope(rs.getBytes(1))), appId);
    return rows.stream().findFirst();
  }

  /**
   * Stores {@code envelope} when the application has no salt yet.
   *
   * @return true when this call stored it
   */
  public boolean storeIfAbsent(int appId, String envelope) {
    return jdbc.update(SET_IF_ABSENT, SecretField.stored(envelope), appId) == 1;
  }

  /**
   * A stored salt.
   *
   * @param envelope the envelope, or null when none is stored
   */
  public record Stored(@Nullable String envelope) {}
}
