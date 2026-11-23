/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.realtime;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The real time plus an offset that a test moves forward: the server's {@code Clock} bean for the
 * realtime tests, which need time to run (bundles every 100 ms, counters every 5 s) and also to
 * jump (a minute without heartbeat for a state change, a minute without client message for the
 * WebSocket timeout).
 */
final class OffsetClock extends Clock {

  private final AtomicLong offsetMs = new AtomicLong();

  /** Moves the clock forward. */
  void advance(Duration by) {
    offsetMs.addAndGet(by.toMillis());
  }

  /** The customizer that registers this clock as the server's {@code Clock} bean. */
  Consumer<SpringApplication> installer() {
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
  public long millis() {
    return System.currentTimeMillis() + offsetMs.get();
  }

  @Override
  public Instant instant() {
    return Instant.ofEpochMilli(millis());
  }
}
