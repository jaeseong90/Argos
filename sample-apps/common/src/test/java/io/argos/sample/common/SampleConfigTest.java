/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.sample.common.db.DbKind;
import io.argos.sample.common.db.PoolKind;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Environment switches of 18 §4.1 / §4.4. */
class SampleConfigTest {

  private static final BuildInfo BUILD = new BuildInfo("shop-test", "1.2.3");

  @Test
  @DisplayName("Defaults: in-memory H2 named after the app, HikariCP 10, no switches")
  void defaults() {
    SampleConfig config = SampleConfig.from(new HashMap<String, String>(), new Properties(), BUILD);
    assertThat(config.db()).isEqualTo(DbKind.H2);
    assertThat(config.dbUrl()).isEqualTo("jdbc:h2:mem:shop-test;DB_CLOSE_DELAY=-1");
    assertThat(config.pool()).isEqualTo(PoolKind.HIKARI);
    assertThat(config.poolMaxSize()).isEqualTo(10);
    assertThat(config.routingDataSource()).isFalse();
    assertThat(config.virtualThreads()).isFalse();
    assertThat(config.customExecutor()).isFalse();
    assertThat(config.extUrl()).isEqualTo("http://127.0.0.1:18080");
    assertThat(config.extRefusedPort()).isEqualTo(1);
    assertThat(config.fixedClock()).isNull();
    assertThat(config.appName()).isEqualTo("shop-test");
    assertThat(config.appVersion()).isEqualTo("1.2.3");
  }

  @Test
  @DisplayName("Every switch is parsed; booleans accept true/1/yes/on")
  void switches() {
    Map<String, String> env = new HashMap<>();
    env.put("SAMPLE_DB", "postgresql");
    env.put("SAMPLE_DB_URL", "jdbc:postgresql://db:5432/x");
    env.put("SAMPLE_POOL", "DBCP2");
    env.put("SAMPLE_POOL_MAX", "4");
    env.put("SAMPLE_ROUTING_DS", "true");
    env.put("SAMPLE_VT", "1");
    env.put("SAMPLE_CUSTOM_EXECUTOR", "yes");
    env.put("SAMPLE_EXT_URL", "http://ext-stub:18080/");
    env.put("SAMPLE_FIXED_CLOCK", "2026-02-01T00:00:00Z");
    Properties system = new Properties();
    system.setProperty(SampleConfig.FIXED_CLOCK_PROPERTY, "2026-01-15T00:00:00Z");
    SampleConfig config = SampleConfig.from(env, system, BUILD);
    assertThat(config.db()).isEqualTo(DbKind.POSTGRES);
    assertThat(config.dbUrl()).isEqualTo("jdbc:postgresql://db:5432/x");
    assertThat(config.pool()).isEqualTo(PoolKind.DBCP2);
    assertThat(config.poolMaxSize()).isEqualTo(4);
    assertThat(config.routingDataSource()).isTrue();
    assertThat(config.virtualThreads()).isTrue();
    assertThat(config.customExecutor()).isTrue();
    assertThat(config.extUrl()).isEqualTo("http://ext-stub:18080");
    // -Dsample.fixed-clock wins over SAMPLE_FIXED_CLOCK.
    assertThat(config.fixedClock()).isEqualTo(Instant.parse("2026-01-15T00:00:00Z"));
  }

  @Test
  @DisplayName("Invalid values fail with the variable name")
  void invalidValues() {
    assertThatThrownBy(() -> config("SAMPLE_DB", "db2")).hasMessageContaining("SAMPLE_DB");
    assertThatThrownBy(() -> config("SAMPLE_POOL", "c3p0")).hasMessageContaining("SAMPLE_POOL");
    assertThatThrownBy(() -> config("SAMPLE_POOL_MAX", "0"))
        .hasMessageContaining("SAMPLE_POOL_MAX");
    assertThatThrownBy(() -> config("SAMPLE_VT", "maybe")).hasMessageContaining("SAMPLE_VT");
    assertThatThrownBy(() -> config("SAMPLE_FIXED_CLOCK", "yesterday"))
        .hasMessageContaining("sample.fixed-clock");
  }

  private static SampleConfig config(String key, String value) {
    Map<String, String> env = new HashMap<>();
    env.put(key, value);
    return SampleConfig.from(env, new Properties(), BUILD);
  }
}
