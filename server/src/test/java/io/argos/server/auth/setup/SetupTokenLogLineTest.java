/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.auth.setup;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.auth.token.SecureTokens;
import io.argos.server.config.logging.LogMasking;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The single log line of the setup token (16 §7.8, DSN-16-12, SEC-47): the masking of the server
 * log must leave it readable, or the operator of a container has no token; and the same value under
 * any other wording stays masked.
 */
@Tag("req:SEC-47")
class SetupTokenLogLineTest {

  private static final Instant EXPIRES = Instant.parse("2026-11-07T10:30:00Z");

  @Test
  @DisplayName("T-SEC-47a the masking rules leave the one setup-token line alone, for any token")
  void lineIsNotMasked() {
    for (String file :
        List.of(
            "conf/setup-token",
            "/data/setup-token",
            "C:\\argos\\secrets\\conf\\setup-token",
            "/home/token: x/setup-token")) {
      for (int i = 0; i < 200; i++) {
        String token = SecureTokens.newToken();
        String line = SetupTokenBootstrap.logLine(token, Duration.ofMinutes(30), EXPIRES, file);
        assertThat(LogMasking.mask(line)).as(file).contains(token);
      }
    }
    String token = SecureTokens.newToken();
    String noFile = SetupTokenBootstrap.logLine(token, Duration.ofMinutes(30), EXPIRES, null);
    assertThat(LogMasking.mask(noFile)).isEqualTo(noFile).contains("valid for 30 minutes");
  }

  @Test
  @DisplayName("T-SEC-47a the same token in any other usual wording is masked")
  void otherLinesAreMasked() {
    String token = SecureTokens.newToken();
    for (String line :
        List.of(
            "Setup token: " + token,
            "setup_token=" + token,
            "{\"setup_token\":\"" + token + "\"}",
            "X-Argos-Setup-Token: " + token,
            "X-Argos-Setup-Token:\"" + token + "\"")) {
      assertThat(LogMasking.mask(line)).as(line).doesNotContain(token);
    }
  }
}
