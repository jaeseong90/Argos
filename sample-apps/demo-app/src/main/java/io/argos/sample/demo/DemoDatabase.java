/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.demo;

import io.argos.sample.common.db.DbKind;
import io.argos.sample.common.db.ShopSchema;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Schema and data of one role (17 §3.9): {@code CREATE SCHEMA IF NOT EXISTS demo_<role>}, then the
 * shop tables and the deterministic shop data (common: ShopSchema, 1,000 orders with 20 items
 * each), all idempotent. The database container may still be starting: the app retries until {@code
 * DEMO_DB_WAIT} has passed, and {@code GET /healthz} answers 503 until the data is ready.
 */
public final class DemoDatabase {

  private static final Logger LOG = LoggerFactory.getLogger(DemoDatabase.class);

  /** A PostgreSQL identifier the app creates; derived from the role, never from user input. */
  private static final Pattern SCHEMA = Pattern.compile("[a-z_][a-z0-9_]{0,62}");

  /** One initialization attempt (replaced in tests). */
  @FunctionalInterface
  interface Attempt {
    void run() throws SQLException;
  }

  /** Sleeps between attempts (replaced in tests). */
  @FunctionalInterface
  interface Sleeper {
    void sleep(Duration duration) throws InterruptedException;
  }

  static final Duration RETRY_DELAY = Duration.ofSeconds(2);

  private final AtomicBoolean ready = new AtomicBoolean();
  private final Attempt attempt;
  private final Duration wait;
  private final Clock clock;
  private final Sleeper sleeper;

  DemoDatabase(Attempt attempt, Duration wait, Clock clock, Sleeper sleeper) {
    this.attempt = attempt;
    this.wait = wait;
    this.clock = clock;
    this.sleeper = sleeper;
  }

  /** The production initializer: schema of the role, shop tables and data on {@code dataSource}. */
  public static DemoDatabase of(DataSource dataSource, DemoSettings settings) {
    String schema = settings.schema();
    if (!SCHEMA.matcher(schema).matches()) {
      throw new IllegalArgumentException("Invalid schema name " + schema);
    }
    Attempt attempt =
        () -> {
          try (Connection c = dataSource.getConnection();
              Statement st = c.createStatement()) {
            st.execute("CREATE SCHEMA IF NOT EXISTS " + schema);
          }
          ShopSchema.initialize(dataSource, DbKind.POSTGRES);
        };
    return new DemoDatabase(
        attempt, settings.dbWait(), Clock.systemUTC(), d -> Thread.sleep(d.toMillis()));
  }

  /** Whether schema and data are ready (the health check and the batch read it). */
  public boolean isReady() {
    return ready.get();
  }

  /**
   * Runs the attempt until it succeeds or the wait time has passed.
   *
   * @throws IllegalStateException when the database is not ready in time (the app then stops)
   */
  public void initialize() {
    Instant deadline = clock.instant().plus(wait);
    int attempts = 0;
    while (true) {
      attempts++;
      try {
        attempt.run();
        ready.set(true);
        LOG.info("Demo data ready after {} attempt(s)", attempts);
        return;
      } catch (SQLException | RuntimeException e) {
        if (!clock.instant().isBefore(deadline)) {
          throw new IllegalStateException(
              "Demo database not ready after " + wait.toSeconds() + "s: " + describe(e), e);
        }
        LOG.warn("Demo database not ready yet (attempt {}): {}", attempts, describe(e));
      }
      try {
        sleeper.sleep(RETRY_DELAY);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Interrupted while waiting for the demo database", e);
      }
    }
  }

  /** Exception class and message only (JDBC messages name hosts and users, not passwords). */
  private static String describe(Throwable e) {
    Throwable root = e;
    for (int depth = 0; depth < 16 && root.getCause() != null; depth++) {
      root = root.getCause();
    }
    return root.getClass().getSimpleName() + ": " + root.getMessage();
  }
}
