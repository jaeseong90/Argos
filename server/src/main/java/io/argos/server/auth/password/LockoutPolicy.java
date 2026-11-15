/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.auth.password;

import io.argos.server.config.ArgosProperties;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Login lockout (16 §7.9, SEC-48, 12 §10 {@code argos.auth.lockout.*}): {@code failures} (10)
 * consecutive failed logins lock the account for {@code base-minutes} (15); every further lockout
 * without a successful login or an administrator unlock in between doubles it, up to four times the
 * base (15, 30, 60 minutes). A successful login and an unlock reset the level ({@code
 * users.lockout_level}, V002).
 */
@Component
public class LockoutPolicy {

  /** The doubling stops after this many lockouts (base x 4 = 60 minutes). */
  static final int MAX_DOUBLINGS = 2;

  private final int failures;
  private final Duration base;

  public LockoutPolicy(ArgosProperties properties) {
    this.failures = properties.auth().lockout().failures();
    this.base = Duration.ofMinutes(properties.auth().lockout().baseMinutes());
  }

  /** Consecutive failures that lock the account. */
  public int failures() {
    return failures;
  }

  /**
   * The duration of a lockout.
   *
   * @param level lockouts before this one since the last reset (0 for the first)
   */
  public Duration duration(int level) {
    int doublings = Math.max(0, Math.min(level, MAX_DOUBLINGS));
    return base.multipliedBy(1L << doublings);
  }
}
