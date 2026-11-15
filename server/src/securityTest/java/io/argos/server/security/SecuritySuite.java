/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.security;

import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.auth.ApiTestClient;
import io.argos.server.testing.auth.ArgosTestServer;
import io.argos.server.web.security.RateLimiter;
import io.argos.servertest.edge.EdgeEndpoints;
import io.argos.servertest.security.HandlerReachedMarker;
import io.argos.servertest.security.HeaderAuthentication;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the classes of the security suite share in one JVM: one {@link ServerTestEnvironment} (real
 * PostgreSQL and ClickHouse, started once, containers are expensive on the shared machine) and one
 * real server with the default limits plus the test-only endpoints ({@link EdgeEndpoints}) and
 * identities ({@link HeaderAuthentication}).
 */
final class SecuritySuite {

  /** The metrics token of {@link #server()}, generated at run time. */
  private static String metricsToken;

  private static final SecureRandom RANDOM = new SecureRandom();

  private static ServerTestEnvironment environment;
  private static ArgosTestServer server;
  private static final List<ArgosTestServer> OTHERS = new ArrayList<>();

  private SecuritySuite() {}

  /** The environment of this JVM. */
  static synchronized ServerTestEnvironment environment() {
    if (environment == null) {
      environment = ServerTestEnvironment.start();
      Runtime.getRuntime()
          .addShutdownHook(Thread.ofPlatform().unstarted(SecuritySuite::closeEverything));
    }
    return environment;
  }

  /** The shared server with the default limits. */
  static synchronized ArgosTestServer server() {
    if (server == null) {
      metricsToken = newToken();
      Map<String, Object> properties = new LinkedHashMap<>();
      properties.put("argos.metrics.allow-cidrs[0]", "127.0.0.1/32");
      properties.put("argos.metrics.allow-cidrs[1]", "::1/128");
      properties.put("argos.metrics.token-file", writeTokenFile(metricsToken).toString());
      server = start(properties);
    }
    return server;
  }

  /** The metrics token of {@link #server()}. */
  static synchronized String metricsToken() {
    server();
    return metricsToken;
  }

  /**
   * Another server on the environment of this JVM, with the test-only endpoints and identities and
   * extra properties; closed at the end of the JVM unless the test closes it.
   */
  static synchronized ArgosTestServer start(Map<String, ?> properties) {
    ArgosTestServer started =
        ArgosTestServer.start(
            environment(),
            properties,
            app ->
                app.addPrimarySources(
                    List.of(
                        EdgeEndpoints.class,
                        HeaderAuthentication.class,
                        HandlerReachedMarker.class)));
    OTHERS.add(started);
    return started;
  }

  /** A client of {@code on} as a role (null: anonymous) with a CSRF cookie. */
  static ApiTestClient client(ArgosTestServer on, String role) {
    ApiTestClient client = on.client();
    client.cookies().put(ApiTestClient.CSRF_COOKIE, newToken(), "/");
    if (role != null) {
      client.header(HeaderAuthentication.ROLE_HEADER, role);
    }
    return client;
  }

  /** Every bucket full again, so that a test does not depend on the ones before it. */
  static void resetLimits(ArgosTestServer on) {
    on.bean(RateLimiter.class).reset();
  }

  /** 32 random bytes, base64url: the shape of every token of the server. */
  static String newToken() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /** The server log of this test JVM. */
  static Path logFile() {
    return Path.of(System.getProperty("LOG_PATH"), "argos-server.log");
  }

  private static Path writeTokenFile(String token) {
    try {
      Path directory = Files.createTempDirectory("argos-security-suite");
      Path file = directory.resolve("metrics-token");
      Files.writeString(file, token);
      return file;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static synchronized void closeEverything() {
    OTHERS.forEach(
        started -> {
          try {
            started.close();
          } catch (RuntimeException ignored) {
            // the JVM is going down: the containers go with it
          }
        });
    if (environment != null) {
      environment.close();
    }
  }
}
