/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.security.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.server.alert.channel.ChannelTls;
import io.argos.server.alert.channel.DeliveryException;
import io.argos.server.alert.channel.Destination;
import io.argos.server.alert.channel.PinnedHttpClient;
import io.argos.server.alert.channel.SmtpClient;
import io.argos.server.alert.channel.WebhookSignature;
import io.argos.server.testing.alert.FakeSmtpServer;
import io.argos.server.testing.alert.FakeWebhookReceiver;
import io.argos.server.testing.alert.TestTls;
import io.argos.server.testing.fakeagent.TestCertificates;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * TLS of the outbound channels (T-SV-035; 16 §8.1 step 6, §8.5): the pinned HTTPS client and the
 * SMTP client against in-process receivers with a CA generated at run time. No server, no Docker.
 */
@Tag("req:SEC-51")
@Tag("req:SEC-54")
class ChannelTlsSecurityTest {

  private static Path dir;
  private static TestCertificates.TestPki pki;
  private static SSLContext serverTls;
  private static ChannelTls trusting;
  private static FakeWebhookReceiver https;

  @BeforeAll
  static void pki() throws Exception {
    dir = Files.createTempDirectory("argos-channel-tls");
    pki = TestTls.pki(dir);
    serverTls = TestTls.serverContext(pki);
    trusting = new ChannelTls(pki.caCertificate());
    https = FakeWebhookReceiver.https(serverTls);
  }

  @AfterAll
  static void close() {
    if (https != null) {
      https.close();
    }
  }

  private static Destination destination(String host, int port) {
    return new Destination(
        URI.create("https://" + host + ":" + port + "/hook"),
        host,
        port,
        true,
        List.of(InetAddress.getLoopbackAddress()),
        InetAddress.getLoopbackAddress());
  }

  @Test
  @DisplayName(
      "T-SV-035a the pinned HTTPS client verifies the chain and the original host name, sends Host"
          + " and the signature, and gets the status")
  void httpsDelivery() throws Exception {
    PinnedHttpClient client = new PinnedHttpClient(trusting, Duration.ofSeconds(5), "Argos/test");
    byte[] body = "{\"schema_version\":1}".getBytes(StandardCharsets.UTF_8);
    String secret = "s".repeat(43);
    String signature = WebhookSignature.header(List.of(secret), Instant.now(), body);
    PinnedHttpClient.HttpOutcome outcome =
        client.post(
            destination("localhost", https.port()),
            Map.of(WebhookSignature.HEADER, signature),
            "application/json",
            body);
    assertThat(outcome.status()).isEqualTo(200);
    FakeWebhookReceiver.Received r = https.await(1, Duration.ofSeconds(5)).getLast();
    assertThat(r.header("Host")).isEqualTo("localhost:" + https.port());
    assertThat(
            WebhookSignature.verify(
                r.header(WebhookSignature.HEADER), secret, r.body(), Instant.now()))
        .isTrue();
  }

  @Test
  @DisplayName(
      "T-SV-035b a certificate that does not name the destination's host fails (tls_failed), even"
          + " on the pinned address")
  void hostNameMismatch() {
    PinnedHttpClient client = new PinnedHttpClient(trusting, Duration.ofSeconds(5), "Argos/test");
    int before = https.requests().size();
    assertThatThrownBy(
            () ->
                client.post(
                    destination("hooks.example.com", https.port()),
                    Map.of(),
                    "application/json",
                    new byte[0]))
        .isInstanceOfSatisfying(
            DeliveryException.class, e -> assertThat(e.code()).isEqualTo("tls_failed"));
    assertThat(https.requests()).hasSize(before);
  }

  @Test
  @DisplayName("T-SV-035c a certificate of an untrusted CA fails (tls_failed)")
  void untrusted() {
    PinnedHttpClient client =
        new PinnedHttpClient(new ChannelTls((Path) null), Duration.ofSeconds(5), "Argos/test");
    assertThatThrownBy(
            () ->
                client.post(
                    destination("localhost", https.port()),
                    Map.of(),
                    "application/json",
                    new byte[0]))
        .isInstanceOfSatisfying(
            DeliveryException.class, e -> assertThat(e.code()).isEqualTo("tls_failed"));
  }

  @Test
  @DisplayName(
      "T-SV-035d SMTP upgrades with STARTTLS, verifies the certificate and its host name, then"
          + " authenticates and sends")
  void smtpStartTls() throws Exception {
    String password = "smtp-" + System.nanoTime();
    try (FakeSmtpServer smtp = new FakeSmtpServer(true, serverTls, "alerts", password)) {
      SmtpClient client =
          new SmtpClient(
              trusting, trusting.insecureContext(), h -> List.of(InetAddress.getLoopbackAddress()));
      SmtpClient.SmtpOutcome outcome =
          client.send(
              new SmtpClient.Settings(
                  "localhost",
                  smtp.port(),
                  "alerts",
                  password,
                  true,
                  false,
                  "argos@example.test",
                  "argos"),
              List.of("ops@example.test"),
              "subject",
              "body");
      assertThat(outcome.tls()).isTrue();
      FakeSmtpServer.Message m = smtp.await(1, Duration.ofSeconds(5)).get(0);
      assertThat(m.tls()).isTrue();
      assertThat(m.authenticatedUser()).isEqualTo("alerts");
      // the credential travels only after the upgrade
      List<String> transcript = smtp.transcript();
      assertThat(transcript.indexOf("STARTTLS"))
          .isLessThan(transcript.indexOf("AUTH PLAIN <credential>"));
    }
  }

  @Test
  @DisplayName(
      "T-SV-035e T-SEC-54 SMTP with an unverifiable certificate or host name fails before AUTH;"
          + " without STARTTLS it is refused")
  void smtpRefusals() {
    String password = "smtp-" + System.nanoTime();
    try (FakeSmtpServer smtp = new FakeSmtpServer(true, serverTls, "alerts", password)) {
      ChannelTls untrusting = new ChannelTls((Path) null);
      SmtpClient untrusted =
          new SmtpClient(
              untrusting,
              untrusting.insecureContext(),
              h -> List.of(InetAddress.getLoopbackAddress()));
      SmtpClient.Settings settings =
          new SmtpClient.Settings(
              "localhost",
              smtp.port(),
              "alerts",
              password,
              true,
              false,
              "argos@example.test",
              "argos");
      assertThatThrownBy(() -> untrusted.send(settings, List.of("ops@example.test"), "s", "b"))
          .isInstanceOfSatisfying(
              DeliveryException.class, e -> assertThat(e.code()).isEqualTo("tls_failed"));

      SmtpClient trusted =
          new SmtpClient(
              trusting, trusting.insecureContext(), h -> List.of(InetAddress.getLoopbackAddress()));
      SmtpClient.Settings wrongHost =
          new SmtpClient.Settings(
              "mail.example.com",
              smtp.port(),
              "alerts",
              password,
              true,
              false,
              "argos@example.test",
              "argos");
      assertThatThrownBy(() -> trusted.send(wrongHost, List.of("ops@example.test"), "s", "b"))
          .isInstanceOfSatisfying(
              DeliveryException.class, e -> assertThat(e.code()).isEqualTo("tls_failed"));

      smtp.offerStartTls(false);
      assertThatThrownBy(() -> trusted.send(settings, List.of("ops@example.test"), "s", "b"))
          .isInstanceOfSatisfying(
              DeliveryException.class, e -> assertThat(e.code()).isEqualTo("starttls_unavailable"));
      assertThat(smtp.inbox()).isEmpty();
      assertThat(smtp.transcript()).noneMatch(l -> l.startsWith("AUTH"));
    }
  }
}
