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
    assertThat(config.driverWrapper()).isFalse();
    assertThat(config.poolUrl()).isEqualTo(config.dbUrl());
    assertThat(config.poolDriverClass()).isEqualTo("org.h2.Driver");
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
  @DisplayName(
      "SAMPLE_DB mariadb, mssql (alias sqlserver), oracle: driver, account and URL;"
          + " SAMPLE_DRIVER_WRAPPER puts the wrapper in front of the real URL and driver (C-40)")
  void newDatabasesAndWrapper() {
    SampleConfig mariadb = config("SAMPLE_DB", "MariaDB");
    assertThat(mariadb.db()).isEqualTo(DbKind.MARIADB);
    assertThat(mariadb.dbUrl()).isEqualTo("jdbc:mariadb://127.0.0.1:3306/shop");
    assertThat(mariadb.dbUser()).isEqualTo("shop");
    assertThat(DbKind.MARIADB.driverClass()).isEqualTo("org.mariadb.jdbc.Driver");
    SampleConfig mssql = config("SAMPLE_DB", "sqlserver");
    assertThat(mssql.db()).isEqualTo(DbKind.MSSQL);
    assertThat(mssql.dbUser()).isEqualTo("sa");
    assertThat(mssql.dbUrl()).startsWith("jdbc:sqlserver://127.0.0.1:1433;databaseName=shop");
    SampleConfig oracle = config("SAMPLE_DB", "oracle");
    assertThat(oracle.db()).isEqualTo(DbKind.ORACLE);
    assertThat(oracle.dbUrl()).isEqualTo("jdbc:oracle:thin:@//127.0.0.1:1521/FREEPDB1");
    assertThat(DbKind.ORACLE.pingSql()).isEqualTo("SELECT 1 FROM DUAL");
    assertThat(DbKind.ORACLE.generatedKeyColumns()).containsExactly("ID");
    assertThat(DbKind.MSSQL.offsetFirst()).isTrue();
    assertThat(DbKind.MARIADB.pageClause()).isEqualTo(" LIMIT ? OFFSET ?");
    assertThat(DbKind.MARIADB.backslashEscapes()).isTrue();
    assertThat(DbKind.MSSQL.supportsDollarQuotes()).isFalse();

    Map<String, String> env = new HashMap<>();
    env.put("SAMPLE_DB", "postgres");
    env.put("SAMPLE_DRIVER_WRAPPER", "on");
    SampleConfig wrapped = SampleConfig.from(env, new Properties(), BUILD);
    assertThat(wrapped.driverWrapper()).isTrue();
    assertThat(wrapped.dbUrl()).isEqualTo("jdbc:postgresql://127.0.0.1:5432/shop");
    assertThat(wrapped.poolUrl())
        .isEqualTo("jdbc:argos-wrapper:jdbc:postgresql://127.0.0.1:5432/shop")
        .startsWith(example.jdbcwrapper.WrappingDriver.PREFIX);
    assertThat(wrapped.poolDriverClass())
        .isEqualTo(example.jdbcwrapper.WrappingDriver.class.getName());
  }

  @Test
  @DisplayName("SAMPLE_SECOND_POOL: off by default, parsed like every boolean switch")
  void secondPool() {
    assertThat(
            SampleConfig.from(new HashMap<String, String>(), new Properties(), BUILD).secondPool())
        .isFalse();
    assertThat(config("SAMPLE_SECOND_POOL", "true").secondPool()).isTrue();
    assertThatThrownBy(() -> config("SAMPLE_SECOND_POOL", "two"))
        .hasMessageContaining("SAMPLE_SECOND_POOL");
  }

  @Test
  @DisplayName("Invalid values fail with the variable name")
  void invalidValues() {
    assertThatThrownBy(() -> config("SAMPLE_DB", "db2")).hasMessageContaining("SAMPLE_DB");
    assertThatThrownBy(() -> config("SAMPLE_POOL", "c3p0")).hasMessageContaining("SAMPLE_POOL");
    assertThatThrownBy(() -> config("SAMPLE_POOL_MAX", "0"))
        .hasMessageContaining("SAMPLE_POOL_MAX");
    assertThatThrownBy(() -> config("SAMPLE_VT", "maybe")).hasMessageContaining("SAMPLE_VT");
    assertThatThrownBy(() -> config("SAMPLE_DRIVER_WRAPPER", "maybe"))
        .hasMessageContaining("SAMPLE_DRIVER_WRAPPER");
    assertThatThrownBy(() -> config("SAMPLE_FIXED_CLOCK", "yesterday"))
        .hasMessageContaining("sample.fixed-clock");
  }

  private static SampleConfig config(String key, String value) {
    Map<String, String> env = new HashMap<>();
    env.put(key, value);
    return SampleConfig.from(env, new Properties(), BUILD);
  }
}
