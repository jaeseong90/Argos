/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.postgres.app;

import io.argos.server.session.AppRef;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code apps} (PostgreSQL V001, DSN-02-14) for the session start of the collector (11 §5 check 5,
 * 12 §4.4): the read by (name, env) and the automatic registration of an environment variant of an
 * agent token's home app ({@code registered_from_app_id}, V003, ADR-0010 C-155). The approval queue
 * is {@link PendingRegistrationRepository} (WO-22); app administration is WO-26's and later.
 */
@Repository
public class AppRepository {

  /** Seconds a statement may take (the Hello path waits on it). */
  static final int QUERY_TIMEOUT_SECONDS = 5;

  /** Seconds the variant registration transaction may take before it is rolled back. */
  static final int TRANSACTION_TIMEOUT_SECONDS = 10;

  /** {@code apps.name VARCHAR(128)}. */
  public static final int MAX_NAME_CHARS = 128;

  /** {@code apps.env VARCHAR(32)}. */
  public static final int MAX_ENV_CHARS = 32;

  private static final String FIND =
      "SELECT id, name, env, registered_from_app_id FROM argos.apps WHERE name = ? AND env = ?"
          + " AND deleted_at IS NULL";

  /** Locks the home app's row: one variant registration per home app at a time, on any server. */
  private static final String LOCK_HOME =
      "SELECT id FROM argos.apps WHERE id = ? AND deleted_at IS NULL FOR UPDATE";

  /**
   * Variants the home app's Hellos registered themselves ({@code auto_register}); a variant an
   * administrator approved from the queue ({@link PendingRegistrationRepository#approve}) is not
   * counted (C-155 (3): the approval does not take the cap).
   */
  private static final String COUNT_VARIANTS =
      "SELECT count(*) FROM argos.apps WHERE registered_from_app_id = ? AND auto_register";

  /**
   * {@code auto_register} marks a row created by an agent's Hello (12 §4.4), as opposed to one an
   * administrator created; {@code registered_from_app_id} names the token's home app (C-155).
   */
  private static final String INSERT_VARIANT =
      "INSERT INTO argos.apps (name, env, auto_register, registered_from_app_id)"
          + " VALUES (?, ?, TRUE, ?) ON CONFLICT (name, env) DO NOTHING";

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;

  public AppRepository(DataSource dataSource) {
    JdbcTemplate t = new JdbcTemplate(dataSource);
    t.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
    this.jdbc = t;
    TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    tx.setTimeout(TRANSACTION_TIMEOUT_SECONDS);
    this.transactions = tx;
  }

  /** The app ({@code name}, {@code env}). */
  public Optional<AppRef> find(String name, String env) {
    return jdbc
        .query(
            FIND,
            (rs, row) -> new AppRef(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getInt(4)),
            name,
            env)
        .stream()
        .findFirst();
  }

  /**
   * Creates ({@code name}, {@code env}) as an environment variant of {@code homeAppId} unless the
   * home app has {@code maxVariants} variants already (ADR-0010 C-155 (3)). One READ COMMITTED
   * transaction: the home app's row is locked first ({@code FOR UPDATE}), then the existing row is
   * looked up and the variants are counted in statements of their own, so each sees what an earlier
   * holder of the lock committed. Concurrent registrations of one home app, from any number of
   * servers, therefore never exceed the cap.
   *
   * @return the app ({@code name}, {@code env}), new or created meanwhile by another session (the
   *     caller checks whose it is); empty when the cap is reached or the home app no longer exists
   * @throws IllegalArgumentException when a value does not fit its column (the collector's Hello
   *     caps refuse such values earlier: names and environments are never cut)
   */
  public Optional<AppRef> createVariant(String name, String env, int homeAppId, int maxVariants) {
    if (name.length() > MAX_NAME_CHARS || env.length() > MAX_ENV_CHARS) {
      throw new IllegalArgumentException("App name or environment longer than its column");
    }
    Optional<AppRef> result =
        transactions.execute(
            status -> {
              List<Integer> home = jdbc.query(LOCK_HOME, (rs, row) -> rs.getInt(1), homeAppId);
              if (home.isEmpty()) {
                return Optional.<AppRef>empty();
              }
              Optional<AppRef> existing = find(name, env);
              if (existing.isPresent()) {
                return existing;
              }
              Integer variants = jdbc.queryForObject(COUNT_VARIANTS, Integer.class, homeAppId);
              if (variants != null && variants >= maxVariants) {
                return Optional.<AppRef>empty();
              }
              jdbc.update(INSERT_VARIANT, name, env, homeAppId);
              return Optional.of(
                  find(name, env)
                      .orElseThrow(
                          () ->
                              new IllegalStateException("App row missing right after its insert")));
            });
    return result == null ? Optional.empty() : result;
  }
}
