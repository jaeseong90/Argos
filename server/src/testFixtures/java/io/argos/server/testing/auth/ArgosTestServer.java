/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.testing.auth;

import io.argos.server.ServerApplication;
import io.argos.server.testing.ServerTestEnvironment;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The real server ({@link ServerApplication}, every start phase) on loopback ports against a {@link
 * ServerTestEnvironment}, for the authentication tests of WO-24 and the REST tests after it.
 *
 * <pre>{@code
 * try (ServerTestEnvironment env = ServerTestEnvironment.start();
 *     ArgosTestServer server = ArgosTestServer.start(env)) {
 *   ApiTestClient client = server.client();
 *   ...
 * }
 * }</pre>
 *
 * Properties: the environment's database settings, {@code server.address} and {@code
 * management.server.address} 127.0.0.1 on free ports, {@code argos.web.public-url} = the HTTP
 * port's URL (the Origin the CSRF filter expects), plus the caller's extras, which win. The server
 * log goes to {@code ${LOG_PATH}} (set by the Gradle test tasks below {@code server/build}).
 */
public final class ArgosTestServer implements AutoCloseable {

  private final ConfigurableApplicationContext context;
  private final int port;
  private final int managementPort;
  private final String baseUrl;

  private ArgosTestServer(
      ConfigurableApplicationContext context, int port, int managementPort, String baseUrl) {
    this.context = context;
    this.port = port;
    this.managementPort = managementPort;
    this.baseUrl = baseUrl;
  }

  /** Starts the server with the default properties. */
  public static ArgosTestServer start(ServerTestEnvironment env) {
    return start(env, Map.of());
  }

  /** Starts the server with extra properties (they win over the defaults). */
  public static ArgosTestServer start(ServerTestEnvironment env, Map<String, ?> extra) {
    return start(env, extra, app -> {});
  }

  /**
   * Starts the server.
   *
   * @param extra properties that win over the defaults
   * @param customizer changes to the application before it runs (initializers, extra sources)
   */
  public static ArgosTestServer start(
      ServerTestEnvironment env, Map<String, ?> extra, Consumer<SpringApplication> customizer) {
    ensureLogPath();
    int port = freePort();
    int management = freePort();
    String baseUrl = "http://127.0.0.1:" + port;
    Map<String, Object> properties = new LinkedHashMap<>(env.springProperties());
    properties.put("server.address", "127.0.0.1");
    properties.put("server.port", Integer.toString(port));
    properties.put("management.server.address", "127.0.0.1");
    properties.put("management.server.port", Integer.toString(management));
    properties.put("argos.web.public-url", baseUrl);
    properties.putAll(extra);
    SpringApplication app = new SpringApplication(ServerApplication.class);
    app.setDefaultProperties(properties);
    customizer.accept(app);
    return new ArgosTestServer(app.run(), port, management, baseUrl);
  }

  /** A new browser-like client of this server (empty cookie jar). */
  public ApiTestClient client() {
    return ApiTestClient.of(baseUrl);
  }

  /** The application context. */
  public ConfigurableApplicationContext context() {
    return context;
  }

  /** A bean of the server. */
  public <T> T bean(Class<T> type) {
    return context.getBean(type);
  }

  /** The HTTP port (8080 in production). */
  public int port() {
    return port;
  }

  /** The management port (8081 in production). */
  public int managementPort() {
    return managementPort;
  }

  /** {@code http://127.0.0.1:<port>}, also {@code argos.web.public-url}. */
  public String baseUrl() {
    return baseUrl;
  }

  /** Stops the server. */
  @Override
  public void close() {
    context.close();
  }

  private static int freePort() {
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      return socket.getLocalPort();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Points {@code LOG_PATH} below {@code server/build} when the test task did not set it. */
  private static synchronized void ensureLogPath() {
    String current = System.getProperty("LOG_PATH");
    if (current == null || current.isBlank()) {
      String root = System.getProperty("argos.repositoryRoot", ".");
      Path dir = Paths.get(root, "server", "build", "test-logs", "fixtures").toAbsolutePath();
      System.setProperty("LOG_PATH", dir.toString());
    }
  }
}
