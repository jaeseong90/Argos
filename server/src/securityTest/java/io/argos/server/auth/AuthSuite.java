/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.auth;

import io.argos.server.auth.session.SessionRevokedEvent;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.auth.ArgosTestServer;
import io.argos.server.testing.auth.MutableClock;
import io.argos.server.testing.auth.TestUsers;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.context.ApplicationListener;

/**
 * What the session tests of the security suite share in one JVM: one {@link ServerTestEnvironment}
 * (real PostgreSQL and ClickHouse) and one real server on a {@link MutableClock}, with kiosk
 * sessions on and the rate limits out of the way (lockout tests need many logins per minute; the
 * limits have their own tests). Tests create their own users ({@link TestUsers}), so they do not
 * meet; the clock only ever moves forward, and tests compare against it, not against absolute
 * times.
 */
final class AuthSuite {

  private static ServerTestEnvironment environment;
  private static ArgosTestServer server;
  private static final MutableClock CLOCK = new MutableClock();
  private static final List<SessionRevokedEvent> EVENTS = new CopyOnWriteArrayList<>();

  private AuthSuite() {}

  static synchronized ServerTestEnvironment environment() {
    if (environment == null) {
      environment = ServerTestEnvironment.start();
      Runtime.getRuntime()
          .addShutdownHook(
              Thread.ofPlatform()
                  .unstarted(
                      () -> {
                        if (server != null) {
                          server.close();
                        }
                        environment.close();
                      }));
    }
    return environment;
  }

  static synchronized ArgosTestServer server() {
    if (server == null) {
      server = ArgosTestServer.start(environment(), relaxedLimits(), CLOCK.installer());
      server
          .context()
          .addApplicationListener(
              ApplicationListener.forPayload((SessionRevokedEvent e) -> EVENTS.add(e)));
    }
    return server;
  }

  static MutableClock clock() {
    server();
    return CLOCK;
  }

  static TestUsers users() {
    // Users live in the schema the suite server's start migrates: start it first, whatever test
    // runs first (a test that created a user before any server start depended on the class order).
    server();
    return new TestUsers(environment());
  }

  /** Every {@link SessionRevokedEvent} of the server, in order. */
  static List<SessionRevokedEvent> events() {
    server();
    return EVENTS;
  }

  /** The settings of the suite's server. */
  static Map<String, Object> relaxedLimits() {
    return Map.of(
        "argos.ratelimit.login-per-ip-per-minute", "100000",
        "argos.ratelimit.api-per-user-per-second", "100000",
        "argos.ratelimit.api-burst-per-user", "100000",
        "argos.security.kiosk.enabled", "true");
  }

  /** The server log of this test JVM (the file of {@code log4j2-spring.xml}). */
  static String serverLog() {
    Path file = Paths.get(System.getProperty("LOG_PATH"), "argos-server.log");
    try {
      return Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
