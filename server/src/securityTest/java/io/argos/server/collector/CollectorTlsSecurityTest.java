/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.collector;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.collector.grpc.CollectorTls;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.collector.CollectorHarness;
import io.argos.server.testing.fakeagent.AgentChannels;
import io.argos.server.testing.fakeagent.FakeAgent;
import io.argos.server.testing.fakeagent.HelloResult;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * T-SEC-07 server part (proposed T-SEC-07b; WO-11 holds T-SEC-07a for the agent): the gRPC listener
 * accepts TLS 1.2+ with the AEAD suites of 16 §3.2 only. The refusals are proved on the wire with
 * hand-built ClientHello records, because a JDK 21 client refuses to offer TLS 1.1 itself ({@code
 * jdk.tls.disabledAlgorithms}) and would fail before the server is asked: the server answers a TLS
 * 1.1 ClientHello and a CBC-only TLS 1.2 ClientHello with an alert, and a TLS 1.2 AEAD ClientHello
 * (positive control) with a ServerHello. A JDK client limited to CBC suites fails the handshake
 * too.
 */
@Tag("req:SEC-07")
class CollectorTlsSecurityTest {

  private static final SecureRandom RANDOM = new SecureRandom();
  private static ServerTestEnvironment env;
  private static CollectorHarness collector;

  @BeforeAll
  static void start() {
    env = ServerTestEnvironment.start();
    collector = CollectorHarness.start(env);
  }

  @AfterAll
  static void stop() {
    if (collector != null) {
      collector.close();
    }
    if (env != null) {
      env.close();
    }
  }

  /** The first bytes the server sends after {@code clientHello}, or empty on EOF. */
  private static byte[] firstBytes(byte[] clientHello) throws IOException {
    try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), collector.port())) {
      socket.setSoTimeout(10_000);
      socket.getOutputStream().write(clientHello);
      socket.getOutputStream().flush();
      InputStream in = socket.getInputStream();
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buffer = new byte[6];
      try {
        while (out.size() < 6) {
          int n = in.read(buffer, 0, 6 - out.size());
          if (n < 0) {
            break;
          }
          out.write(buffer, 0, n);
        }
      } catch (SocketTimeoutException | java.net.SocketException e) {
        // a reset or silence is a refusal too
      }
      return out.toByteArray();
    }
  }

  private static boolean isServerHello(byte[] first) {
    return first.length >= 6 && first[0] == 0x16 && first[5] == 0x02;
  }

  private static boolean isAlertOrClose(byte[] first) {
    return first.length == 0 || first[0] == 0x15;
  }

  @Test
  @DisplayName("T-SEC-07b a TLS 1.1 ClientHello gets an alert, never a ServerHello")
  void tls11Refused() throws IOException {
    byte[] first =
        firstBytes(clientHello(0x0302, new int[] {0xc013, 0xc009, 0x002f, 0x0035, 0x00ff}));
    assertThat(isServerHello(first)).isFalse();
    assertThat(isAlertOrClose(first)).as("alert (0x15) or closed").isTrue();
  }

  @Test
  @DisplayName(
      "T-SEC-07b a TLS 1.2 ClientHello offering only CBC and RSA key exchange suites gets an alert")
  void cbcOnlyRefused() throws IOException {
    byte[] first =
        firstBytes(
            clientHello(
                0x0303,
                new int[] {
                  0xc009, 0xc00a, 0xc023, 0xc024, 0xc013, 0xc027, 0x002f, 0x003c, 0x009c, 0x00ff
                }));
    assertThat(isServerHello(first)).isFalse();
    assertThat(isAlertOrClose(first)).isTrue();
  }

  @Test
  @DisplayName(
      "T-SEC-07b positive control: a TLS 1.2 ClientHello with an AEAD suite of 16 §3.2 gets a ServerHello")
  void aeadAccepted() throws IOException {
    byte[] first = firstBytes(clientHello(0x0303, new int[] {0xc02b, 0xc02c, 0xcca9, 0x00ff}));
    assertThat(isServerHello(first)).as("ServerHello").isTrue();
  }

  @Test
  @DisplayName(
      "T-SEC-07b a JDK client limited to CBC suites fails the handshake; a default JDK client connects")
  void jdkClients() {
    String app = "tls-" + UUID.randomUUID().toString().substring(0, 8);
    String token = collector.token(app, "prod").token();
    AgentChannels.Options cbc =
        AgentChannels.Options.defaults()
            .withProtocols("TLSv1.2")
            .withCipherSuites(
                "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256",
                "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA384");
    try (FakeAgent a =
        FakeAgent.builder(collector.channel(cbc))
            .ownsChannel(true)
            .token(token)
            .appName(app)
            .build()) {
      HelloResult r = a.connect();
      assertThat(r.hello()).isNull();
      assertThat(r.code()).isEqualTo(io.grpc.Status.Code.UNAVAILABLE);
    }
    try (FakeAgent ok = collector.agent(token).appName(app).build()) {
      assertThat(ok.connect().accepted()).isTrue();
    }
    assertThat(CollectorTls.PROTOCOLS).containsExactly("TLSv1.3", "TLSv1.2");
  }

  /** A minimal ClientHello record with {@code version} and {@code suites} for {@code localhost}. */
  static byte[] clientHello(int version, int[] suites) {
    ByteArrayOutputStream ext = new ByteArrayOutputStream();
    byte[] host = "localhost".getBytes(StandardCharsets.US_ASCII);
    // server_name
    u16(ext, 0x0000);
    u16(ext, host.length + 5);
    u16(ext, host.length + 3);
    ext.write(0);
    u16(ext, host.length);
    ext.writeBytes(host);
    // supported_groups: secp256r1, x25519, secp384r1
    u16(ext, 0x000a);
    u16(ext, 8);
    u16(ext, 6);
    u16(ext, 0x0017);
    u16(ext, 0x001d);
    u16(ext, 0x0018);
    // ec_point_formats: uncompressed
    u16(ext, 0x000b);
    u16(ext, 2);
    ext.write(1);
    ext.write(0);
    // signature_algorithms
    int[] sigs = {0x0403, 0x0503, 0x0804, 0x0805, 0x0401, 0x0501};
    u16(ext, 0x000d);
    u16(ext, sigs.length * 2 + 2);
    u16(ext, sigs.length * 2);
    for (int s : sigs) {
      u16(ext, s);
    }
    // ALPN h2
    u16(ext, 0x0010);
    u16(ext, 5);
    u16(ext, 3);
    ext.write(2);
    ext.writeBytes("h2".getBytes(StandardCharsets.US_ASCII));
    // extended_master_secret
    u16(ext, 0x0017);
    u16(ext, 0);
    byte[] extensions = ext.toByteArray();

    ByteArrayOutputStream body = new ByteArrayOutputStream();
    u16(body, version);
    byte[] random = new byte[32];
    RANDOM.nextBytes(random);
    body.writeBytes(random);
    body.write(0);
    u16(body, suites.length * 2);
    for (int s : suites) {
      u16(body, s);
    }
    body.write(1);
    body.write(0);
    u16(body, extensions.length);
    body.writeBytes(extensions);
    byte[] hello = body.toByteArray();

    ByteArrayOutputStream record = new ByteArrayOutputStream();
    record.write(0x16);
    u16(record, 0x0301);
    u16(record, hello.length + 4);
    record.write(0x01);
    record.write((hello.length >>> 16) & 0xFF);
    u16(record, hello.length & 0xFFFF);
    record.writeBytes(hello);
    return record.toByteArray();
  }

  private static void u16(ByteArrayOutputStream out, int value) {
    out.write((value >>> 8) & 0xFF);
    out.write(value & 0xFF);
  }
}
