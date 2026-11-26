/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.admin.token;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The revocation watcher (ADR-0010 C-238 (14)): every {@value #PERIOD_MILLIS} ms it applies the
 * token changes of other processes to this server ({@link
 * TokenService#endSessionsOfUnauthenticated}). {@code argos-admin token revoke} runs outside the
 * server and changes the {@code agent_tokens} row only; within one period this server drops the
 * token from its cache and ends the token's open sessions with {@code
 * SessionClose{TOKEN_REVOKED_CLOSE}} and {@code UNAUTHENTICATED}, the same bar as a revocation
 * through the administration API (16 §2.4 "폐기 전파", SEC-04, T-SEC-02 and T-SEC-05: within 5 s).
 *
 * <p>One PostgreSQL query per run, over the token ids of the live sessions and the revocations of
 * the last {@link #RECENT_REVOCATIONS} (longer than the 60 s cache lifetime, so a revoked token
 * without a session here is dropped from the cache too). The period is a constant: there is no
 * {@code argos.*} setting.
 *
 * <p>A failed run (PostgreSQL unreachable) logs a WARN with the exception class only, at most once
 * per {@link #WARN_INTERVAL} while the failure lasts, and the next run retries; the first run that
 * succeeds again logs one INFO line. No audit record: the process that changed the token recorded
 * it ({@code TOKEN_REVOKED}); the INFO line of a closed session names the token prefix and the
 * number of sessions only.
 */
@Component
public class TokenRevocationWatcher {

  /** Milliseconds between the end of one run and the start of the next. */
  public static final long PERIOD_MILLIS = 2_000;

  /** How far back a revocation is applied to the cache (the cache keeps entries 60 s). */
  public static final Duration RECENT_REVOCATIONS = Duration.ofMinutes(5);

  /** Least time between two WARN lines of one failure streak. */
  static final Duration WARN_INTERVAL = Duration.ofMinutes(1);

  private static final Logger LOG = LoggerFactory.getLogger(TokenRevocationWatcher.class);

  private final TokenService tokens;
  private final LongSupplier nanoTime;

  /** Failed runs in a row (one run at a time: the schedule has a fixed delay). */
  private int failures;

  /** {@link #nanoTime} of the last WARN line of the current failure streak. */
  private long lastWarnNanos;

  @Autowired
  public TokenRevocationWatcher(TokenService tokens) {
    this(tokens, System::nanoTime);
  }

  /** A watcher with another monotonic time source (tests). */
  TokenRevocationWatcher(TokenService tokens, LongSupplier nanoTime) {
    this.tokens = tokens;
    this.nanoTime = nanoTime;
  }

  /** One run (the schedule). */
  @Scheduled(fixedDelay = PERIOD_MILLIS, initialDelay = PERIOD_MILLIS)
  public void run() {
    check();
  }

  /**
   * One run.
   *
   * @return the result, or null when the run failed
   */
  TokenService.@Nullable Sweep check() {
    TokenService.Sweep sweep;
    try {
      sweep = tokens.endSessionsOfUnauthenticated(RECENT_REVOCATIONS);
    } catch (RuntimeException e) {
      failed(e);
      return null;
    }
    if (failures > 0) {
      logRecovered(failures);
      failures = 0;
    }
    return sweep;
  }

  private void failed(RuntimeException e) {
    long now = nanoTime.getAsLong();
    failures++;
    if (failures == 1 || now - lastWarnNanos >= WARN_INTERVAL.toNanos()) {
      lastWarnNanos = now;
      LOG.warn(
          "Agent token revocation check failed ({} run(s) in a row): {}; retrying every {} ms",
          failures,
          e.getClass().getSimpleName(),
          PERIOD_MILLIS);
    }
  }

  private static void logRecovered(int failed) {
    LOG.info(
        "Agent token revocation check works again after {} failed run(s) (about {} s)",
        failed,
        TimeUnit.MILLISECONDS.toSeconds(failed * PERIOD_MILLIS));
  }

  /** Failed runs in a row (tests). */
  int failures() {
    return failures;
  }
}
