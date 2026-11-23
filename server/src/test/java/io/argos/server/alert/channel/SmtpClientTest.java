/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.server.testing.alert.FakeSmtpServer;
import java.net.InetAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The SMTP client against a plaintext fake server (STARTTLS cases are in the security suite). */
@Tag("req:SEC-54")
class SmtpClientTest {

  private static final String USER = "alerts";
  private static final String PASSWORD = "smtp-password-" + System.nanoTime();

  private final ChannelTls tls = new ChannelTls((Path) null);
  private final SmtpClient client =
      new SmtpClient(tls, tls.insecureContext(), host -> List.of(InetAddress.getLoopbackAddress()));

  private static SmtpClient.Settings settings(int port, boolean insecure) {
    return new SmtpClient.Settings(
        "127.0.0.1",
        port,
        USER,
        PASSWORD,
        true,
        insecure,
        "argos@example.test",
        "argos.example.test");
  }

  @Test
  @DisplayName(
      "T-SEC-54a a server without STARTTLS is refused by default: no AUTH, no message, failure"
          + " starttls_unavailable")
  void plaintextRefused() {
    try (FakeSmtpServer server = new FakeSmtpServer(false, null, USER, PASSWORD)) {
      assertThatThrownBy(
              () ->
                  client.send(
                      settings(server.port(), false),
                      List.of("ops@example.test"),
                      "subject",
                      "body"))
          .isInstanceOfSatisfying(
              DeliveryException.class,
              e -> {
                assertThat(e.code()).isEqualTo("starttls_unavailable");
                assertThat(e.getMessage()).doesNotContain(PASSWORD);
              });
      assertThat(server.inbox()).isEmpty();
      assertThat(server.transcript())
          .noneMatch(l -> l.toUpperCase(java.util.Locale.ROOT).startsWith("AUTH"));
      assertThat(server.transcript())
          .noneMatch(l -> l.toUpperCase(java.util.Locale.ROOT).startsWith("MAIL"));
    }
  }

  @Test
  @DisplayName(
      "T-SEC-54b with insecure=true the plaintext server is used (the explicit, warned opt-out)")
  void insecureAllowsPlaintext() throws Exception {
    try (FakeSmtpServer server = new FakeSmtpServer(false, null, USER, PASSWORD)) {
      SmtpClient.SmtpOutcome outcome =
          client.send(
              settings(server.port(), true),
              List.of("ops@example.test", "dev@example.test"),
              "[Argos] p95 지연",
              "line one\nline two");
      assertThat(outcome.replyCode()).isEqualTo(250);
      assertThat(outcome.tls()).isFalse();
      FakeSmtpServer.Message m = server.await(1, Duration.ofSeconds(5)).get(0);
      assertThat(m.tls()).isFalse();
      assertThat(m.authenticatedUser()).isEqualTo(USER);
      assertThat(m.from()).isEqualTo("argos@example.test");
      assertThat(m.recipients()).containsExactly("ops@example.test", "dev@example.test");
      assertThat(m.header("Subject")).startsWith("=?UTF-8?B?");
      assertThat(m.header("Content-Type")).isEqualTo("text/plain; charset=UTF-8");
      assertThat(m.decodedBody()).isEqualTo("line one\r\nline two");
    }
  }

  @Test
  @DisplayName("a wrong password is auth_failed and the password is not in the error")
  void authFailed() {
    try (FakeSmtpServer server = new FakeSmtpServer(false, null, USER, "other-password")) {
      assertThatThrownBy(
              () -> client.send(settings(server.port(), true), List.of("a@example.test"), "s", "b"))
          .isInstanceOfSatisfying(
              DeliveryException.class,
              e -> {
                assertThat(e.code()).isEqualTo("auth_failed");
                assertThat(e.statusCode()).isEqualTo(535);
                assertThat(e.getMessage()).doesNotContain(PASSWORD);
              });
    }
  }
}
