/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** ConfigHash (10 §4.3 P4, §12.2, 16 §2.6): canonical, stable, without the token. */
class ConfigHashTest {
  private static final String TOKEN = "argos_" + "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_abcde";

  private static AgentConfig config(String... keyValues) {
    Map<String, String> values = new LinkedHashMap<String, String>();
    for (int i = 0; i < keyValues.length; i += 2) {
      values.put(keyValues[i], keyValues[i + 1]);
    }
    return AgentConfig.of(values);
  }

  @Test
  @DisplayName("16 lower-case hex digits; the same effective values give the same hash")
  void stable() {
    String hash = ConfigHash.of(AgentConfig.defaults());
    assertThat(hash).matches("[0-9a-f]{16}");
    assertThat(ConfigHash.of(AgentConfig.of(Collections.<String, String>emptyMap())))
        .isEqualTo(hash);
    // a value equal to its default gives the default hash
    assertThat(ConfigHash.of(config("log.level", "info"))).isEqualTo(hash);
    // the way a value is written does not matter
    assertThat(ConfigHash.of(config("buffer.max-bytes", "32MB")))
        .isEqualTo(ConfigHash.of(config("buffer.max-bytes", "33554432")));
    assertThat(ConfigHash.of(config("instrumentation.disabled", "jdbc, executor")))
        .isEqualTo(ConfigHash.of(config("instrumentation.disabled", "[\"jdbc\", 'executor']")));
    assertThat(ConfigHash.of(config("sampling.profile.normal-rate", "0.50")))
        .isEqualTo(ConfigHash.of(config("sampling.profile.normal-rate", ".5")));
  }

  @Test
  @DisplayName("every value change changes the hash, including per-service thresholds")
  void changes() {
    List<String> hashes = new ArrayList<String>();
    hashes.add(ConfigHash.of(AgentConfig.defaults()));
    hashes.add(ConfigHash.of(config("log.level", "DEBUG")));
    hashes.add(ConfigHash.of(config("instrumentation.ignore-paths", "")));
    hashes.add(ConfigHash.of(config("instrumentation.ignore-paths", "/a")));
    hashes.add(ConfigHash.of(config("server.token-file", "/etc/argos/token")));
    hashes.add(ConfigHash.of(config("sampling.profile.slow-threshold-ms.services.a", "5")));
    hashes.add(ConfigHash.of(config("sampling.profile.slow-threshold-ms.services.a", "6")));
    hashes.add(ConfigHash.of(config("app.name", "a")));
    hashes.add(ConfigHash.of(config("instrumentation.app-packages", "")));
    assertThat(hashes).doesNotHaveDuplicates();
  }

  @Test
  @Tag("req:SEC-06")
  @DisplayName("T-SEC-06a the token is never part of the hash input")
  void tokenExcluded() {
    AgentConfig withToken = config("server.token", TOKEN);
    assertThat(withToken.serverToken().isPresent()).isTrue();
    assertThat(ConfigHash.of(withToken)).isEqualTo(ConfigHash.of(AgentConfig.defaults()));
    for (String line : ConfigHash.canonicalLines(withToken)) {
      assertThat(line).doesNotContain(TOKEN).doesNotStartWith("server.token=");
    }
  }

  @Test
  @DisplayName("canonical lines: sorted key=value, typed rendering, keys without a value left out")
  void canonicalLines() {
    List<String> lines =
        ConfigHash.canonicalLines(
            config(
                "sampling.profile.slow-threshold-ms.services.GET /a", "10",
                "buffer.max-bytes", "1KB"));
    List<String> sorted = new ArrayList<String>(lines);
    Collections.sort(sorted);
    assertThat(lines).isEqualTo(sorted);
    assertThat(lines)
        .contains(
            "buffer.max-bytes=1024",
            "enabled=true",
            "sampling.profile.normal-rate=0.1",
            "sampling.profile.slow-threshold-ms.services.GET /a=10",
            "instrumentation.ignore-methods=[\"OPTIONS\"]");
    assertThat(lines).noneMatch(line -> line.startsWith("server.address="));
    assertThat(lines).noneMatch(line -> line.startsWith("server.token"));
    assertThat(ConfigHash.render(java.util.Arrays.asList("a\"b", "c\\d")))
        .isEqualTo("[\"a\\\"b\",\"c\\\\d\"]");
    assertThat(ConfigHash.render("x\ny")).isEqualTo("x\\ny");
  }
}
