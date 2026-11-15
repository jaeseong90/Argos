/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.auth.session;

import io.argos.server.auth.password.LockoutPolicy;
import io.argos.server.auth.token.SecureTokens;
import io.argos.server.storage.postgres.auth.AuthTransactions;
import io.argos.server.storage.postgres.auth.UnknownNameRepository;
import io.argos.server.storage.postgres.auth.UserRepository;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The lockout of user names that do not exist (16 §7.9, SEC-48, WO-24 "사용자 열거 방지"). Repeated
 * failures on an existing name end in 423 {@code account_locked}; if an unknown name answered 401
 * forever, the tenth attempt would tell whether a name exists. So an unknown name counts failures
 * and locks by the rule of {@code LoginService#recordFailure} for {@code users}, with the same
 * {@link LockoutPolicy}, in PostgreSQL ({@code login_failures_unknown}, V002, keyed by the SHA-256
 * of the name):
 *
 * <ul>
 *   <li>the counter lives as long as an account's: it survives restarts, is shared by every node,
 *       and is not forgotten with time (an account's counter is reset only by a successful login, a
 *       password change or reset, or an administrator unlock, which an unknown name never gets);
 *   <li>the database round trips match an account's, statement for statement. {@code LoginService}
 *       reads the lockout of both kinds of name in its one lookup ({@link
 *       UserRepository#findForLogin}), so a locked name, known or not, is refused without any
 *       write. A failure that counts is one transaction with two statements and the commit: for an
 *       account a row-locking read of {@code users} and an update, here an upsert that creates or
 *       row-locks the name's row ({@link UnknownNameRepository#lockForFailure}) and an update. The
 *       statements are not the same ones (another table; an insert on a name's first failure), so
 *       their timing is close but not identical; the bcrypt check in front of them dominates.
 *   <li>the failure that locks writes an {@code ACCOUNT_LOCKED} audit row for both kinds of name.
 * </ul>
 *
 * <p>What remains: the table is bounded. {@link #prune()} (run by {@link SessionCleanupJob}) keeps
 * the {@value #MAX_ROWS} names with the latest failures and deletes the others, so a flood of more
 * distinct names than that (at most 20 login attempts per minute and address, 16 §7.13) can evict
 * an unknown name's counter, where an account would keep its counter.
 */
@Component
public class UnknownNameLockout {

  /** Names kept; the ones with the oldest last failure go first. */
  public static final long MAX_ROWS = 100_000;

  /** What a failed login of an unknown name did. */
  public enum Outcome {
    /** The name was locked already: nothing counted (as for a locked account). */
    LOCKED,
    /** One more failure, below the limit. */
    COUNTED,
    /** This failure reached the limit and locks the name. */
    LOCKS
  }

  /**
   * The result of {@link #recordFailure}.
   *
   * @param outcome what the failure did
   * @param lockedUntil the end of the lockout this failure started ({@link Outcome#LOCKS}) or that
   *     was running ({@link Outcome#LOCKED}); null for {@link Outcome#COUNTED}
   */
  public record Failure(Outcome outcome, Instant lockedUntil) {}

  private final LockoutPolicy policy;
  private final UnknownNameRepository names;
  private final AuthTransactions transactions;
  private final long maxRows;

  @Autowired
  public UnknownNameLockout(
      LockoutPolicy policy, UnknownNameRepository names, AuthTransactions transactions) {
    this(policy, names, transactions, MAX_ROWS);
  }

  /** A lockout that keeps at most {@code maxRows} names (tests). */
  public UnknownNameLockout(
      LockoutPolicy policy,
      UnknownNameRepository names,
      AuthTransactions transactions,
      long maxRows) {
    if (maxRows < 1) {
      throw new IllegalArgumentException("maxRows must be positive");
    }
    this.policy = policy;
    this.names = names;
    this.transactions = transactions;
    this.maxRows = maxRows;
  }

  /** The key of a name in {@code login_failures_unknown}: hex SHA-256 of the name as typed. */
  public static String nameHash(String name) {
    return SecureTokens.sha256Hex(name);
  }

  /** Whether the name is locked at {@code now} (a read, for tests and diagnostics). */
  public boolean locked(String name, Instant now) {
    return names.lockedUntil(nameHash(name)).filter(until -> until.isAfter(now)).isPresent();
  }

  /**
   * A failed login of an unknown name, in one transaction: a locked name stays as it is (the caller
   * has normally refused it from its lookup already, without calling this; here only a lock set
   * since that lookup is seen); otherwise the failure is counted by the rule of {@code
   * LoginService#recordFailure}.
   */
  public Failure recordFailure(String name, Instant now) {
    String key = nameHash(name);
    return transactions.inTransaction(
        () -> {
          UnknownNameRepository.State current = names.lockForFailure(key, now);
          if (current.lockedUntil() != null && current.lockedUntil().isAfter(now)) {
            return new Failure(Outcome.LOCKED, current.lockedUntil());
          }
          int failures = current.failedLogins() + 1;
          int level = current.lockoutLevel();
          if (failures >= policy.failures()) {
            Instant until = now.plus(policy.duration(level));
            names.updateFailure(key, 0, until, Math.min(level + 1, Short.MAX_VALUE), now);
            return new Failure(Outcome.LOCKS, until);
          }
          names.updateFailure(key, failures, null, level, now);
          return new Failure(Outcome.COUNTED, null);
        });
  }

  /**
   * Deletes the names beyond the bound, oldest last failure first.
   *
   * @return deleted names
   */
  public int prune() {
    return names.deleteAllButLatest(maxRows);
  }
}
