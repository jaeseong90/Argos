/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.core.config.RemoteConfigGate.Decision;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The evaluation half of the remote hard cap (SEC-23, 16 §5.5 steps 1-4, 10 §12.2 steps 1-3):
 * {@link RemoteConfigGate#evaluate(String, String, AllowList)} for every reject reason.
 */
@Tag("req:SEC-23")
class RemoteConfigGateTest {
  /** Allows every key: only the gate's own rules can reject. */
  private static final AllowList EVERYTHING = AllowList.of(Collections.singletonList("**"));

  private static final RemoteConfigGate DEFAULTS = RemoteConfigGate.of(AgentConfig.defaults());

  static Stream<Arguments> immutable() {
    return Stream.of(
            "server.address",
            "server.token",
            "server.token-file",
            "server.tls",
            "server.tls.insecure-skip-verify",
            "server.not-a-key",
            "argos.server.address",
            "remote-config.allow",
            "remote-config.anything",
            "app.name",
            "app.env",
            "app.unknown",
            "log.dir",
            "agent.disabled",
            "argos.agent.disabled",
            "log.max-file-mb",
            "log.max-files",
            "debug.transform-timing",
            "debug.transform-log",
            "test.exporter",
            "test.faults",
            "test.selfdiag-file")
        .map(Arguments::of);
  }

  @ParameterizedTest(name = "T-SEC-23a {0} is immutable even when the allow list names every key")
  @MethodSource("immutable")
  void immutableKeys(String key) {
    assertThat(DEFAULTS.evaluate(key, "x", EVERYTHING)).isEqualTo(Decision.IMMUTABLE);
    assertThat(DEFAULTS.evaluate(key, "x", AllowList.of(Collections.singletonList(key))))
        .isEqualTo(Decision.IMMUTABLE);
  }

  @Test
  @DisplayName("T-SEC-23a SEC-23 'default deny' keys are not allowed with the default allow list")
  void defaultDeny() {
    for (String key :
        Arrays.asList(
            "capture.sql-parameters",
            "capture.http-headers",
            "capture.user-id.mode",
            "capture.user-id.source",
            "capture.query-string",
            "capture.query-string.whitelist",
            "capture.remote-ip",
            "capture.trusted-proxies",
            "capture.mask-patterns",
            "instrumentation.custom-methods",
            "commands.env-dump.mask-patterns",
            "buffer.max-bytes")) {
      assertThat(DEFAULTS.evaluate(key, "x", AllowList.DEFAULT))
          .as(key)
          .isEqualTo(Decision.NOT_ALLOWED);
    }
  }

  @Test
  @DisplayName(
      "T-SEC-23a C-92: the Y column does not widen the default list; only the local list does")
  void remoteColumnDoesNotWiden() {
    for (String key :
        Arrays.asList(
            "transaction.max-duration-ms",
            "transaction.slow-stack.enabled",
            "capture.error-message-max-length",
            "instrumentation.ignore-extensions",
            "metrics.interval-seconds",
            "async.step")) {
      assertThat(ConfigKeys.spec(key).remote()).isEqualTo(KeySpec.Remote.Y);
      assertThat(DEFAULTS.evaluate(key, "true", AllowList.DEFAULT))
          .as(key)
          .isEqualTo(Decision.NOT_ALLOWED);
    }
    AllowList local = AllowList.of(Arrays.asList("transaction.**", "async.step"));
    assertThat(DEFAULTS.evaluate("transaction.max-duration-ms", "60000", local))
        .isEqualTo(Decision.APPLY);
    assertThat(DEFAULTS.evaluate("transaction.slow-stack.enabled", "false", local))
        .isEqualTo(Decision.APPLY);
    assertThat(DEFAULTS.evaluate("async.step", "true", local)).isEqualTo(Decision.APPLY);
  }

  @Test
  @DisplayName("T-SEC-23a the default list applies the SEC-23 keys; argos.enabled = enabled")
  void defaultAllowApplies() {
    assertThat(DEFAULTS.evaluate("enabled", "false", AllowList.DEFAULT)).isEqualTo(Decision.APPLY);
    assertThat(DEFAULTS.evaluate("argos.enabled", "false", AllowList.DEFAULT))
        .isEqualTo(Decision.APPLY);
    assertThat(DEFAULTS.evaluate("log.level", "debug", AllowList.DEFAULT))
        .isEqualTo(Decision.APPLY);
    assertThat(DEFAULTS.evaluate("instrumentation.disabled", "jdbc, executor", AllowList.DEFAULT))
        .isEqualTo(Decision.APPLY);
    assertThat(DEFAULTS.evaluate("instrumentation.ignore-paths", "[\"/ping\"]", AllowList.DEFAULT))
        .isEqualTo(Decision.APPLY);
    // an empty list is a value (it turns the rule off), not "unset"
    assertThat(DEFAULTS.evaluate("instrumentation.ignore-paths", "", AllowList.DEFAULT))
        .isEqualTo(Decision.APPLY);
  }

  @Test
  @DisplayName(
      "T-SEC-23a an allowed key with a sensitive value is applied only when the list names it")
  void explicitAllow() {
    AllowList local = AllowList.of(Arrays.asList("capture.**", "sampling.**"));
    assertThat(DEFAULTS.evaluate("capture.sql-parameters", "full", local))
        .isEqualTo(Decision.APPLY);
    assertThat(DEFAULTS.evaluate("capture.user-id.mode", "hashed", local))
        .isEqualTo(Decision.APPLY);
    assertThat(DEFAULTS.evaluate("sampling.profile.normal-rate", "0.5", local))
        .isEqualTo(Decision.APPLY);
    assertThat(
            DEFAULTS.evaluate("sampling.profile.slow-threshold-ms.services.GET /a", "200", local))
        .isEqualTo(Decision.APPLY);
    assertThat(DEFAULTS.evaluate("capture.sql-parameters", "full", AllowList.DEFAULT))
        .isEqualTo(Decision.NOT_ALLOWED);
  }

  static Stream<Arguments> invalidValues() {
    return Stream.of(
        Arguments.of("log.level", "verbose"),
        Arguments.of("enabled", "maybe"),
        Arguments.of("enabled", ""),
        Arguments.of("enabled", null),
        Arguments.of("sampling.profile.normal-rate", "1.5"),
        Arguments.of("sampling.profile.normal-rate", "NaN"),
        Arguments.of("sampling.profile.max-steps", "0"),
        Arguments.of("sampling.profile.max-steps", "10001"),
        Arguments.of("capture.sql-parameters", "everything"),
        Arguments.of("capture.mask-patterns", "[\"(unclosed\"]"),
        Arguments.of("instrumentation.disabled", "Not A Module"),
        Arguments.of("instrumentation.custom-methods", "com.example.Job"),
        Arguments.of("transaction.max-duration-ms", "999"),
        Arguments.of("log.level", repeat('x', KeySpec.MAX_RAW_LENGTH + 1)));
  }

  @ParameterizedTest(name = "T-SEC-23a {0} = ''{1}'' is invalid_value (type, range, format)")
  @MethodSource("invalidValues")
  void invalidValue(String key, String value) {
    assertThat(DEFAULTS.evaluate(key, value, EVERYTHING)).isEqualTo(Decision.INVALID_VALUE);
  }

  @Test
  @DisplayName("T-SEC-23a capture.mask-patterns may only add patterns (16 §5.5 step 3)")
  void maskPatternsOnlyGrow() {
    Map<String, String> values = new LinkedHashMap<String, String>();
    values.put("capture.mask-patterns", "[\"ORD-\\\\d+\", \"cust-[0-9]+\"]");
    values.put("commands.env-dump.mask-patterns", "(?i)vault");
    RemoteConfigGate gate = RemoteConfigGate.of(AgentConfig.of(values));
    AllowList local = AllowList.of(Arrays.asList("capture.mask-patterns", "commands.**"));

    assertThat(gate.evaluate("capture.mask-patterns", "[\"ORD-\\\\d+\"]", local))
        .isEqualTo(Decision.REDUCE_MASKING);
    assertThat(gate.evaluate("capture.mask-patterns", "", local))
        .isEqualTo(Decision.REDUCE_MASKING);
    assertThat(
            gate.evaluate(
                "capture.mask-patterns", "[\"cust-[0-9]+\", \"ORD-\\\\d+\", \"new\"]", local))
        .isEqualTo(Decision.APPLY);
    assertThat(gate.evaluate("capture.mask-patterns", "[\"cust-[0-9]+\", \"ORD-\\\\d+\"]", local))
        .isEqualTo(Decision.APPLY);
    assertThat(gate.evaluate("commands.env-dump.mask-patterns", "other", local))
        .isEqualTo(Decision.REDUCE_MASKING);
    assertThat(gate.evaluate("commands.env-dump.mask-patterns", "(?i)vault, other", local))
        .isEqualTo(Decision.APPLY);
    // nothing to keep: any valid list applies
    assertThat(DEFAULTS.evaluate("capture.mask-patterns", "a", local)).isEqualTo(Decision.APPLY);
    // the allow list is checked first: removal without the key in the list is not_allowed
    assertThat(gate.evaluate("capture.mask-patterns", "", AllowList.DEFAULT))
        .isEqualTo(Decision.NOT_ALLOWED);
  }

  @Test
  @DisplayName(
      "T-SEC-23a the first failing check decides: immutable > unknown > not_allowed >"
          + " reduce_masking > invalid (16 §5.5)")
  void order() {
    assertThat(DEFAULTS.evaluate("server.address", "bad host", AllowList.DEFAULT))
        .isEqualTo(Decision.IMMUTABLE);
    assertThat(DEFAULTS.evaluate("capture.sql-parameters", "everything", AllowList.DEFAULT))
        .isEqualTo(Decision.NOT_ALLOWED);
    assertThat(DEFAULTS.evaluate("no.such.key", "x", EVERYTHING)).isEqualTo(Decision.UNKNOWN_KEY);
    assertThat(DEFAULTS.evaluate("log.max-files", "nope", EVERYTHING))
        .isEqualTo(Decision.IMMUTABLE);
  }

  @Test
  @DisplayName("T-SEC-23a reduce_masking (16 §5.5 step 3) is decided before invalid_value (step 4)")
  void reduceMaskingBeforeInvalidValue() {
    Map<String, String> values = new LinkedHashMap<String, String>();
    values.put("capture.mask-patterns", "[\"ORD-\\\\d+\", \"cust-[0-9]+\"]");
    values.put("commands.env-dump.mask-patterns", "(?i)vault");
    RemoteConfigGate gate = RemoteConfigGate.of(AgentConfig.of(values));
    AllowList local = AllowList.of(Arrays.asList("capture.mask-patterns", "commands.**"));

    // a current pattern removed, only a pattern that does not compile pushed: reduce_masking
    assertThat(gate.evaluate("capture.mask-patterns", "[\"(unclosed\"]", local))
        .isEqualTo(Decision.REDUCE_MASKING);
    assertThat(gate.evaluate("capture.mask-patterns", "[\"ORD-\\\\d+\", \"(unclosed\"]", local))
        .isEqualTo(Decision.REDUCE_MASKING);
    assertThat(gate.evaluate("commands.env-dump.mask-patterns", "(unclosed", local))
        .isEqualTo(Decision.REDUCE_MASKING);
    // every current pattern kept, a pattern that does not compile added: invalid_value
    assertThat(
            gate.evaluate(
                "capture.mask-patterns", "[\"ORD-\\\\d+\", \"cust-[0-9]+\", \"(unclosed\"]", local))
        .isEqualTo(Decision.INVALID_VALUE);
    assertThat(gate.evaluate("commands.env-dump.mask-patterns", "(?i)vault, (unclosed", local))
        .isEqualTo(Decision.INVALID_VALUE);
    // every current pattern kept but over the item cap: invalid_value
    StringBuilder many = new StringBuilder("[\"ORD-\\\\d+\", \"cust-[0-9]+\"");
    for (int i = 0; i < ConfigKeys.spec("capture.mask-patterns").maxItems(); i++) {
      many.append(", \"p").append(i).append('"');
    }
    assertThat(gate.evaluate("capture.mask-patterns", many.append(']').toString(), local))
        .isEqualTo(Decision.INVALID_VALUE);
    // not a list at all (unclosed quote) or no value: no items to compare, invalid_value
    assertThat(gate.evaluate("capture.mask-patterns", "[\"ORD-\\\\d+, cust]", local))
        .isEqualTo(Decision.INVALID_VALUE);
    assertThat(gate.evaluate("capture.mask-patterns", null, local))
        .isEqualTo(Decision.INVALID_VALUE);
    // the allow list still comes first
    assertThat(gate.evaluate("capture.mask-patterns", "[\"(unclosed\"]", AllowList.DEFAULT))
        .isEqualTo(Decision.NOT_ALLOWED);
  }

  @Test
  @DisplayName("T-SEC-23a unknown and hostile keys never throw")
  void unknownAndHostile() {
    for (String key :
        Arrays.asList(
            null,
            "",
            "   ",
            "no.such.key",
            "sampling.profile.slow-threshold-ms.services.",
            "LOG.LEVEL",
            "log.level\u0000",
            "argos.",
            " log.level ",
            repeat('k', 100_000))) {
      assertThat(DEFAULTS.evaluate(key, "x", EVERYTHING))
          .as(String.valueOf(key))
          .isEqualTo(Decision.UNKNOWN_KEY);
    }
  }

  @Test
  @DisplayName("evaluate is pure: the compared configuration does not change")
  void pure() {
    AgentConfig current = AgentConfig.of(Collections.singletonMap("log.level", "WARN"));
    RemoteConfigGate gate = RemoteConfigGate.of(current);
    gate.evaluate("log.level", "DEBUG", AllowList.DEFAULT);
    assertThat(current.logLevel()).isEqualTo("WARN");
    assertThat(gate.current()).isSameAs(current);
  }

  private static String repeat(char c, int count) {
    return YamlSubsetTest.repeat(c, count);
  }
}
