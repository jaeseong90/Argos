/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The allow list, the gate's decision codes and the token holder (SEC-06, SEC-23, C-92, C-94). */
class RemoteConfigContractTest {
  private static final String TOKEN = "argos_" + "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_abcde";

  @Test
  @Tag("req:SEC-23")
  @DisplayName(
      "C-240 (4)/C-118: the default allow list is the 10 §15 list, sampling.** reaches every"
          + " sampling key; argos.enabled = enabled")
  void defaultAllowList() {
    assertThat(AllowList.DEFAULT.patterns())
        .containsExactly(
            "sampling.**",
            "instrumentation.disabled",
            "instrumentation.ignore-*",
            "log.level",
            "enabled",
            "transaction.slow-stack.*",
            "capture.error-message-max-length",
            "capture.stack-depth",
            "instrumentation.app-packages",
            "async.step",
            "active.snapshot-interval-seconds",
            "metrics.interval-seconds",
            "transaction.max-duration-ms");
    assertThat(AllowList.DEFAULT.matches("enabled")).isTrue();
    assertThat(AllowList.DEFAULT.matches("argos.enabled")).isTrue();
    assertThat(AllowList.DEFAULT.matches("log.level")).isTrue();
    assertThat(AllowList.DEFAULT.matches("instrumentation.ignore-paths")).isTrue();
    // SEC-23 "sampling.*" means every sampling key (C-118).
    assertThat(AllowList.DEFAULT.matches("sampling.profile.normal-rate")).isTrue();
    assertThat(AllowList.DEFAULT.matches("sampling.profile.slow-threshold-ms")).isTrue();
    assertThat(AllowList.DEFAULT.matches("sampling.profile.slow-threshold-ms.services.shop"))
        .isTrue();
    assertThat(AllowList.DEFAULT.matches("instrumentation.ignore-extensions")).isTrue();
    assertThat(AllowList.DEFAULT.matches("transaction.slow-stack.enabled")).isTrue();
    assertThat(AllowList.DEFAULT.matches("instrumentation.custom-methods")).isFalse();
    assertThat(AllowList.DEFAULT.matches("instrumentation.exclude-classes")).isFalse();
    assertThat(AllowList.DEFAULT.matches("capture.sql-parameters")).isFalse();
    assertThat(AllowList.DEFAULT.matches("server.address")).isFalse();
    assertThat(AllowList.of(Arrays.asList("argos.enabled", " ", "log.level")).patterns())
        .containsExactly("enabled", "log.level");
  }

  @Test
  @Tag("req:SEC-23")
  @DisplayName("16 §5.5 globs: * within one segment, ** for the remaining segments")
  void globs() {
    AllowList list =
        AllowList.of(
            Arrays.asList("instrumentation.ignore-*", "transaction.slow-stack.*", "capture.**"));
    assertThat(list.matches("instrumentation.ignore-paths")).isTrue();
    assertThat(list.matches("instrumentation.ignore-user-agents")).isTrue();
    assertThat(list.matches("instrumentation.disabled")).isFalse();
    assertThat(list.matches("transaction.slow-stack.enabled")).isTrue();
    assertThat(list.matches("transaction.max-duration-ms")).isFalse();
    assertThat(list.matches("capture.user-id.mode")).isTrue();
    assertThat(list.matches("capture")).isFalse();
    assertThat(AllowList.globMatches("a.*", "a.b.c")).isFalse();
    assertThat(AllowList.globMatches("a.*.c", "a.b.c")).isTrue();
    assertThat(AllowList.globMatches("a.b*d", "a.bxyd")).isTrue();
    assertThat(AllowList.of(Collections.<String>emptyList()).matches("enabled")).isFalse();
  }

  @Test
  @DisplayName("allow lists keep at most 128 patterns")
  void allowListCap() {
    String[] patterns = new String[AllowList.MAX_PATTERNS + 10];
    for (int i = 0; i < patterns.length; i++) {
      patterns[i] = "key" + i;
    }
    assertThat(AllowList.of(Arrays.asList(patterns)).patterns()).hasSize(AllowList.MAX_PATTERNS);
  }

  @Test
  @Tag("req:SEC-23")
  @DisplayName("gate decisions carry the 16 §5.5 reject reasons")
  void decisionReasons() {
    assertThat(RemoteConfigGate.Decision.APPLY.applies()).isTrue();
    assertThat(RemoteConfigGate.Decision.NOT_ALLOWED.applies()).isFalse();
    assertThat(RemoteConfigGate.Decision.NOT_ALLOWED.reason()).isEqualTo("not_allowed");
    assertThat(RemoteConfigGate.Decision.IMMUTABLE.reason()).isEqualTo("immutable");
    assertThat(RemoteConfigGate.Decision.INVALID_VALUE.reason()).isEqualTo("invalid_value");
    assertThat(RemoteConfigGate.Decision.REDUCE_MASKING.reason()).isEqualTo("reduce_masking");
    assertThat(RemoteConfigGate.Decision.UNKNOWN_KEY.reason()).isEqualTo("unknown_key");
    AgentConfig current = AgentConfig.defaults();
    assertThat(RemoteConfigGate.of(current).current()).isSameAs(current);
  }

  @Test
  @Tag("req:SEC-06")
  @DisplayName("C-94: tokens are shown as argos_****, in toString and in free text")
  void tokenMasking() {
    TokenHolder token = TokenHolder.of(" " + TOKEN + "\n", TokenHolder.Source.FILE);
    assertThat(token.reveal()).isEqualTo(TOKEN);
    assertThat(token.hasValidFormat()).isTrue();
    assertThat(token.masked()).isEqualTo("argos_****");
    assertThat(token.toString()).isEqualTo("argos_**** (FILE)").doesNotContain(TOKEN);
    assertThat(TokenHolder.maskTokens("Bearer " + TOKEN + " end"))
        .isEqualTo("Bearer argos_**** end");
    assertThat(TokenHolder.maskTokens("no token")).isEqualTo("no token");
    assertThat(TokenHolder.of("  ", TokenHolder.Source.PLAIN)).isSameAs(TokenHolder.NONE);
    assertThat(TokenHolder.NONE.masked()).isEqualTo("none");
    assertThat(TokenHolder.of("argos_short", TokenHolder.Source.PLAIN).hasValidFormat()).isFalse();
  }
}
