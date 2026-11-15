/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.error;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.config.IntegrationLogs;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.auth.ApiResponse;
import io.argos.server.testing.auth.ApiTestClient;
import io.argos.server.testing.auth.ArgosTestServer;
import io.argos.servertest.errorbody.ErrorBodyEndpoints;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The error contract on the real server (14 §2.6, 16 §7.14, SEC-56; WO-20 hand-over: the body limit
 * through Jackson) and the stateless Spring Security set-up of WO-24, against PostgreSQL 16 and
 * ClickHouse (Testcontainers), with test-only endpoints ({@link ErrorBodyEndpoints}).
 *
 * <p>Requests carry what the CSRF filter of WO-24 expects of a browser (Origin of the public URL,
 * {@code Sec-Fetch-Site: same-origin}, a double-submit token in cookie and header), so these checks
 * stay valid when the API filters are in place.
 */
@Tag("req:SEC-56")
@Tag("req:SEC-49")
class ErrorBodyIT {

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final String BASE = "/api/v1/test-errors";

  private static ServerTestEnvironment env;
  private static ArgosTestServer server;
  private static long logStart;
  private static String csrfToken;

  @BeforeAll
  static void start() throws IOException {
    Path log = IntegrationLogs.logFile();
    logStart = Files.exists(log) ? Files.size(log) : 0;
    env = ServerTestEnvironment.start();
    server =
        ArgosTestServer.start(
            env, Map.of(), app -> app.addPrimarySources(List.of(ErrorBodyEndpoints.class)));
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    csrfToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  @AfterAll
  static void stop() {
    try {
      if (server != null) {
        server.close();
      }
    } finally {
      if (env != null) {
        env.close();
      }
    }
  }

  /** A browser at the server's origin with a double-submit CSRF token. */
  private static ApiTestClient client() {
    ApiTestClient client = server.client();
    client.cookies().put(ApiTestClient.CSRF_COOKIE, csrfToken, "/");
    return client;
  }

  /**
   * The checks every error response passes: JSON content type, the request id of the {@code
   * X-Request-Id} header in the body, nothing of the server's internals, no cookie and no session.
   */
  private static ApiResponse assertErrorBody(ApiResponse response, int status, String code) {
    assertThat(response.status()).as(response.toString()).isEqualTo(status);
    assertThat(response.header("Content-Type"))
        .hasValueSatisfying(
            type -> assertThat(type.toLowerCase(Locale.ROOT)).startsWith("application/json"));
    String requestId = response.header("X-Request-Id").orElseThrow();
    assertThat(response.json().path("error").path("code").asString()).isEqualTo(code);
    assertThat(response.json().path("error").path("request_id").asString()).isEqualTo(requestId);
    assertThat(response.body())
        .doesNotContain("Exception")
        .doesNotContain("java.")
        .doesNotContain("io.argos")
        .doesNotContain("SELECT")
        .doesNotContain("/opt/")
        .doesNotContain("pg-internal")
        .doesNotContain("trace")
        .doesNotContain("timestamp")
        .doesNotContain("\"path\"");
    assertThat(response.headers("Set-Cookie")).as("no cookie on an error").isEmpty();
    return response;
  }

  @Test
  @DisplayName(
      "T-SEC-56a a 2 MB chunked POST without Content-Length is 400 payload_too_large (body limit"
          + " reached inside Jackson)")
  void chunkedBodyOverLimit() throws Exception {
    String response = chunkedPost(BASE + "/echo", 2 * 1024 * 1024);
    String statusLine = response.substring(0, response.indexOf("\r\n"));
    assertThat(statusLine).startsWith("HTTP/1.1 400");
    String body = response.substring(response.indexOf("\r\n\r\n") + 4);
    if (response.toLowerCase(Locale.ROOT).contains("transfer-encoding: chunked")) {
      body = dechunk(body);
    }
    assertThat(body).contains("\"code\":\"payload_too_large\"").contains("\"request_id\":\"");
    assertThat(body).doesNotContain("Exception").doesNotContain("java.");

    // The same endpoint reads a body below the limit.
    ApiResponse ok = client().post(BASE + "/echo", Map.of("a", 1, "b", 2));
    assertThat(ok.status()).isEqualTo(200);
    assertThat(ok.body()).isEqualTo("{\"keys\":2}");
  }

  @Test
  @DisplayName(
      "T-SEC-56a an unexpected failure is 500 internal with the fixed message and the request id"
          + " only")
  void internalError() {
    ApiResponse response = assertErrorBody(client().get(BASE + "/boom"), 500, "internal");
    assertThat(response.body())
        .isEqualTo(
            "{\"error\":{\"code\":\"internal\",\"message\":\"Internal server error\","
                + "\"request_id\":\""
                + response.header("X-Request-Id").orElseThrow()
                + "\"}}");
  }

  @Test
  @DisplayName(
      "T-SEC-56a 400 invalid_request (malformed JSON, bad parameter), 401, 404, 405 and 409 share"
          + " the ErrorBody")
  void clientErrors() {
    ApiTestClient client = client();
    assertErrorBody(client.post(BASE + "/echo", "{\"not json"), 400, "invalid_request");

    ApiResponse badNumber =
        assertErrorBody(client.get(BASE + "/number?value=abc"), 400, "invalid_request");
    assertThat(badNumber.json().path("error").path("details").get(0).path("field").asString())
        .isEqualTo("value");
    assertErrorBody(client.get(BASE + "/number"), 400, "invalid_request");

    assertErrorBody(client.get(BASE + "/viewer"), 401, "unauthenticated");

    assertErrorBody(client.get("/api/v1/no-such-endpoint"), 404, "not_found");

    ApiResponse method =
        assertErrorBody(client.delete(BASE + "/viewer"), 405, "method_not_allowed");
    assertThat(method.header("Allow")).contains("GET");

    ApiResponse conflict = assertErrorBody(client.get(BASE + "/conflict"), 409, "invalid_state");
    assertThat(conflict.body())
        .contains("\"message\":\"The alert is already acknowledged\"")
        .contains("\"details\":[{\"field\":\"state\",\"reason\":\"acknowledged\"}]");
  }

  @Test
  @DisplayName(
      "a URL rejected by the Spring Security firewall is 400 invalid_request in the ErrorBody")
  void firewall() {
    assertErrorBody(client().get(BASE + "/number;x=1?value=1"), 400, "invalid_request");
  }

  @Test
  @DisplayName(
      "stateless security: no session cookie on any response, no generated password, the"
          + " management port keeps its own rules")
  void statelessAndManagement() throws Exception {
    ApiTestClient client = client();
    for (ApiResponse response :
        List.of(
            client.get(BASE + "/number?value=7"),
            client.get(BASE + "/viewer"),
            client.post(BASE + "/echo", Map.of("k", "v")),
            client.get("/"))) {
      assertThat(response.headers("Set-Cookie")).as(response.toString()).isEmpty();
    }
    assertThat(client.cookies().all())
        .extracting(c -> c.name())
        .containsExactly(ApiTestClient.CSRF_COOKIE);

    String log = newLog();
    assertThat(log).as("server log of this class").contains("Startup phase CONFIG step");
    assertThat(log).doesNotContain("generated security password").doesNotContain(csrfToken);

    HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    HttpResponse<String> health =
        http.send(
            HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + server.managementPort() + "/actuator/health"))
                .timeout(Duration.ofSeconds(10))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(health.statusCode()).isEqualTo(200);
    assertThat(health.body()).contains("\"status\":\"UP\"").doesNotContain("components");
    HttpResponse<String> env =
        http.send(
            HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + server.managementPort() + "/actuator/env"))
                .timeout(Duration.ofSeconds(10))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(env.statusCode()).as("SEC-55 unchanged").isIn(401, 404);
  }

  @Test
  @DisplayName(
      "T-SEC-49a a framework handler without a role (springdoc's default OpenAPI path) serves no"
          + " document to an anonymous client")
  void frameworkHandlersFailClosed() {
    ApiTestClient client = client();
    for (String path : List.of("/v3/api-docs", "/v3/api-docs.yaml")) {
      ApiResponse response = client.get(path);
      assertThat(response.status()).as(path).isIn(401, 403, 404);
      assertThat(response.body()).as(path).doesNotContain("openapi");
      response
          .errorCode()
          .ifPresent(code -> assertThat(code).isIn("unauthenticated", "forbidden", "not_found"));
    }
  }

  private static String newLog() throws IOException {
    byte[] all = Files.readAllBytes(IntegrationLogs.logFile());
    int from = (int) Math.min(logStart, all.length);
    return new String(all, from, all.length - from, StandardCharsets.UTF_8);
  }

  /**
   * A POST with {@code Transfer-Encoding: chunked} and no {@code Content-Length}, written on a
   * second thread (the server may answer and stop reading before the body ends; write errors are
   * expected then). Returns the raw response.
   */
  private static String chunkedPost(String path, int bodyBytes) throws Exception {
    try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), server.port())) {
      socket.setSoTimeout(60_000);
      OutputStream out = socket.getOutputStream();
      String head =
          "POST "
              + path
              + " HTTP/1.1\r\n"
              + "Host: 127.0.0.1:"
              + server.port()
              + "\r\n"
              + "Content-Type: application/json\r\n"
              + "Transfer-Encoding: chunked\r\n"
              + "Origin: "
              + server.baseUrl()
              + "\r\n"
              + "Sec-Fetch-Site: same-origin\r\n"
              + "Cookie: "
              + ApiTestClient.CSRF_COOKIE
              + "="
              + csrfToken
              + "\r\n"
              + ApiTestClient.CSRF_HEADER
              + ": "
              + csrfToken
              + "\r\n"
              + "Connection: close\r\n\r\n";
      Thread writer =
          Thread.ofPlatform()
              .start(
                  () -> {
                    try {
                      out.write(head.getBytes(StandardCharsets.US_ASCII));
                      byte[] chunk = new byte[16 * 1024];
                      // {"k":"aaaa...  - valid JSON start, so only the size can end the read
                      byte[] start = "{\"k\":\"".getBytes(StandardCharsets.US_ASCII);
                      System.arraycopy(start, 0, chunk, 0, start.length);
                      for (int i = start.length; i < chunk.length; i++) {
                        chunk[i] = 'a';
                      }
                      int sent = 0;
                      boolean first = true;
                      while (sent < bodyBytes) {
                        byte[] data = chunk;
                        if (!first) {
                          data = new byte[chunk.length];
                          java.util.Arrays.fill(data, (byte) 'a');
                        }
                        first = false;
                        out.write(
                            (Integer.toHexString(data.length) + "\r\n")
                                .getBytes(StandardCharsets.US_ASCII));
                        out.write(data);
                        out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                        sent += data.length;
                      }
                      out.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                      out.flush();
                    } catch (IOException expectedWhenTheServerStopsReading) {
                      // the server answered 400 and closed the connection
                    }
                  });
      InputStream in = socket.getInputStream();
      ByteArrayOutputStream response = new ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      int n;
      try {
        while ((n = in.read(buffer)) >= 0) {
          response.write(buffer, 0, n);
        }
      } catch (IOException resetAfterResponse) {
        // a reset after the response was read is fine
      }
      writer.join(60_000);
      return response.toString(StandardCharsets.UTF_8);
    }
  }

  /** The payload of a chunked body. */
  private static String dechunk(String body) {
    StringBuilder out = new StringBuilder();
    int pos = 0;
    while (pos < body.length()) {
      int eol = body.indexOf("\r\n", pos);
      if (eol < 0) {
        break;
      }
      int size = Integer.parseInt(body.substring(pos, eol).trim(), 16);
      if (size == 0) {
        break;
      }
      out.append(body, eol + 2, Math.min(body.length(), eol + 2 + size));
      pos = eol + 2 + size + 2;
    }
    return out.toString();
  }
}
