/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.postgres.app;

import io.argos.server.session.AppRef;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The approval queue of automatic registration, {@code pending_registrations} (V001, {@code
 * home_app_id} V004; 12 §4.4, F-S30, ADR-0010 C-155 (3)).
 *
 * <ul>
 *   <li>{@link #record}: the collector's Hello path. A Hello of a missing (name, env) whose
 *       environment does not register automatically, or whose home app has its 8 automatically
 *       registered variants, upserts (name, env, host): a new row starts at 1 attempt, a known row
 *       counts one more attempt and moves its last attempt. At most {@value #MAX_ROWS} rows: a new
 *       row beyond them replaces the least recently attempted one (ties: the lowest id).
 *   <li>{@link #list}, {@link #approve}, {@link #reject}: for the administration API of WO-26
 *       ({@code /admin/pending-registrations*}, not built here).
 * </ul>
 *
 * <p>Writers of the queue serialize on one transaction-scoped advisory lock ({@value #LOCK_KEY}),
 * so the cap holds under concurrent Hellos on any number of servers. An approval creates the
 * environment variant ({@code registered_from_app_id} = the home app, {@code auto_register} FALSE:
 * an administrator's decision, not counted against the cap of automatic variants), so that the same
 * agent token is accepted on its next retry without a new token.
 */
@Repository
public class PendingRegistrationRepository {

  /** 12 §4.4: rows the queue keeps. */
  public static final int MAX_ROWS = 1_000;

  /** {@code pending_registrations.host VARCHAR(255)}. */
  public static final int MAX_HOST_CHARS = 255;

  /** The advisory lock of the queue's writers (an arbitrary constant of this table). */
  static final long LOCK_KEY = 0x4152474F_50524547L;

  static final int QUERY_TIMEOUT_SECONDS = 5;

  static final int TRANSACTION_TIMEOUT_SECONDS = 10;

  private static final String LOCK = "SELECT pg_advisory_xact_lock(?)";

  private static final String UPDATE_ATTEMPT =
      "UPDATE argos.pending_registrations SET attempts = COALESCE(attempts, 0) + 1,"
          + " last_attempt_at = ?, home_app_id = ? WHERE name = ? AND env = ? AND host = ?";

  private static final String COUNT = "SELECT count(*) FROM argos.pending_registrations";

  private static final String DELETE_OLDEST =
      "DELETE FROM argos.pending_registrations WHERE id IN (SELECT id FROM"
          + " argos.pending_registrations ORDER BY last_attempt_at ASC NULLS FIRST, id ASC LIMIT ?)";

  private static final String INSERT =
      "INSERT INTO argos.pending_registrations"
          + " (name, env, host, first_attempt_at, last_attempt_at, attempts, home_app_id)"
          + " VALUES (?, ?, ?, ?, ?, 1, ?)";

  private static final String COLUMNS =
      "id, name, env, host, home_app_id, first_attempt_at, last_attempt_at, attempts";

  private static final String LIST_ALL =
      "SELECT "
          + COLUMNS
          + " FROM argos.pending_registrations ORDER BY last_attempt_at DESC NULLS LAST, id DESC"
          + " LIMIT ?";

  private static final String LIST_ENV =
      "SELECT "
          + COLUMNS
          + " FROM argos.pending_registrations WHERE env = ?"
          + " ORDER BY last_attempt_at DESC NULLS LAST, id DESC LIMIT ?";

  private static final String FIND_FOR_UPDATE =
      "SELECT " + COLUMNS + " FROM argos.pending_registrations WHERE id = ? FOR UPDATE";

  private static final String LOCK_HOME = "SELECT id FROM argos.apps WHERE id = ? FOR UPDATE";

  private static final String FIND_APP =
      "SELECT id, name, env, registered_from_app_id FROM argos.apps WHERE name = ? AND env = ?";

  private static final String INSERT_APP =
      "INSERT INTO argos.apps (name, env, auto_register, registered_from_app_id)"
          + " VALUES (?, ?, FALSE, ?) ON CONFLICT (name, env) DO NOTHING";

  private static final String DELETE_APP_REQUESTS =
      "DELETE FROM argos.pending_registrations WHERE name = ? AND env = ?";

  private static final String DELETE = "DELETE FROM argos.pending_registrations WHERE id = ?";

  private static final RowMapper<PendingRegistration> ROW =
      (rs, row) -> {
        int home = rs.getInt(5);
        Integer homeAppId = rs.wasNull() ? null : home;
        Timestamp first = rs.getTimestamp(6);
        Timestamp last = rs.getTimestamp(7);
        return new PendingRegistration(
            rs.getInt(1),
            rs.getString(2),
            rs.getString(3),
            rs.getString(4),
            homeAppId,
            first == null ? null : first.toInstant(),
            last == null ? null : last.toInstant(),
            rs.getInt(8));
      };

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;

  public PendingRegistrationRepository(DataSource dataSource) {
    JdbcTemplate t = new JdbcTemplate(dataSource);
    t.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
    this.jdbc = t;
    TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    tx.setTimeout(TRANSACTION_TIMEOUT_SECONDS);
    this.transactions = tx;
  }

  /**
   * Records a refused Hello of ({@code name}, {@code env}) from {@code host} for the agent token's
   * home app {@code homeAppId} at {@code at} (see the class comment for the cap).
   *
   * @return whether a new row was queued (false: an existing row counted one more attempt)
   * @throws IllegalArgumentException when the name or environment does not fit its column (the
   *     collector's Hello caps refuse such values earlier)
   */
  public boolean record(String name, String env, String host, int homeAppId, Instant at) {
    if (name.length() > AppRepository.MAX_NAME_CHARS
        || env.length() > AppRepository.MAX_ENV_CHARS) {
      throw new IllegalArgumentException("App name or environment longer than its column");
    }
    String storedHost = host(host);
    Timestamp ts = Timestamp.from(at);
    Boolean inserted =
        transactions.execute(
            status -> {
              lock();
              if (jdbc.update(UPDATE_ATTEMPT, ts, homeAppId, name, env, storedHost) > 0) {
                return false;
              }
              Integer rows = jdbc.queryForObject(COUNT, Integer.class);
              int excess = (rows == null ? 0 : rows) - MAX_ROWS + 1;
              if (excess > 0) {
                jdbc.update(DELETE_OLDEST, excess);
              }
              jdbc.update(INSERT, name, env, storedHost, ts, ts, homeAppId);
              return true;
            });
    return Boolean.TRUE.equals(inserted);
  }

  /**
   * The queued requests, most recently attempted first, at most {@value #MAX_ROWS}.
   *
   * @param env only this environment, or null for all
   */
  public List<PendingRegistration> list(String env) {
    return env == null
        ? jdbc.query(LIST_ALL, ROW, MAX_ROWS)
        : jdbc.query(LIST_ENV, ROW, env, MAX_ROWS);
  }

  /**
   * Approves request {@code id} (ADR-0010 C-155 (3), PM question 3 assumption of WO-22): creates
   * the app (name, env) as an environment variant of the request's home app ({@code
   * registered_from_app_id} = home app, {@code auto_register} FALSE; the cap of {@code
   * HelloService.MAX_APP_VARIANTS} automatic variants does not apply), and deletes every request of
   * (name, env). The home app's tokens are accepted on their next retry; no token is issued. One
   * transaction; the home app's row is locked like an automatic registration locks it.
   *
   * <p>An app (name, env) that exists already and is the home app or one of its variants counts as
   * approved (its requests are deleted). One that belongs to another app (an administrator created
   * it, or another home app's variant) is {@link Approval.Outcome#NAME_TAKEN}: the request stays,
   * since the home app's tokens could not use that app (C-155 (2)).
   */
  public Approval approve(int id) {
    Approval result =
        transactions.execute(
            status -> {
              lock();
              Optional<PendingRegistration> found =
                  jdbc.query(FIND_FOR_UPDATE, ROW, id).stream().findFirst();
              if (found.isEmpty()) {
                return new Approval(Approval.Outcome.NOT_FOUND, null);
              }
              PendingRegistration p = found.get();
              if (p.homeAppId() == null
                  || jdbc.query(LOCK_HOME, (rs, row) -> rs.getInt(1), p.homeAppId()).isEmpty()) {
                return new Approval(Approval.Outcome.HOME_APP_MISSING, null);
              }
              int home = p.homeAppId();
              Optional<AppRef> existing = findApp(p.name(), p.env());
              if (existing.isPresent() && !existing.get().usableWith(home)) {
                return new Approval(Approval.Outcome.NAME_TAKEN, existing.get());
              }
              if (existing.isEmpty()) {
                jdbc.update(INSERT_APP, p.name(), p.env(), home);
              }
              AppRef app =
                  findApp(p.name(), p.env())
                      .orElseThrow(
                          () ->
                              new IllegalStateException("App row missing right after its insert"));
              jdbc.update(DELETE_APP_REQUESTS, p.name(), p.env());
              return new Approval(Approval.Outcome.APPROVED, app);
            });
    return Objects.requireNonNull(result, "approval");
  }

  /**
   * Rejects request {@code id}: the row is deleted. The agent's next retry queues it again (12 §4.4
   * keeps no deny list).
   *
   * @return whether the row existed
   */
  public boolean reject(int id) {
    Boolean deleted =
        transactions.execute(
            status -> {
              lock();
              return jdbc.update(DELETE, id) > 0;
            });
    return Boolean.TRUE.equals(deleted);
  }

  /** The stored host of a request: the Hello host, {@code unknown} when blank, cut to 255. */
  static String host(String host) {
    String h = host == null ? "" : host.strip();
    if (h.isEmpty()) {
      h = "unknown";
    }
    if (h.length() > MAX_HOST_CHARS) {
      int end = MAX_HOST_CHARS;
      if (Character.isHighSurrogate(h.charAt(end - 1))) {
        end--;
      }
      h = h.substring(0, end);
    }
    return h;
  }

  private void lock() {
    jdbc.query(LOCK, rs -> null, LOCK_KEY);
  }

  private Optional<AppRef> findApp(String name, String env) {
    return jdbc
        .query(
            FIND_APP,
            (rs, row) -> new AppRef(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getInt(4)),
            name,
            env)
        .stream()
        .findFirst();
  }

  /**
   * One queued request.
   *
   * @param id the row id
   * @param name the app name of the refused Hello
   * @param env its environment
   * @param host the Hello host ({@code unknown} when blank)
   * @param homeAppId the home app of the token that asked, or null (a row older than V004)
   * @param firstAttemptAt the first refused Hello
   * @param lastAttemptAt the latest refused Hello
   * @param attempts refused Hellos so far
   */
  public record PendingRegistration(
      int id,
      String name,
      String env,
      String host,
      Integer homeAppId,
      Instant firstAttemptAt,
      Instant lastAttemptAt,
      int attempts) {}

  /**
   * The outcome of {@link #approve}.
   *
   * @param outcome what happened
   * @param app the app (name, env): the variant for {@code APPROVED}, the other app for {@code
   *     NAME_TAKEN}, null otherwise
   */
  public record Approval(Outcome outcome, AppRef app) {

    /** What an approval did. */
    public enum Outcome {
      /** The variant exists now (created, or already the home app's); the requests are gone. */
      APPROVED,
      /** No request with this id. */
      NOT_FOUND,
      /** The request has no home app (deleted, or a row older than V004); nothing changed. */
      HOME_APP_MISSING,
      /** (name, env) belongs to an app the home app's tokens cannot use; nothing changed. */
      NAME_TAKEN
    }
  }
}
