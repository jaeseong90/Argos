/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.command;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.bootstrap.KillSwitch;
import io.argos.agent.core.config.EffectiveConfig.Source;
import io.argos.agent.core.log.LogCapture;
import io.argos.proto.v1.CommandType;
import io.argos.proto.v1.ConfigPush;
import io.argos.proto.v1.RejectCode;
import io.argos.proto.v1.RejectedKey;
import java.io.File;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code ServerHello.config} (ADR-0010 C-231 (4), 11 §4.2, WO-17): on every established session the
 * remote overlay becomes the gated values the server holds; nothing is reported back.
 */
@Tag("req:SEC-23")
@Tag("req:F-A13")
class HelloConfigTest {
  @TempDir File dir;
  private RemoteFixture fixture;

  @AfterEach
  void restore() {
    if (fixture != null) {
      fixture.close();
    }
  }

  private static Map<String, String> values(String... keyValues) {
    Map<String, String> map = new LinkedHashMap<String, String>();
    for (int i = 0; i < keyValues.length; i += 2) {
      map.put(keyValues[i], keyValues[i + 1]);
    }
    return map;
  }

  @Test
  @DisplayName(
      "C-231 (4) the hello config replaces the remote overlay: a key the server no longer holds"
          + " falls back to the local value (and source); a held key is in force with source"
          + " remote")
  void replacesOverlay() throws Exception {
    fixture =
        new RemoteFixture(
            dir, RemoteFixture.required(), "log.level: WARN", "sampling.profile.normal-rate: 0.5");
    fixture.pushes.apply(
        ConfigPush.newBuilder()
            .setChangeId("c")
            .putValues("log.level", "DEBUG")
            .putValues("sampling.profile.normal-rate", "0.2")
            .build());
    assertThat(fixture.config.config().logLevel()).isEqualTo("DEBUG");
    String hashAfterPush = fixture.config.configHash();

    List<RejectedKey> rejected =
        fixture.pushes.applyHello(values("sampling.profile.normal-rate", "0.3"));
    assertThat(rejected).isEmpty();
    assertThat(fixture.config.state().overlay()).containsOnlyKeys("sampling.profile.normal-rate");
    assertThat(fixture.config.config().normalRate()).isEqualTo(0.3);
    assertThat(fixture.config.effective().source("sampling.profile.normal-rate"))
        .isEqualTo(Source.REMOTE);
    assertThat(fixture.config.config().logLevel()).isEqualTo("WARN");
    assertThat(fixture.config.effective().source("log.level")).isEqualTo(Source.FILE);
    assertThat(fixture.runtime.tracerImpl().sampler().normalRate()).isEqualTo(0.3);
    assertThat(fixture.hashes).last().isEqualTo(fixture.config.configHash());
    assertThat(fixture.config.configHash()).isNotEqualTo(hashAfterPush);

    fixture.pushes.applyHello(Collections.<String, String>emptyMap());
    assertThat(fixture.config.state().overlay()).isEmpty();
    assertThat(fixture.config.config().normalRate()).isEqualTo(0.5);
    assertThat(fixture.config.configHash()).isEqualTo(fixture.loaded.configHash());
  }

  @Test
  @DisplayName(
      "T-SEC-23 C-231 (4) hello values are gated like pushes: rejected keys are not applied, give"
          + " one WARN with key names only, and are not reported to the server")
  void gated() throws Exception {
    fixture = new RemoteFixture(dir, RemoteFixture.required());
    try (LogCapture log = LogCapture.start("INFO")) {
      List<RejectedKey> rejected =
          fixture.pushes.applyHello(
              values(
                  "capture.sql-parameters", "full",
                  "server.address", "evil.example:1",
                  "buffer.max-age-seconds", "1",
                  "log.level", "DEBUG"));
      assertThat(rejected)
          .extracting(RejectedKey::getKey)
          .containsExactly("buffer.max-age-seconds", "capture.sql-parameters", "server.address");
      assertThat(rejected).extracting(RejectedKey::getCode).containsOnly(RejectCode.NOT_ALLOWED);
      assertThat(fixture.config.state().overlay()).containsOnlyKeys("log.level");
      assertThat(fixture.config.config().captureSqlParameters()).isEqualTo("masked");
      List<String> warns = log.linesAt("WARN");
      assertThat(warns).hasSize(1);
      assertThat(warns.get(0))
          .contains("remote config rejected keys (ServerHello config): ")
          .contains("capture.sql-parameters(not_allowed)")
          .contains("server.address(immutable)")
          .contains("buffer.max-age-seconds(not_allowed)");
      assertThat(log.text()).doesNotContain("evil.example").doesNotContain("full");
    }
  }

  @Test
  @Tag("req:F-A15")
  @DisplayName(
      "T-AG-043 C-231 (4) enabled=false in the hello config turns the kill switch on at once on"
          + " argos-io, before argos-command applies the rest (then KILL_SWITCH_ON); only the"
          + " latest waiting hello config is applied")
  void killSwitchAtOnce() throws Exception {
    fixture = new RemoteFixture(dir, RemoteFixture.required());
    CommandDispatcher dispatcher =
        new CommandDispatcher(
            Collections.<CommandType, CommandHandler>emptyMap(),
            fixture.pushes,
            null,
            fixture.clock(),
            null);
    dispatcher.helloConfig(values("argos.enabled", "false", "log.level", "DEBUG"));
    assertThat(KillSwitch.ENABLED).as("before argos-command ran").isFalse();
    assertThat(fixture.events).isEmpty();
    dispatcher.helloConfig(values("enabled", "false", "sampling.profile.normal-rate", "0.4"));
    assertThat(dispatcher.queued()).as("one waiting hello config").isEqualTo(1);
    dispatcher.runPending();
    assertThat(fixture.config.state().overlay())
        .containsOnlyKeys("enabled", "sampling.profile.normal-rate");
    assertThat(fixture.events).containsExactly("KILL_SWITCH_ON");
    assertThat(KillSwitch.ENABLED).isFalse();

    dispatcher.helloConfig(Collections.<String, String>emptyMap());
    dispatcher.runPending();
    assertThat(KillSwitch.ENABLED).as("back to the local enabled=true").isTrue();
    assertThat(fixture.events).containsExactly("KILL_SWITCH_ON", "KILL_SWITCH_OFF");
  }

  @Test
  @Tag("req:F-A15")
  @DisplayName(
      "T-AG-043 enabled=false is not applied at once when the local allow list leaves it out")
  void killSwitchHonoursAllowList() throws Exception {
    Map<String, String> properties = RemoteFixture.required();
    properties.put("argos.remote-config.allow", "[log.level]");
    fixture = new RemoteFixture(dir, properties);
    fixture.pushes.helloKillSwitch(values("enabled", "false"));
    assertThat(KillSwitch.ENABLED).isTrue();
    fixture.pushes.applyHello(values("enabled", "false"));
    assertThat(KillSwitch.ENABLED).isTrue();
    assertThat(fixture.events).isEmpty();
  }
}
