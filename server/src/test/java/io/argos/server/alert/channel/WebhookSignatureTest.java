/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert.channel;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The webhook signature of 16 §8.4 against vectors computed outside this code (Python hmac). */
@Tag("req:SEC-53")
class WebhookSignatureTest {

  private static final String SECRET = "test-secret-0123456789abcdefghijklmnopqrstuvwxyz";
  private static final String PREVIOUS = "previous-secret-ABCDEFGHIJKLMNOPQRSTUVWXYZ012345";
  private static final byte[] BODY =
      "{\"schema_version\":1,\"alert\":{\"kind\":\"test\"}}".getBytes(StandardCharsets.UTF_8);
  private static final Instant T = Instant.ofEpochSecond(1_700_000_000L);

  /** {@code hmac.new(SECRET, b"1700000000." + BODY, sha256).hexdigest()}. */
  private static final String V1 =
      "c7084e4fc313f2cb4e9d06d746f77c870fbff9eace5da28629e2b49d141d23c4";

  /** The same with {@link #PREVIOUS}. */
  private static final String V1_PREVIOUS =
      "78e7d62096006d6f6c6362f8b67ac10bf8e48c87d0ef7d3d2d0e4184a8c8a0e9";

  @Test
  @DisplayName(
      "T-SEC-53a X-Argos-Signature is t=<unix>,v1=<hex HMAC-SHA256(key, t.body)> (known vector)")
  void knownVector() {
    assertThat(WebhookSignature.header(List.of(SECRET), T, BODY))
        .isEqualTo("t=1700000000,v1=" + V1);
  }

  @Test
  @DisplayName(
      "T-SEC-53b during the rotation grace the header has two v1 values, newest first, and a"
          + " receiver with either key accepts it")
  void rotation() {
    String header = WebhookSignature.header(List.of(SECRET, PREVIOUS), T, BODY);
    assertThat(header).isEqualTo("t=1700000000,v1=" + V1 + ",v1=" + V1_PREVIOUS);
    assertThat(WebhookSignature.verify(header, SECRET, BODY, T)).isTrue();
    assertThat(WebhookSignature.verify(header, PREVIOUS, BODY, T)).isTrue();
    assertThat(WebhookSignature.verify(header, "another-secret-0000000000000000000000", BODY, T))
        .isFalse();
  }

  @Test
  @DisplayName(
      "T-SEC-53c the receiver example accepts within 5 minutes and refuses an older or newer t, a"
          + " changed body or a malformed header")
  void receiverExample() {
    String header = "t=1700000000,v1=" + V1;
    assertThat(WebhookSignature.verify(header, SECRET, BODY, T.plus(Duration.ofMinutes(5))))
        .isTrue();
    assertThat(WebhookSignature.verify(header, SECRET, BODY, T.minus(Duration.ofMinutes(5))))
        .isTrue();
    assertThat(
            WebhookSignature.verify(
                header, SECRET, BODY, T.plus(Duration.ofMinutes(5)).plusSeconds(1)))
        .isFalse();
    assertThat(
            WebhookSignature.verify(
                header, SECRET, BODY, T.minus(Duration.ofMinutes(5)).minusSeconds(1)))
        .isFalse();
    byte[] changed =
        "{\"schema_version\":1,\"alert\":{\"kind\":\"TEST\"}}".getBytes(StandardCharsets.UTF_8);
    assertThat(WebhookSignature.verify(header, SECRET, changed, T)).isFalse();
    assertThat(WebhookSignature.verify("v1=" + V1, SECRET, BODY, T)).isFalse();
    assertThat(WebhookSignature.verify("t=abc,v1=" + V1, SECRET, BODY, T)).isFalse();
    assertThat(WebhookSignature.verify(null, SECRET, BODY, T)).isFalse();
  }
}
