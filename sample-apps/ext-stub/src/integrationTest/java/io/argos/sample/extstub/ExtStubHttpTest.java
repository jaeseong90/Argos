/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.extstub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ext-stub behaviours over real HTTP on the loopback interface (18 §4.1: normal, delay, no answer,
 * connection refused, 5xx, redirect).
 */
class ExtStubHttpTest {

  private static ExtStub stub;
  private static HttpClient client;

  @BeforeAll
  static void start() throws IOException {
    stub = ExtStub.start(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 8);
    client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  @AfterAll
  static void stop() {
    stub.close();
  }

  private static HttpResponse<String> get(String path, Duration timeout) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(stub.baseUri().resolve(path))
            .timeout(timeout)
            .header("traceparent", "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01")
            .build();
    return client.send(request, HttpResponse.BodyHandlers.ofString());
  }

  @Test
  @DisplayName("Normal: 200 with a JSON echo of path and traceparent; the request is logged")
  void normal() throws Exception {
    HttpResponse<String> response = get("/pay/7?x=1", Duration.ofSeconds(5));
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Content-Type"))
        .hasValueSatisfying(v -> assertThat(v).startsWith("application/json"));
    assertThat(response.body())
        .contains("\"path\":\"/pay/7\"")
        .contains("0af7651916cd43dd8448eb211c80319c");
    String log = get("/_stub/requests", Duration.ofSeconds(5)).body();
    assertThat(log).contains("/pay/7").contains("traceparent");
  }

  @Test
  @DisplayName(
      "TCP_NODELAY of the JDK HTTP server is on unless set explicitly (no 40 ms ACK delay)")
  void nodelay() {
    assertThat(System.getProperty(ExtStub.NODELAY_PROPERTY)).isEqualTo("true");
  }

  @Test
  @DisplayName("Delay: the answer comes after ms milliseconds")
  void delay() throws Exception {
    long start = System.nanoTime();
    HttpResponse<String> response = get("/delay?ms=300", Duration.ofSeconds(5));
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(elapsedMs).isGreaterThanOrEqualTo(290);
  }

  @Test
  @DisplayName("Timeout: no answer, the client's timeout fires; the stub closes after ms")
  void noAnswer() throws Exception {
    assertThatThrownBy(() -> get("/timeout", Duration.ofMillis(300)))
        .isInstanceOf(HttpTimeoutException.class);
    long start = System.nanoTime();
    assertThatThrownBy(() -> get("/timeout?ms=400", Duration.ofSeconds(10)))
        .isInstanceOf(IOException.class)
        .isNotInstanceOf(HttpTimeoutException.class);
    assertThat((System.nanoTime() - start) / 1_000_000).isGreaterThanOrEqualTo(390);
  }

  @Test
  @DisplayName("Status: 500 and 503 as asked; out-of-range status is a 400")
  void status() throws Exception {
    assertThat(get("/status/500", Duration.ofSeconds(5)).statusCode()).isEqualTo(500);
    assertThat(get("/status/503", Duration.ofSeconds(5)).statusCode()).isEqualTo(503);
    assertThat(get("/status/99", Duration.ofSeconds(5)).statusCode()).isEqualTo(400);
  }

  @Test
  @DisplayName("Redirect: 302 to a path on ext-stub; absolute targets are rejected")
  void redirect() throws Exception {
    HttpResponse<String> response = get("/redirect?to=/pay/1", Duration.ofSeconds(5));
    assertThat(response.statusCode()).isEqualTo(302);
    assertThat(response.headers().firstValue("Location")).hasValue("/pay/1");
    assertThat(get("/redirect?to=//evil.example/x", Duration.ofSeconds(5)).statusCode())
        .isEqualTo(400);
    assertThat(get("/redirect?to=http://evil.example/", Duration.ofSeconds(5)).statusCode())
        .isEqualTo(400);
  }

  @Test
  @DisplayName("Close: the connection ends without an answer")
  void close() {
    assertThatThrownBy(() -> get("/close", Duration.ofSeconds(5))).isInstanceOf(IOException.class);
  }

  @Test
  @DisplayName("Connection refused: a port of the stub host where nothing listens")
  void refused() throws Exception {
    int unused;
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      unused = socket.getLocalPort();
    }
    URI target = URI.create("http://127.0.0.1:" + unused + "/pay/1");
    assertThatThrownBy(
            () ->
                client.send(
                    HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(10)).build(),
                    HttpResponse.BodyHandlers.ofString()))
        .isInstanceOf(ConnectException.class);
  }
}
