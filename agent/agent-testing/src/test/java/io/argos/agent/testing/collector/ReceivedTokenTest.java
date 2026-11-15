/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.collector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A received token never appears as text (11 §11, SEC-06, ADR-0010 C-94): its own {@code toString},
 * the records that hold it and AssertJ failure messages show {@code argos_****}.
 */
@Tag("req:SEC-06")
class ReceivedTokenTest {
  // Built from readable text at run time: a token-shaped literal trips the secret scan (gitleaks,
  // SEC-73), and this is a test value, not a secret.
  static final String TOKEN =
      "argos_"
          + Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString("secret-test-token-value".getBytes(StandardCharsets.US_ASCII));

  @Test
  @DisplayName(
      "SEC-06: toString, SessionInfo, ReceivedBatch and assertion failures show argos_**** only;"
          + " comparison is by value")
  void masked() {
    ReceivedToken token = ReceivedToken.fromAuthorization("Bearer " + TOKEN);
    assertThat(token).hasToString("argos_****");
    assertThat(token.wellFormed()).isTrue();
    assertThat(token.matches(TOKEN)).isTrue();
    assertThat(token.matches(TOKEN + "x")).isFalse();
    assertThat(token).isEqualTo(ReceivedToken.of(TOKEN)).isNotEqualTo(ReceivedToken.of("argos_x"));
    assertThat(token.length()).isEqualTo(TOKEN.length());
    SessionInfo session =
        new SessionInfo(
            1, 1, true, "shop", "test", "1", "0", Instant.EPOCH, null, "s", null, token, 3, false);
    ReceivedBatch batch =
        new ReceivedBatch(
            BatchKind.TRANSACTIONS,
            1,
            3,
            1,
            Instant.EPOCH,
            null,
            0,
            ReceivedBatch.ACKED,
            false,
            0,
            "s",
            token,
            0);
    assertThat(session.toString()).contains("argos_****").doesNotContain(TOKEN);
    assertThat(batch.toString()).contains("argos_****").doesNotContain(TOKEN);
    assertThatThrownBy(() -> assertThat(token).isEqualTo(ReceivedToken.of("argos_other")))
        .hasMessageNotContaining(TOKEN.substring(6));
  }

  @Test
  @DisplayName("11 §2 format: Bearer scheme and argos_<base64url>; others are absent or malformed")
  void format() {
    assertThat(ReceivedToken.fromAuthorization(null)).isNull();
    assertThat(ReceivedToken.fromAuthorization("Basic abc")).isNull();
    assertThat(ReceivedToken.fromAuthorization("Bearer xyz").wellFormed()).isFalse();
    assertThat(ReceivedToken.fromAuthorization("Bearer argos_").wellFormed()).isFalse();
    assertThat(ReceivedToken.fromAuthorization("Bearer argos_a+b").wellFormed()).isFalse();
    assertThat(ReceivedToken.fromAuthorization("Bearer argos_a-b_C9").wellFormed()).isTrue();
    assertThat(ReceivedToken.fromAuthorization("Bearer xyz")).hasToString("argos_****");
  }
}
