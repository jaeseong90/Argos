/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.clickhouse;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import io.argos.server.ServerApplication;
import io.argos.server.config.IntegrationLogs;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.storage.clickhouse.Account;
import io.argos.storage.clickhouse.ArgosClickHouse;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.DockerClientFactory;

/**
 * Readiness follows ClickHouse (17 §3.5: readiness needs the PostgreSQL and ClickHouse connections
 * and completed migrations): the real server against PostgreSQL 16 and ClickHouse (Testcontainers,
 * own containers for this class) is ready while ClickHouse answers, not ready (503, no details)
 * while the ClickHouse container is paused, and ready again after it is unpaused. Liveness does not
 * follow the database. The container is unpaused in any case before the test ends.
 */
@Tag("req:DSN-12-15")
@Tag("req:F-S20")
class ClickHouseReadinessIT {

  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  private static int freePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      return socket.getLocalPort();
    }
  }

  private static HttpResponse<String> get(int port, String path)
      throws IOException, InterruptedException {
    return HTTP.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(20))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  /** Polls readiness until it answers {@code status} or {@code timeout} passes; the last answer. */
  private static HttpResponse<String> awaitReadiness(int port, int status, Duration timeout)
      throws IOException, InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    HttpResponse<String> last = get(port, "/actuator/health/readiness");
    while (last.statusCode() != status && System.nanoTime() < deadline) {
      TimeUnit.MILLISECONDS.sleep(250);
      last = get(port, "/actuator/health/readiness");
    }
    return last;
  }

  /**
   * The id of the Docker container that publishes ClickHouse's HTTP port on {@code hostPort}. The
   * daemon is shared: containers of other test runs that are being created or removed may report no
   * ports at all (null), and they are not this one.
   */
  private static String clickHouseContainerId(DockerClient docker, int hostPort) {
    List<Container> matches =
        docker.listContainersCmd().exec().stream()
            .filter(
                c ->
                    c.getPorts() != null
                        && Arrays.stream(c.getPorts())
                            .anyMatch(
                                p ->
                                    p.getPrivatePort() != null
                                        && p.getPrivatePort() == ArgosClickHouse.HTTP_PORT
                                        && p.getPublicPort() != null
                                        && p.getPublicPort() == hostPort))
            .toList();
    assertThat(matches).as("the ClickHouse container of this test").hasSize(1);
    return matches.get(0).getId();
  }

  @Test
  @DisplayName(
      "T-SV-001b readiness is UP while ClickHouse answers, DOWN (503, no details) while it is paused, UP"
          + " again after unpause; liveness stays UP")
  void readinessFollowsClickHouse() throws Exception {
    long logStart = IntegrationLogs.offset();
    try (ServerTestEnvironment env = ServerTestEnvironment.start()) {
      int http = freePort();
      int management = freePort();
      Map<String, Object> properties = new LinkedHashMap<>(env.springProperties());
      properties.put("server.address", "127.0.0.1");
      properties.put("server.port", Integer.toString(http));
      properties.put("management.server.address", "127.0.0.1");
      properties.put("management.server.port", Integer.toString(management));
      properties.put("argos.web.public-url", "http://127.0.0.1:" + http);
      SpringApplication app = new SpringApplication(ServerApplication.class);
      app.setDefaultProperties(properties);

      DockerClient docker = DockerClientFactory.instance().client();
      String container = clickHouseContainerId(docker, URI.create(env.clickHouseUrl()).getPort());
      try (ConfigurableApplicationContext server = app.run()) {
        assertThat(server.isActive()).isTrue();
        HttpResponse<String> ready = get(management, "/actuator/health/readiness");
        assertThat(ready.statusCode()).isEqualTo(200);
        assertThat(ready.body()).isEqualTo("{\"status\":\"UP\"}");

        HttpResponse<String> paused;
        HttpResponse<String> livenessWhilePaused;
        docker.pauseContainerCmd(container).exec();
        try {
          paused = awaitReadiness(management, 503, Duration.ofSeconds(30));
          livenessWhilePaused = get(management, "/actuator/health/liveness");
        } finally {
          docker.unpauseContainerCmd(container).exec();
        }
        assertThat(paused.statusCode()).as(paused.body()).isEqualTo(503);
        assertThat(paused.body()).isEqualTo("{\"status\":\"DOWN\"}");
        assertThat(livenessWhilePaused.statusCode()).isEqualTo(200);

        HttpResponse<String> again = awaitReadiness(management, 200, Duration.ofSeconds(60));
        assertThat(again.statusCode()).as(again.body()).isEqualTo(200);
        assertThat(again.body()).isEqualTo("{\"status\":\"UP\"}");
      }

      String run = IntegrationLogs.since(logStart);
      assertThat(run)
          .contains("ClickHouse readiness check failed, readiness is DOWN: ")
          .contains("ClickHouse answers again, readiness check is UP");
      for (Account a : Account.values()) {
        assertThat(run).doesNotContain(env.password(a));
      }
      for (PgAccount a : PgAccount.values()) {
        assertThat(run).doesNotContain(env.password(a));
      }
    }
  }
}
