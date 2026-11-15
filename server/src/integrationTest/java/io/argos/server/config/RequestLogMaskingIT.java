/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.ServerApplication;
import io.argos.server.testing.ServerTestEnvironment;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The server log masks credentials of request headers (12 §7.1, 17 §11.3) when an operator turns on
 * Spring's request logging ({@code DispatcherServlet} at TRACE with {@code
 * spring.mvc.log-request-details}), which prints every header as {@code Authorization:[Bearer
 * ...]}: the real server against PostgreSQL 16 and ClickHouse (Testcontainers), a generated Bearer
 * token on the HTTP port (8080) and the metrics token on the management port (8081); neither token
 * reaches the console or the log file. The 8080 line shows the masked header ({@code
 * Authorization:[Bearer ****]}); the management port's own {@code DispatcherServlet} does not take
 * {@code spring.mvc.log-request-details} and prints {@code headers={masked}}, and its token is
 * checked all the same.
 */
@Tag("req:SEC-06")
@Tag("req:SEC-55")
@ExtendWith(OutputCaptureExtension.class)
class RequestLogMaskingIT {

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  @TempDir Path tmp;

  private static String newSecret() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static int freePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      return socket.getLocalPort();
    }
  }

  private static HttpResponse<String> get(int port, String path, String bearer)
      throws IOException, InterruptedException {
    return HTTP.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .header("Authorization", "Bearer " + bearer)
            .timeout(Duration.ofSeconds(20))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  @DisplayName(
      "T-SEC-06b request logging at TRACE with details: Bearer tokens of 8080 and 8081 requests are"
          + " in neither the console nor the log file")
  void bearerTokensMasked(CapturedOutput output) throws Exception {
    Path log = IntegrationLogs.logFile();
    String requestToken = newSecret();
    String metricsToken = newSecret();
    Path tokenFile =
        Files.writeString(
            tmp.resolve("metrics-token"), metricsToken + "\n", StandardCharsets.UTF_8);
    try (ServerTestEnvironment env = ServerTestEnvironment.start()) {
      int http = freePort();
      int management = freePort();
      Map<String, Object> properties = new LinkedHashMap<>(env.springProperties());
      properties.put("server.address", "127.0.0.1");
      properties.put("server.port", Integer.toString(http));
      properties.put("management.server.address", "127.0.0.1");
      properties.put("management.server.port", Integer.toString(management));
      properties.put("argos.web.public-url", "http://127.0.0.1:" + http);
      properties.put("argos.metrics.token-file", tokenFile.toString());
      properties.put("argos.metrics.allow-cidrs[0]", "127.0.0.1/32");
      properties.put("logging.level.org.springframework.web.servlet.DispatcherServlet", "TRACE");
      properties.put("spring.mvc.log-request-details", "true");
      SpringApplication app = new SpringApplication(ServerApplication.class);
      app.setDefaultProperties(properties);

      try (ConfigurableApplicationContext server = app.run()) {
        assertThat(server.isActive()).isTrue();
        assertThat(get(http, "/api/v1/x", requestToken).statusCode()).isEqualTo(404);
        assertThat(get(management, "/actuator/prometheus", metricsToken).statusCode())
            .isEqualTo(200);
      }
    }

    String console = output.getAll();
    String file = Files.readString(log, StandardCharsets.UTF_8);
    // Not vacuous: the requests were logged with their headers, masked.
    assertThat(console)
        .contains("GET \\\"/api/v1/x\\\"")
        .contains("GET \\\"/actuator/prometheus\\\"")
        .containsIgnoringCase("authorization:[Bearer ****]");
    assertThat(file).containsIgnoringCase("authorization:[Bearer ****]");
    for (String token : new String[] {requestToken, metricsToken}) {
      assertThat(console).doesNotContain(token);
      assertThat(file).doesNotContain(token);
    }
  }
}
