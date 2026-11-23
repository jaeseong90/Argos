/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.command;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.core.config.EffectiveConfig.Source;
import io.argos.proto.v1.ConfigApplyResult;
import io.argos.proto.v1.ConfigPush;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code CONFIG_RELOAD} (10 §12.1, 16 §5.5 step 7, ADR-0010 C-118 (3), WO-17): the local file is
 * read again; local keys apply, the allow list is refreshed and the remote values are gated again.
 */
@Tag("req:SEC-23")
@Tag("req:F-A13")
class ReloadCommandTest {
  @TempDir File dir;
  private RemoteFixture fixture;

  @AfterEach
  void restore() {
    if (fixture != null) {
      fixture.close();
    }
  }

  @Test
  @DisplayName(
      "C-118 (3) reload: changed local keys apply, a key dropped from remote-config.allow loses its"
          + " remote value (rejected, back to local), a still allowed remote value stays; keys read"
          + " only at start keep their running value and are restart_required; CONFIG_FILE_RELOADED")
  void reload() throws Exception {
    fixture =
        new RemoteFixture(
            dir,
            Collections.<String, String>emptyMap(),
            "server.address: collector.example:9100",
            "app.name: orders",
            "app.env: test",
            "remote-config.allow: [\"sampling.**\", log.level, capture.query-string]",
            "capture.query-string: off",
            "log.level: INFO",
            "buffer.max-age-seconds: 30");
    ConfigApplyResult pushed =
        fixture.pushes.apply(
            ConfigPush.newBuilder()
                .setChangeId("c")
                .putValues("capture.query-string", "full")
                .putValues("sampling.profile.normal-rate", "0.2")
                .build());
    assertThat(pushed.getAppliedKeysList()).hasSize(2);
    assertThat(fixture.config.config().captureQueryString()).isEqualTo("full");

    fixture.writeYml(
        "server.address: other.example:9100",
        "app.name: renamed",
        "app.env: test",
        "remote-config.allow: [\"sampling.**\", log.level]",
        "capture.query-string: off",
        "log.level: WARN",
        "buffer.max-age-seconds: 90");
    CommandOutput output = fixture.reloadCommand().run(Collections.<String, String>emptyMap());
    String json = new String(output.payload(), StandardCharsets.UTF_8);
    assertThat(output.contentType()).isEqualTo("application/json");
    assertThat(output.truncated()).isFalse();

    // local key applied; the remote value of a key the new allow list drops is gone
    assertThat(fixture.config.config().logLevel()).isEqualTo("WARN");
    assertThat(fixture.config.config().captureQueryString()).isEqualTo("off");
    assertThat(fixture.config.effective().source("capture.query-string")).isEqualTo(Source.FILE);
    assertThat(fixture.config.allow().patterns()).containsExactly("sampling.**", "log.level");
    // a remote value the new list still allows stays in force (C-118 (3))
    assertThat(fixture.config.config().normalRate()).isEqualTo(0.2);
    assertThat(fixture.config.effective().source("sampling.profile.normal-rate"))
        .isEqualTo(Source.REMOTE);
    // read only at start: running values stay
    assertThat(fixture.config.config().serverAddress()).isEqualTo("collector.example:9100");
    assertThat(fixture.config.config().appName()).isEqualTo("orders");
    assertThat(fixture.config.config().bufferMaxAgeSeconds()).isEqualTo(30);
    assertThat(fixture.config.effective().value("buffer.max-age-seconds")).isEqualTo("30");

    assertThat(json)
        .contains("\"applied_keys\":[")
        .contains("\"log.level\"")
        .contains("\"capture.query-string\"")
        .contains("\"remote-config.allow\"")
        .contains(
            "\"rejected\":[{\"key\":\"capture.query-string\",\"code\":\"NOT_ALLOWED\","
                + "\"message\":\"not_allowed: ")
        .contains("\"effective_config_hash\":\"" + fixture.config.configHash() + "\"")
        .contains(
            "\"restart_required\":[\"app.name\",\"buffer.max-age-seconds\",\"server.address\"]")
        .doesNotContain("other.example")
        .doesNotContain("renamed");
    assertThat(fixture.events).containsExactly("CONFIG_FILE_RELOADED");
    assertThat(fixture.hashes).last().isEqualTo(fixture.config.configHash());
  }

  @Test
  @DisplayName(
      "C-118 (3) reload widens the allow list: a key added to remote-config.allow can be pushed"
          + " afterwards; local keys themselves are never hard-capped")
  void widenedAllowList() throws Exception {
    Map<String, String> properties = RemoteFixture.required();
    fixture = new RemoteFixture(dir, properties, "capture.sql-parameters: masked");
    assertThat(
            fixture
                .pushes
                .apply(
                    ConfigPush.newBuilder()
                        .setChangeId("c-1")
                        .putValues("capture.sql-parameters", "partial")
                        .build())
                .getAppliedKeysList())
        .isEmpty();
    fixture.writeYml(
        "capture.sql-parameters: partial",
        "remote-config.allow: [\"sampling.**\", capture.sql-parameters]");
    fixture.reloadCommand().run(Collections.<String, String>emptyMap());
    assertThat(fixture.config.config().captureSqlParameters()).isEqualTo("partial");
    ConfigApplyResult after =
        fixture.pushes.apply(
            ConfigPush.newBuilder()
                .setChangeId("c-2")
                .putValues("capture.sql-parameters", "full")
                .build());
    assertThat(after.getAppliedKeysList()).containsExactly("capture.sql-parameters");
    assertThat(fixture.config.config().captureSqlParameters()).isEqualTo("full");
  }

  @Test
  @DisplayName("reload payload: valid JSON strings for keys and messages with quotes")
  void jsonEscaping() {
    StringBuilder out = new StringBuilder();
    ReloadCommand.string(out, "a\"b\\c\n\u0001");
    assertThat(out.toString()).isEqualTo("\"a\\\"b\\\\c\\n\\u0001\"");
  }
}
