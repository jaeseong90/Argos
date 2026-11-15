/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.apibench;

import io.argos.server.testing.auth.ApiTestClient;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.zip.GZIPInputStream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The timed HTTP calls of the query API bench: a signed-in browser (the session cookies and the
 * CSRF token of an {@link ApiTestClient} after its login), {@code Accept-Encoding: gzip} like a
 * browser, and the time from sending the request to the whole body being read and inflated (what
 * the UI waits for before it can parse).
 */
final class BenchClient {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final ApiTestClient session;
  private final HttpClient http =
      HttpClient.newBuilder()
          .version(HttpClient.Version.HTTP_1_1)
          .connectTimeout(Duration.ofSeconds(10))
          .build();

  BenchClient(ApiTestClient session) {
    this.session = session;
  }

  /**
   * One timed call.
   *
   * @param status HTTP status
   * @param millis send to body read and inflated
   * @param wireBytes bytes on the wire
   * @param bodyBytes bytes of the JSON
   * @param json the parsed body (null when it is no JSON)
   */
  record Timed(int status, double millis, long wireBytes, long bodyBytes, JsonNode json) {

    /** The error code of an error body, or null. */
    String errorCode() {
      return json == null ? null : json.path("error").path("code").asString(null);
    }
  }

  /** A GET. */
  Timed get(String path) {
    return send("GET", path, null);
  }

  /** A POST with a JSON body. */
  Timed post(String path, Object body) {
    return send("POST", path, JSON.writeValueAsString(body));
  }

  private Timed send(String method, String path, String body) {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(session.baseUrl() + path))
            .timeout(Duration.ofSeconds(60))
            .header("Accept", "application/json")
            .header("Accept-Encoding", "gzip");
    String cookies = session.cookies().header(path, false);
    if (cookies != null) {
      request.header("Cookie", cookies);
    }
    if (body == null) {
      request.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      request.header("Content-Type", "application/json");
      request.header("Origin", session.serverOrigin());
      session
          .cookies()
          .value(ApiTestClient.CSRF_COOKIE)
          .ifPresent(t -> request.header(ApiTestClient.CSRF_HEADER, t));
      request.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
    }
    long started = System.nanoTime();
    try {
      HttpResponse<byte[]> response =
          http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
      byte[] wire = response.body();
      byte[] raw = wire;
      if (response.headers().firstValue("Content-Encoding").orElse("").equalsIgnoreCase("gzip")) {
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(wire))) {
          raw = in.readAllBytes();
        }
      }
      double millis = (System.nanoTime() - started) / 1_000_000.0;
      JsonNode json = null;
      String type = response.headers().firstValue("Content-Type").orElse("");
      if (type.startsWith("application/json") && raw.length > 0) {
        json = JSON.readTree(raw);
      }
      return new Timed(response.statusCode(), millis, wire.length, raw.length, json);
    } catch (IOException e) {
      throw new UncheckedIOException(method + " " + path + " failed", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(method + " " + path + " interrupted", e);
    }
  }
}
