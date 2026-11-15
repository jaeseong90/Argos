/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.testing.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * A {@link Clock} that tests move by hand, for access-token expiry, session expiry, lockout
 * durations and the setup-token lifetime. It stands still unless {@link #advance(Duration)} moves
 * it, and starts at the real time in milliseconds (PostgreSQL keeps microseconds, so stored times
 * compare equal), so rows and tokens have ordinary dates. {@link #installer()} makes it the {@code
 * Clock} bean of a server under test ({@code AuthConfiguration} registers the system clock only
 * when no other {@code Clock} bean exists):
 *
 * <pre>{@code
 * MutableClock clock = new MutableClock();
 * ArgosTestServer server = ArgosTestServer.start(env, Map.of(), clock.installer());
 * clock.advance(Duration.ofMinutes(16));
 * }</pre>
 */
public final class MutableClock extends Clock {

  private final AtomicReference<Instant> now =
      new AtomicReference<>(Instant.now().truncatedTo(ChronoUnit.MILLIS));

  /** Moves the clock forward. */
  public void advance(Duration by) {
    now.updateAndGet(t -> t.plus(by));
  }

  /** The customizer that registers this clock as the server's {@code Clock} bean. */
  public Consumer<SpringApplication> installer() {
    return app ->
        app.addInitializers(
            (ConfigurableApplicationContext context) ->
                context.getBeanFactory().registerSingleton("testClock", this));
  }

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
    return now.get();
  }
}
