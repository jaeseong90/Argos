/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.command;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.bootstrap.CaptureConfig;
import io.argos.agent.bootstrap.KillSwitch;
import io.argos.agent.bootstrap.ModuleHealth;
import io.argos.agent.core.config.ConfigHash;
import io.argos.agent.core.config.EffectiveConfig.Source;
import io.argos.agent.core.log.LogCapture;
import io.argos.agent.core.masking.Masker;
import io.argos.proto.v1.AgentMessage;
import io.argos.proto.v1.ConfigApplyResult;
import io.argos.proto.v1.ConfigPush;
import io.argos.proto.v1.RejectCode;
import io.argos.proto.v1.RejectedKey;
import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The remote configuration apply path of WO-17 (10 §12.2, 16 §5.5, SEC-23, ADR-0010 C-118 (3)) on a
 * configuration loaded like at start, with the real runtime parts (tracer, sampler, kill switch,
 * module states, capture modes, masker).
 */
@Tag("req:SEC-23")
@Tag("req:F-A13")
@Tag("req:DSN-02-17")
class ConfigPushApplierTest {
  @TempDir File dir;
  private RemoteFixture fixture;

  @AfterEach
  void restore() {
    if (fixture != null) {
      fixture.close();
    }
  }

  private RemoteFixture fixture(Map<String, String> properties, String... yml) throws Exception {
    fixture = new RemoteFixture(dir, properties, yml);
    return fixture;
  }

  private static ConfigPush push(String changeId, String... keyValues) {
    ConfigPush.Builder push = ConfigPush.newBuilder().setChangeId(changeId);
    for (int i = 0; i < keyValues.length; i += 2) {
      push.putValues(keyValues[i], keyValues[i + 1]);
    }
    return push.build();
  }

  private static Map<String, RejectedKey> rejected(ConfigApplyResult result) {
    Map<String, RejectedKey> out = new HashMap<String, RejectedKey>();
    for (RejectedKey key : result.getRejectedList()) {
      out.put(key.getKey(), key);
    }
    return out;
  }

  @Test
  @DisplayName(
      "T-AG-069/T-SEC-23 allowed keys are applied, disallowed ones rejected with the reason code,"
          + " their local value unchanged; partial apply, new hash to the session, no values in"
          + " the answer")
  void hardCap() throws Exception {
    RemoteFixture f = fixture(RemoteFixture.required(), "capture.sql-parameters: masked");
    String before = f.config.configHash();
    ConfigApplyResult result =
        f.pushes.apply(
            push(
                "change-1",
                "sampling.profile.normal-rate",
                "0.25",
                "log.level",
                "INFO",
                "capture.sql-parameters",
                "full",
                "server.address",
                "evil.example:1",
                "argos.remote-config.allow",
                "[\"**\"]",
                "no.such.key",
                "secret-sentinel-1",
                "sampling.profile.max-steps",
                "lots"));
    assertThat(result.getChangeId()).isEqualTo("change-1");
    assertThat(result.getAppliedKeysList())
        .containsExactly("log.level", "sampling.profile.normal-rate");
    Map<String, RejectedKey> rejected = rejected(result);
    assertThat(rejected).hasSize(5);
    assertThat(rejected.get("capture.sql-parameters").getCode()).isEqualTo(RejectCode.NOT_ALLOWED);
    assertThat(rejected.get("capture.sql-parameters").getMessage()).startsWith("not_allowed: ");
    assertThat(rejected.get("server.address").getCode()).isEqualTo(RejectCode.NOT_ALLOWED);
    assertThat(rejected.get("server.address").getMessage()).startsWith("immutable: ");
    assertThat(rejected.get("remote-config.allow").getMessage()).startsWith("immutable: ");
    assertThat(rejected.get("no.such.key").getCode()).isEqualTo(RejectCode.UNKNOWN_KEY);
    assertThat(rejected.get("sampling.profile.max-steps").getCode())
        .isEqualTo(RejectCode.INVALID_VALUE);
    assertThat(rejected.get("sampling.profile.max-steps").getMessage())
        .startsWith("invalid_value: expected");

    // applied: in force, source remote, on the tracer
    assertThat(f.config.config().normalRate()).isEqualTo(0.25);
    assertThat(f.config.effective().source("sampling.profile.normal-rate"))
        .isEqualTo(Source.REMOTE);
    assertThat(f.runtime.tracerImpl().sampler().normalRate()).isEqualTo(0.25);
    // rejected: the local value stays, with its local source
    assertThat(f.config.config().captureSqlParameters()).isEqualTo("masked");
    assertThat(f.config.effective().source("capture.sql-parameters")).isEqualTo(Source.FILE);
    assertThat(CaptureConfig.current().sqlParamMode()).isEqualTo(CaptureConfig.SQL_PARAMS_MASKED);
    assertThat(f.config.config().serverAddress()).isEqualTo("collector.example:9100");
    assertThat(f.config.allow().patterns()).doesNotContain("**");
    assertThat(f.config.config().maxSteps()).isEqualTo(1000);
    // hash: recomputed, answered and handed to the session once
    assertThat(result.getEffectiveConfigHash())
        .isEqualTo(ConfigHash.of(f.config.config()))
        .isNotEqualTo(before);
    assertThat(f.hashes).containsExactly(result.getEffectiveConfigHash());
    assertThat(result.toString())
        .doesNotContain("evil.example")
        .doesNotContain("secret-sentinel-1")
        .doesNotContain("lots");
  }

  @Test
  @DisplayName(
      "T-SEC-23 16 §5.5 step 6: one WARN 'remote config rejected keys: k(reason), ...' with key"
          + " names and reasons only, never values")
  void warnWithoutValues() throws Exception {
    RemoteFixture f = fixture(RemoteFixture.required());
    try (LogCapture log = LogCapture.start("INFO")) {
      f.pushes.apply(
          push(
              "c",
              "capture.sql-parameters",
              "full",
              "no.such.key",
              "secret-sentinel-2",
              "evil key\n",
              "secret-sentinel-3",
              "sampling.profile.normal-rate",
              // A value that cannot appear in a log timestamp (HH:mm:ss.SSS): "0.5" matched
              // e.g. "06:09:40.548" and made the assertion below flaky.
              "0.4375"));
      List<String> warns = log.linesAt("WARN");
      assertThat(warns).hasSize(1);
      assertThat(warns.get(0))
          .contains(
              "remote config rejected keys: capture.sql-parameters(not_allowed),"
                  + " evil?key(unknown_key), no.such.key(unknown_key)");
      assertThat(log.text())
          .doesNotContain("secret-sentinel")
          .doesNotContain("full")
          .doesNotContain("0.4375");
    }
  }

  @Test
  @DisplayName(
      "T-AG-069 C-118 (3): a pushed value wins over a locally set one for allow-listed keys only;"
          + " a key outside the allow list keeps its local value; -Dargos.remote-config.allow widens"
          + " the list")
  void remoteWinsForAllowedKeysOnly() throws Exception {
    RemoteFixture f =
        fixture(
            RemoteFixture.required(),
            "sampling.profile.normal-rate: 0.5",
            "capture.query-string: off");
    ConfigApplyResult result =
        f.pushes.apply(
            push("c", "sampling.profile.normal-rate", "0.1", "capture.query-string", "full"));
    assertThat(result.getAppliedKeysList()).containsExactly("sampling.profile.normal-rate");
    assertThat(f.config.config().normalRate()).isEqualTo(0.1);
    assertThat(f.config.state().local().normalRate()).isEqualTo(0.5);
    assertThat(f.config.config().captureQueryString()).isEqualTo("off");
    assertThat(f.config.effective().source("capture.query-string")).isEqualTo(Source.FILE);
    f.close();

    Map<String, String> widened = RemoteFixture.required();
    widened.put("argos.remote-config.allow", "[\"sampling.**\", capture.query-string]");
    RemoteFixture g = fixture(widened, "capture.query-string: off");
    ConfigApplyResult widenedResult = g.pushes.apply(push("c", "capture.query-string", "full"));
    assertThat(widenedResult.getAppliedKeysList()).containsExactly("capture.query-string");
    assertThat(g.config.config().captureQueryString()).isEqualTo("full");
    assertThat(CaptureConfig.current().queryStringMode())
        .isEqualTo(CaptureConfig.QUERY_STRING_FULL);
  }

  @Test
  @Tag("req:SEC-20")
  @DisplayName(
      "T-SEC-20/T-SEC-23 mask patterns are add-only: dropping one is FORBIDDEN_PATTERN"
          + " (reduce_masking) for capture and env-dump patterns; adding one installs a new masker")
  void maskPatternsAddOnly() throws Exception {
    Map<String, String> properties = RemoteFixture.required();
    properties.put(
        "argos.remote-config.allow",
        "[capture.mask-patterns, commands.env-dump.mask-patterns, log.level]");
    RemoteFixture f =
        fixture(
            properties,
            "capture.mask-patterns: [\"ORD-[0-9]{6}\"]",
            "commands.env-dump.mask-patterns: [\"(?i)tenant\"]");
    Masker before = Masker.current();
    ConfigApplyResult reduce =
        f.pushes.apply(
            push(
                "c-1",
                "capture.mask-patterns",
                "[\"INV-[0-9]{4}\"]",
                "commands.env-dump.mask-patterns",
                "[]"));
    assertThat(reduce.getAppliedKeysList()).isEmpty();
    assertThat(reduce.getRejectedList())
        .extracting(RejectedKey::getCode)
        .containsOnly(RejectCode.FORBIDDEN_PATTERN);
    assertThat(reduce.getRejectedList())
        .allSatisfy(key -> assertThat(key.getMessage()).startsWith("reduce_masking: "));
    assertThat(f.config.config().captureMaskPatterns()).containsExactly("ORD-[0-9]{6}");
    assertThat(Masker.current()).isSameAs(before);

    ConfigApplyResult add =
        f.pushes.apply(
            push("c-2", "capture.mask-patterns", "[\"ORD-[0-9]{6}\", \"INV-[0-9]{4}\"]"));
    assertThat(add.getAppliedKeysList()).containsExactly("capture.mask-patterns");
    assertThat(Masker.current()).isNotSameAs(before);
    assertThat(Masker.current().mask("invoice INV-1234 order ORD-123456"))
        .doesNotContain("INV-1234")
        .doesNotContain("ORD-123456");
  }

  @Test
  @DisplayName(
      "T-AG-069 keys read only at start are rejected NOT_ALLOWED restart_required even when the"
          + " allow list names them; the value in force does not change")
  void restartRequired() throws Exception {
    Map<String, String> properties = RemoteFixture.required();
    properties.put("argos.remote-config.allow", "[buffer.*, sql.max-length, propagation.**]");
    RemoteFixture f = fixture(properties);
    ConfigApplyResult result =
        f.pushes.apply(
            push(
                "c",
                "buffer.max-age-seconds",
                "120",
                "sql.max-length",
                "2048",
                "propagation.preserve-incoming",
                "false"));
    assertThat(result.getAppliedKeysList()).isEmpty();
    assertThat(result.getRejectedList()).hasSize(3);
    assertThat(result.getRejectedList())
        .allSatisfy(
            key -> {
              assertThat(key.getCode()).isEqualTo(RejectCode.NOT_ALLOWED);
              assertThat(key.getMessage()).isEqualTo(RuntimeApplier.RESTART_REQUIRED);
            });
    assertThat(f.config.config().bufferMaxAgeSeconds())
        .isEqualTo(f.loaded.config().bufferMaxAgeSeconds());
    assertThat(f.hashes).isEmpty();
  }

  @Test
  @Tag("req:F-A15")
  @DisplayName(
      "T-AG-043 enabled=false turns the kill switch on with AgentEvent KILL_SWITCH_ON, enabled=true"
          + " turns it off with KILL_SWITCH_OFF; other keys of the same push still apply")
  void killSwitch() throws Exception {
    RemoteFixture f = fixture(RemoteFixture.required());
    ConfigApplyResult off =
        f.pushes.apply(push("c-1", "enabled", "false", "sampling.profile.normal-rate", "0.3"));
    assertThat(off.getAppliedKeysList()).containsExactly("enabled", "sampling.profile.normal-rate");
    assertThat(KillSwitch.ENABLED).isFalse();
    assertThat(f.events).containsExactly("KILL_SWITCH_ON");
    assertThat(f.runtime.tracerImpl().sampler().normalRate()).isEqualTo(0.3);

    f.pushes.apply(push("c-2", "argos.enabled", "true"));
    assertThat(KillSwitch.ENABLED).isTrue();
    assertThat(f.events).containsExactly("KILL_SWITCH_ON", "KILL_SWITCH_OFF");

    f.pushes.apply(push("c-3", "enabled", "true"));
    assertThat(f.events).as("no change, no event").hasSize(2);
  }

  @Test
  @DisplayName(
      "T-AG-069 instrumentation.disabled: an installed module goes DISABLED_BY_REMOTE and back;"
          + " enabling a module that was off at start is rejected restart_required")
  void modules() throws Exception {
    RemoteFixture f =
        fixture(RemoteFixture.required(), "instrumentation.disabled: [httpclient-okhttp]");
    ConfigApplyResult off =
        f.pushes.apply(push("c-1", "instrumentation.disabled", "[jdbc, httpclient-okhttp]"));
    assertThat(off.getAppliedKeysList()).containsExactly("instrumentation.disabled");
    assertThat(ModuleHealth.state(RemoteFixture.JDBC)).isEqualTo(ModuleHealth.DISABLED_BY_REMOTE);
    assertThat(ModuleHealth.state(RemoteFixture.SERVLET)).isEqualTo(ModuleHealth.ENABLED);

    ConfigApplyResult back =
        f.pushes.apply(push("c-2", "instrumentation.disabled", "[httpclient-okhttp]"));
    assertThat(back.getAppliedKeysList()).containsExactly("instrumentation.disabled");
    assertThat(ModuleHealth.state(RemoteFixture.JDBC)).isEqualTo(ModuleHealth.ENABLED);

    ConfigApplyResult enableStartOff =
        f.pushes.apply(push("c-3", "instrumentation.disabled", "[]"));
    assertThat(enableStartOff.getAppliedKeysList()).isEmpty();
    assertThat(enableStartOff.getRejected(0).getCode()).isEqualTo(RejectCode.NOT_ALLOWED);
    assertThat(enableStartOff.getRejected(0).getMessage())
        .startsWith("restart_required: ")
        .contains("httpclient-okhttp");
    assertThat(f.config.config().instrumentationDisabled()).containsExactly("httpclient-okhttp");
  }

  @Test
  @DisplayName(
      "T-AG-069 instrumentation.ignore-paths reaches the tracer; a module disabled by errors is not"
          + " re-enabled by a remote change")
  void ignorePathsAndErrorState() throws Exception {
    RemoteFixture f = fixture(RemoteFixture.required());
    assertThat(f.runtime.tracerImpl().ignoreReason("GET", "/health/live", null)).isNull();
    f.pushes.apply(push("c-1", "instrumentation.ignore-paths", "[\"/health/**\"]"));
    assertThat(f.runtime.tracerImpl().ignoreReason("GET", "/health/live", null)).isNotNull();

    f.control.setModuleState(RemoteFixture.SERVLET, ModuleHealth.DISABLED_BY_ERRORS);
    f.pushes.apply(push("c-2", "instrumentation.disabled", "[servlet]"));
    f.pushes.apply(push("c-3", "instrumentation.disabled", "[]"));
    assertThat(ModuleHealth.state(RemoteFixture.SERVLET))
        .isEqualTo(ModuleHealth.DISABLED_BY_ERRORS);
  }

  @Test
  @DisplayName(
      "11 §4.2: a remote sampling change keeps the server's throttle cap; a throttle never raises"
          + " the configured rate")
  void throttleCapStays() throws Exception {
    RemoteFixture f = fixture(RemoteFixture.required(), "sampling.profile.normal-rate: 0.5");
    f.sampling.throttle(0.1f);
    assertThat(f.runtime.tracerImpl().sampler().normalRate()).isCloseTo(0.1, within());
    f.pushes.apply(push("c-1", "sampling.profile.normal-rate", "0.8"));
    assertThat(f.runtime.tracerImpl().sampler().normalRate()).isCloseTo(0.1, within());
    f.pushes.apply(push("c-2", "sampling.profile.normal-rate", "0.05"));
    assertThat(f.runtime.tracerImpl().sampler().normalRate()).isCloseTo(0.05, within());
    f.sampling.throttle(1.0f);
    assertThat(f.runtime.tracerImpl().sampler().normalRate()).isCloseTo(0.05, within());
  }

  private static org.assertj.core.data.Offset<Double> within() {
    return org.assertj.core.data.Offset.offset(1e-6);
  }

  @Test
  @DisplayName(
      "T-AG-069 log.level, metrics.interval-seconds and transaction.max-duration-ms change at run"
          + " time when the allow list names them; duration_ms is measured")
  void otherRuntimeKeys() throws Exception {
    Map<String, String> properties = RemoteFixture.required();
    properties.put(
        "argos.remote-config.allow",
        "[log.level, metrics.interval-seconds, transaction.max-duration-ms]");
    RemoteFixture f = fixture(properties);
    try (LogCapture log = LogCapture.start("INFO")) {
      f.nanos.set(0);
      ConfigApplyResult result =
          f.pushes.apply(
              push(
                  "c",
                  "log.level",
                  "DEBUG",
                  "metrics.interval-seconds",
                  "10",
                  "transaction.max-duration-ms",
                  "60000"));
      assertThat(result.getAppliedKeysList())
          .containsExactly("log.level", "metrics.interval-seconds", "transaction.max-duration-ms");
      assertThat(io.argos.agent.core.log.AgentLogger.isDebugEnabled()).isTrue();
      assertThat(log.lines()).isNotNull();
      assertThat(f.config.config().metricsIntervalSeconds()).isEqualTo(10);
      assertThat(f.config.config().transactionMaxDurationMs()).isEqualTo(60000);
    }
    f.nanos.set(0);
    ConfigPushApplier slow =
        new ConfigPushApplier(
            f.config,
            f.applier,
            null,
            new java.util.function.LongSupplier() {
              private long calls;

              @Override
              public long getAsLong() {
                return TimeUnit.MILLISECONDS.toNanos(1500) * calls++;
              }
            },
            f.control);
    assertThat(slow.apply(push("d", "log.level", "WARN")).getDurationMs()).isEqualTo(1500);
  }

  @Test
  @DisplayName(
      "T-SEC-23 through the dispatcher: a push is applied on argos-command and answered once; more"
          + " than 8 waiting pushes are answered LIMIT_EXCEEDED busy, nothing applied")
  void throughDispatcher() throws Exception {
    RemoteFixture f = fixture(RemoteFixture.required());
    CommandDispatcher dispatcher =
        new CommandDispatcher(
            Collections.<io.argos.proto.v1.CommandType, CommandHandler>emptyMap(),
            f.pushes,
            null,
            f.clock(),
            null);
    CommandDispatcherTest.Sink[] sinks = new CommandDispatcherTest.Sink[9];
    for (int i = 0; i < sinks.length; i++) {
      sinks[i] = new CommandDispatcherTest.Sink();
      dispatcher.configPush(push("c-" + i, "log.level", "INFO"), sinks[i]);
    }
    assertThat(sinks[8].messages).hasSize(1);
    ConfigApplyResult busy = sinks[8].messages.get(0).getConfigApplyResult();
    assertThat(busy.getChangeId()).isEqualTo("c-8");
    assertThat(busy.getRejected(0).getCode()).isEqualTo(RejectCode.LIMIT_EXCEEDED);
    assertThat(busy.getAppliedKeysList()).isEmpty();
    assertThat(sinks[0].messages).isEmpty();
    dispatcher.runPending();
    for (int i = 0; i < 8; i++) {
      List<AgentMessage> answers = sinks[i].messages;
      assertThat(answers).hasSize(1);
      assertThat(answers.get(0).getConfigApplyResult().getAppliedKeysList())
          .containsExactly("log.level");
    }
  }
}
