/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.server.testing.alert.FakeWebhookReceiver;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The pinned client over plain HTTP on loopback (TLS cases are in the security suite). */
@Tag("req:SEC-51")
class PinnedHttpClientTest {

  private final FakeWebhookReceiver receiver = FakeWebhookReceiver.http();
  private final PinnedHttpClient client =
      new PinnedHttpClient(
          new ChannelTls((java.nio.file.Path) null), Duration.ofSeconds(2), "Argos/test");

  @AfterEach
  void close() {
    receiver.close();
  }

  /** A destination named {@code host} but pinned to loopback (what the validator produces). */
  private Destination pinned(String host, String path) {
    return new Destination(
        URI.create("http://" + host + ":" + receiver.port() + path),
        host,
        receiver.port(),
        false,
        List.of(InetAddress.getLoopbackAddress()),
        InetAddress.getLoopbackAddress());
  }

  @Test
  @DisplayName(
      "T-SEC-51 the request goes to the pinned address with the original Host header, no new"
          + " lookup")
  void pinnedWithHost() throws Exception {
    PinnedHttpClient.HttpOutcome outcome =
        client.post(
            pinned("hooks.example.com", "/services/a?b=c"),
            Map.of("X-Argos-Signature", "t=1,v1=00"),
            "application/json",
            "{}".getBytes(StandardCharsets.UTF_8));
    assertThat(outcome.status()).isEqualTo(200);
    FakeWebhookReceiver.Received r = receiver.await(1, Duration.ofSeconds(5)).get(0);
    assertThat(r.header("Host")).isEqualTo("hooks.example.com:" + receiver.port());
    assertThat(r.path()).isEqualTo("/services/a?b=c");
    assertThat(r.header("X-Argos-Signature")).isEqualTo("t=1,v1=00");
    assertThat(r.bodyText()).isEqualTo("{}");
  }

  @Test
  @DisplayName(
      "T-SEC-51d a 302 is not followed: one request, failure redirect, the Location never"
          + " contacted")
  void redirectNotFollowed() {
    try (FakeWebhookReceiver target = FakeWebhookReceiver.http()) {
      receiver.redirect(target.url("/stolen"));
      assertThatThrownBy(
              () ->
                  client.post(
                      pinned("127.0.0.1", "/hook"), Map.of(), "application/json", new byte[0]))
          .isInstanceOfSatisfying(
              DeliveryException.class,
              e -> {
                assertThat(e.code()).isEqualTo("redirect");
                assertThat(e.statusCode()).isEqualTo(302);
              });
      assertThat(receiver.requests()).hasSize(1);
      assertThat(target.requests()).isEmpty();
    }
  }

  @Test
  @DisplayName(
      "T-SEC-51e the response body is read up to 64 KB and dropped: only the status and the code"
          + " come back")
  void bodyNeverExposed() {
    String secret = "INTERNAL-SECRET-" + "x".repeat(200_000);
    receiver.respond(500).body(secret);
    assertThatThrownBy(
            () ->
                client.post(
                    pinned("127.0.0.1", "/hook"), Map.of(), "application/json", new byte[0]))
        .isInstanceOfSatisfying(
            DeliveryException.class,
            e -> {
              assertThat(e.code()).isEqualTo("http_status");
              assertThat(e.statusCode()).isEqualTo(500);
              assertThat(e.getMessage()).isEqualTo("http_status").doesNotContain("INTERNAL-SECRET");
            });
  }

  @Test
  @DisplayName("T-SEC-51 the total time limit ends a slow destination: failure timeout")
  void timeout() {
    receiver.delay(Duration.ofSeconds(2));
    PinnedHttpClient quick =
        new PinnedHttpClient(
            new ChannelTls((java.nio.file.Path) null), Duration.ofMillis(500), "Argos/test");
    long start = System.nanoTime();
    assertThatThrownBy(
            () ->
                quick.post(pinned("127.0.0.1", "/hook"), Map.of(), "application/json", new byte[0]))
        .isInstanceOfSatisfying(
            DeliveryException.class, e -> assertThat(e.code()).isEqualTo("timeout"));
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(1500));
  }

  @Test
  @DisplayName("a closed port is connect_failed; a header with a line break is refused")
  void failures() {
    Destination closed =
        new Destination(
            URI.create("http://127.0.0.1:1/x"),
            "127.0.0.1",
            1,
            false,
            List.of(InetAddress.getLoopbackAddress()),
            InetAddress.getLoopbackAddress());
    assertThatThrownBy(() -> client.post(closed, Map.of(), "application/json", new byte[0]))
        .isInstanceOfSatisfying(
            DeliveryException.class, e -> assertThat(e.code()).isEqualTo("connect_failed"));
    assertThatThrownBy(
            () ->
                client.post(
                    pinned("127.0.0.1", "/hook"),
                    Map.of("X-Test", "a\r\nInjected: 1"),
                    "application/json",
                    new byte[0]))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
