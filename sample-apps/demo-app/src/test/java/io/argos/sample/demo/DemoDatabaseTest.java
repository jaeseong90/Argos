/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The database wait of the demo app: the demo database may start after the app (17 §3.9). */
class DemoDatabaseTest {

  /** A clock the fake sleeper moves forward. */
  private static final class StepClock extends Clock {
    private Instant now = Instant.parse("2026-11-25T00:00:00Z");

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }

    void advance(Duration d) {
      now = now.plus(d);
    }
  }

  @Test
  @DisplayName("Not ready while the database refuses; ready after the first successful attempt")
  void retriesUntilTheDatabaseAnswers() {
    StepClock clock = new StepClock();
    AtomicInteger calls = new AtomicInteger();
    DemoDatabase db =
        new DemoDatabase(
            () -> {
              if (calls.incrementAndGet() < 4) {
                throw new SQLException("Connection to demo-db:5432 refused");
              }
            },
            Duration.ofMinutes(2),
            clock,
            clock::advance);
    assertThat(db.isReady()).isFalse();
    db.initialize();
    assertThat(db.isReady()).isTrue();
    assertThat(calls).hasValue(4);
  }

  @Test
  @DisplayName("Gives up after DEMO_DB_WAIT with the last error (the app then stops)")
  void givesUpAfterTheWait() {
    StepClock clock = new StepClock();
    AtomicInteger calls = new AtomicInteger();
    DemoDatabase db =
        new DemoDatabase(
            () -> {
              calls.incrementAndGet();
              throw new SQLException("FATAL: password authentication failed for user \"demo\"");
            },
            Duration.ofSeconds(10),
            clock,
            clock::advance);
    assertThatThrownBy(db::initialize)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not ready after 10s")
        .hasMessageContaining("password authentication failed");
    assertThat(db.isReady()).isFalse();
    // 10 s with a 2 s delay: attempts at 0, 2, 4, 6, 8 and 10 s.
    assertThat(calls).hasValue(6);
  }
}
