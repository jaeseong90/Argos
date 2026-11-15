/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.auth.password.LockoutPolicy;
import io.argos.server.auth.session.SessionCleanupJob;
import io.argos.server.auth.session.UnknownNameLockout;
import io.argos.server.auth.session.UnknownNameLockout.Outcome;
import io.argos.server.storage.postgres.auth.AuthTransactions;
import io.argos.server.storage.postgres.auth.SessionRepository;
import io.argos.server.storage.postgres.auth.UnknownNameRepository;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.server.testing.auth.ApiResponse;
import io.argos.server.testing.auth.ArgosTestServer;
import io.argos.server.testing.auth.AuditLog;
import io.argos.server.testing.auth.SessionTable;
import io.argos.server.testing.auth.TestUsers;
import io.argos.server.testing.auth.TestUsers.TestUser;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The lockout of names that are no account, on the real server and PostgreSQL (16 §7.9, SEC-48,
 * WO-24 "사용자 열거 방지"): its counter lives in {@code login_failures_unknown} as long as an account's
 * lives in {@code users}, so the tenth failure answers 423 for both whatever happens in between (a
 * restart, hours without a failure), and the table stays bounded.
 */
@Tag("req:SEC-48")
class UnknownNameLockoutSecurityTest {

  private final ServerTestEnvironment env = AuthSuite.environment();
  private final ArgosTestServer server = AuthSuite.server();
  private final TestUsers users = AuthSuite.users();

  private static ApiResponse wrongLogin(ArgosTestServer target, String username) {
    return target.client().login(username, TestUsers.newPassword(username));
  }

  private static void failNineTimes(ArgosTestServer target, String username) {
    for (int i = 1; i <= 9; i++) {
      ApiResponse response = wrongLogin(target, username);
      assertThat(response.status()).as(username + " failure " + i).isEqualTo(401);
      assertThat(response.errorCode()).contains("unauthenticated");
    }
  }

  private static String withoutRequestId(ApiResponse response) {
    return response.body().replaceAll("\"request_id\":\"[^\"]*\"", "");
  }

  /** The tenth failure of a known and an unknown name: 423 both, the same body. */
  private static void assertTenthLocksBoth(ApiResponse known, ApiResponse unknown) {
    assertThat(known.status()).as("known: " + known).isEqualTo(423);
    assertThat(known.errorCode()).contains("account_locked");
    assertThat(unknown.status()).as("unknown: " + unknown).isEqualTo(known.status());
    assertThat(withoutRequestId(unknown)).isEqualTo(withoutRequestId(known));
    assertThat(unknown.setCookies()).isEmpty();
    assertThat(unknown.header("Retry-After")).isEmpty();
  }

  /** {@code failed_logins} of the name's row, or null without a row. */
  private Integer storedFailures(String name) {
    try (Connection c = env.connect(PgAccount.OWNER);
        PreparedStatement select =
            c.prepareStatement(
                "SELECT failed_logins FROM argos.login_failures_unknown WHERE name_hash = ?")) {
      select.setString(1, SessionTable.sha256Hex(name));
      try (ResultSet rs = select.executeQuery()) {
        return rs.next() ? rs.getInt(1) : null;
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private long rowsContaining(String text) {
    try (Connection c = env.connect(PgAccount.OWNER);
        PreparedStatement select =
            c.prepareStatement(
                "SELECT count(*) FROM argos.login_failures_unknown f"
                    + " WHERE strpos(CAST(f AS text), ?) > 0")) {
      select.setString(1, text);
      try (ResultSet rs = select.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private long totalRows() {
    return server.bean(UnknownNameRepository.class).count();
  }

  private void deleteRows(List<String> names) {
    try (Connection c = env.connect(PgAccount.OWNER);
        PreparedStatement delete =
            c.prepareStatement("DELETE FROM argos.login_failures_unknown WHERE name_hash = ?")) {
      for (String name : names) {
        delete.setString(1, SessionTable.sha256Hex(name));
        delete.executeUpdate();
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private UnknownNameLockout lockout(long maxRows) {
    return new UnknownNameLockout(
        server.bean(LockoutPolicy.class),
        server.bean(UnknownNameRepository.class),
        server.bean(AuthTransactions.class),
        maxRows);
  }

  private static void failTen(UnknownNameLockout lockout, String name, Instant now) {
    for (int i = 1; i <= 9; i++) {
      assertThat(lockout.recordFailure(name, now).outcome())
          .as("failure " + i)
          .isEqualTo(Outcome.COUNTED);
      assertThat(lockout.locked(name, now)).isFalse();
    }
    assertThat(lockout.recordFailure(name, now).outcome())
        .as("failure 10")
        .isEqualTo(Outcome.LOCKS);
    assertThat(lockout.locked(name, now)).isTrue();
    assertThat(lockout.recordFailure(name, now).outcome())
        .as("while locked nothing is counted")
        .isEqualTo(Outcome.LOCKED);
  }

  /** The stored state of a {@code login_failures_unknown} row, its row version included. */
  private record UnknownRow(
      String xmin, Instant lastFailedAt, int failedLogins, Instant lockedUntil, int level) {}

  private UnknownRow unknownRow(String name) {
    try (Connection c = env.connect(PgAccount.OWNER);
        PreparedStatement select =
            c.prepareStatement(
                "SELECT xmin::text, last_failed_at, failed_logins, locked_until, lockout_level"
                    + " FROM argos.login_failures_unknown WHERE name_hash = ?")) {
      select.setString(1, SessionTable.sha256Hex(name));
      try (ResultSet rs = select.executeQuery()) {
        assertThat(rs.next()).as("a row for the name").isTrue();
        return new UnknownRow(
            rs.getString(1),
            rs.getObject(2, OffsetDateTime.class).toInstant(),
            rs.getInt(3),
            rs.getObject(4, OffsetDateTime.class).toInstant(),
            rs.getInt(5));
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  /** The row version ({@code xmin}) and lockout columns of the account's row. */
  private String userRow(TestUser user) {
    try (Connection c = env.connect(PgAccount.OWNER);
        PreparedStatement select =
            c.prepareStatement(
                "SELECT concat_ws(' ', xmin::text, failed_logins, locked_until, lockout_level)"
                    + " FROM argos.users WHERE id = ?")) {
      select.setObject(1, user.id());
      try (ResultSet rs = select.executeQuery()) {
        assertThat(rs.next()).isTrue();
        return rs.getString(1);
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  /** The {@code ACCOUNT_LOCKED} audit rows of an attempted name (known or not). */
  private List<AuditLog.Row> lockRows(String name) {
    return AuditLog.rows(env, "ACCOUNT_LOCKED").stream()
        .filter(r -> name.equals(r.actorName()))
        .toList();
  }

  private long failedRows(String name) {
    return AuditLog.rows(env, "LOGIN_FAILED").stream()
        .filter(r -> name.equals(r.actorName()))
        .count();
  }

  @Test
  @DisplayName(
      "T-SEC-48a ten failures lock an unknown name for 15, then 30, then 60 minutes, in PostgreSQL"
          + " by the SHA-256 of the name")
  void progression() {
    UnknownNameLockout lockout = server.bean(UnknownNameLockout.class);
    String name = TestUsers.newUsername();
    Instant now = AuthSuite.clock().instant();
    failTen(lockout, name, now);
    assertThat(lockout.locked(name, now.plus(Duration.ofMinutes(15)).minusSeconds(1))).isTrue();
    now = now.plus(Duration.ofMinutes(15));
    assertThat(lockout.locked(name, now)).isFalse();
    failTen(lockout, name, now);
    assertThat(lockout.locked(name, now.plus(Duration.ofMinutes(30)).minusSeconds(1))).isTrue();
    now = now.plus(Duration.ofMinutes(30));
    assertThat(lockout.locked(name, now)).isFalse();
    failTen(lockout, name, now);
    assertThat(lockout.locked(name, now.plus(Duration.ofMinutes(60)).minusSeconds(1))).isTrue();
    now = now.plus(Duration.ofMinutes(60));
    assertThat(lockout.locked(name, now)).isFalse();
    failTen(lockout, name, now);
    assertThat(lockout.locked(name, now.plus(Duration.ofMinutes(60)).minusSeconds(1)))
        .as("60 minutes is the maximum")
        .isTrue();
    assertThat(lockout.locked(name, now.plus(Duration.ofMinutes(60)))).isFalse();
    assertThat(lockout.locked(TestUsers.newUsername(), now)).isFalse();

    assertThat(storedFailures(name)).as("keyed by the SHA-256 of the name").isZero();
    assertThat(rowsContaining(name)).as("the name itself is not stored").isZero();
  }

  @Test
  @DisplayName(
      "T-SEC-48c nine failures, a server restart, the tenth: 423 for a known and an unknown name"
          + " alike, the same body")
  void restartKeepsTheCounter() {
    TestUser known = users.create(Role.VIEWER);
    String unknownName = TestUsers.newUsername();
    try (ArgosTestServer first = ArgosTestServer.start(env, AuthSuite.relaxedLimits())) {
      failNineTimes(first, known.username());
      failNineTimes(first, unknownName);
    }
    assertThat(storedFailures(unknownName)).as("in PostgreSQL, not in the process").isEqualTo(9);
    try (ArgosTestServer second = ArgosTestServer.start(env, AuthSuite.relaxedLimits())) {
      ApiResponse knownTenth = wrongLogin(second, known.username());
      ApiResponse unknownTenth = wrongLogin(second, unknownName);
      assertTenthLocksBoth(knownTenth, unknownTenth);
      assertTenthLocksBoth(wrongLogin(second, known.username()), wrongLogin(second, unknownName));
    }
  }

  @Test
  @DisplayName(
      "T-SEC-48c nine failures, seven hours without one, the tenth: 423 for a known and an unknown"
          + " name alike (no counter is forgotten with time)")
  void timeForgetsNeither() {
    TestUser known = users.create(Role.VIEWER);
    String unknownName = TestUsers.newUsername();
    failNineTimes(server, known.username());
    failNineTimes(server, unknownName);
    AuthSuite.clock().advance(Duration.ofHours(7));
    ApiResponse knownTenth = wrongLogin(server, known.username());
    ApiResponse unknownTenth = wrongLogin(server, unknownName);
    assertTenthLocksBoth(knownTenth, unknownTenth);
    assertTenthLocksBoth(wrongLogin(server, known.username()), wrongLogin(server, unknownName));
  }

  @Test
  @DisplayName(
      "T-SEC-48d a locked name is refused without a write, known or not: xmin and last_failed_at of"
          + " the unknown name's row and xmin of the account's row stay unchanged")
  void lockedNameWritesNothing() {
    TestUser known = users.create(Role.VIEWER);
    String unknownName = TestUsers.newUsername();
    failNineTimes(server, known.username());
    failNineTimes(server, unknownName);
    assertTenthLocksBoth(wrongLogin(server, known.username()), wrongLogin(server, unknownName));
    UnknownRow unknownLocked = unknownRow(unknownName);
    String knownLocked = userRow(known);
    assertThat(unknownLocked.lockedUntil()).isAfter(AuthSuite.clock().instant());

    // Later attempts while the lockout runs: a minute later, so a rewrite of last_failed_at would
    // show; the right password of the account too.
    AuthSuite.clock().advance(Duration.ofMinutes(1));
    for (int i = 0; i < 3; i++) {
      assertTenthLocksBoth(wrongLogin(server, known.username()), wrongLogin(server, unknownName));
    }
    assertThat(server.client().login(known.username(), known.password()).status()).isEqualTo(423);

    UnknownRow unknownAfter = unknownRow(unknownName);
    assertThat(unknownAfter.xmin())
        .as("no new row version: nothing written")
        .isEqualTo(unknownLocked.xmin());
    assertThat(unknownAfter.lastFailedAt()).isEqualTo(unknownLocked.lastFailedAt());
    assertThat(unknownAfter).isEqualTo(unknownLocked);
    assertThat(userRow(known)).as("the account's row is not written either").isEqualTo(knownLocked);
  }

  @Test
  @DisplayName(
      "T-SEC-48e the tenth failure writes exactly one ACCOUNT_LOCKED audit row for a known and an"
          + " unknown name alike (target none for the unknown name); locked attempts write none")
  void lockAuditedOnceForBoth() {
    TestUser known = users.create(Role.VIEWER);
    String unknownName = TestUsers.newUsername();
    failNineTimes(server, known.username());
    failNineTimes(server, unknownName);
    assertThat(lockRows(known.username())).isEmpty();
    assertThat(lockRows(unknownName)).isEmpty();

    assertTenthLocksBoth(wrongLogin(server, known.username()), wrongLogin(server, unknownName));
    assertThat(lockRows(known.username())).hasSize(1);
    assertThat(lockRows(unknownName)).hasSize(1);
    assertTenthLocksBoth(wrongLogin(server, known.username()), wrongLogin(server, unknownName));

    List<AuditLog.Row> knownRows = lockRows(known.username());
    List<AuditLog.Row> unknownRows = lockRows(unknownName);
    assertThat(knownRows).as("the attempt while locked adds none").hasSize(1);
    assertThat(unknownRows).as("the same count as the account").hasSize(1);
    AuditLog.Row account = knownRows.get(0);
    AuditLog.Row unknown = unknownRows.get(0);
    assertThat(unknown.result()).isEqualTo(account.result()).isEqualTo("success");
    assertThat(unknown.reason()).isEqualTo(account.reason()).isEqualTo("failed_logins");
    assertThat(unknown.after()).contains("locked_until");
    assertThat(account.after()).contains("locked_until");
    assertThat(account.targetType()).isEqualTo("user");
    assertThat(account.targetId()).isEqualTo(known.id().toString());
    assertThat(unknown.targetType()).as("Target.none").isNull();
    assertThat(unknown.targetId()).isNull();
    assertThat(unknown.targetName()).isNull();
    assertThat(failedRows(unknownName))
        .as("LOGIN_FAILED rows: 11 attempts each")
        .isEqualTo(failedRows(known.username()))
        .isEqualTo(11);
  }

  @Test
  @DisplayName(
      "T-SEC-48c the table is bounded: the cleanup job keeps the names with the latest failures"
          + " and deletes the oldest")
  void boundedTable() {
    long cap = 3;
    UnknownNameLockout small = lockout(cap);
    // Later than every other row of the suite, so these are the newest names.
    Instant base = AuthSuite.clock().instant().plus(Duration.ofDays(3650));
    List<String> names = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      String name = TestUsers.newUsername();
      names.add(name);
      assertThat(small.recordFailure(name, base.plus(Duration.ofMinutes(i))).outcome())
          .isEqualTo(Outcome.COUNTED);
    }
    // The oldest name fails again last: its latest failure now makes it the newest.
    assertThat(small.recordFailure(names.get(0), base.plus(Duration.ofMinutes(10))).outcome())
        .isEqualTo(Outcome.COUNTED);
    assertThat(totalRows()).isGreaterThanOrEqualTo(names.size());
    try {
      SessionCleanupJob job =
          new SessionCleanupJob(
              server.bean(SessionRepository.class),
              small,
              Clock.fixed(Instant.EPOCH.plus(Duration.ofDays(8)), ZoneOffset.UTC));
      job.run();
      assertThat(totalRows()).as("cut back to the bound").isEqualTo(cap);
      assertThat(storedFailures(names.get(0))).isEqualTo(2);
      assertThat(storedFailures(names.get(1))).as("oldest last failure: deleted").isNull();
      assertThat(storedFailures(names.get(2))).as("deleted").isNull();
      assertThat(storedFailures(names.get(3))).isEqualTo(1);
      assertThat(storedFailures(names.get(4))).isEqualTo(1);
      assertThat(small.prune()).as("nothing more to delete").isZero();
      assertThat(server.bean(UnknownNameLockout.class).prune())
          .as("the server's bound is far above")
          .isZero();
      assertThat(UnknownNameLockout.MAX_ROWS).isEqualTo(100_000);
    } finally {
      deleteRows(names);
    }
  }
}
