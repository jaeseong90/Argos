/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.util.unit.DataSize;

/**
 * Binding of the storage sections of {@link ArgosProperties} (12 §10, ADR-0010 C-96, C-97):
 * defaults, the kebab-case keys of the design and password masking. The full key snapshot
 * (T-API-009) is a contract test.
 */
@Tag("req:N-22")
class ArgosPropertiesTest {

  private static ArgosProperties bind(Map<String, String> values) {
    return new Binder(new MapConfigurationPropertySource(values))
        .bindOrCreate("argos", ArgosProperties.class);
  }

  @Test
  @DisplayName("storage defaults: 12 §10 values, C-11 account names")
  void storageDefaults() {
    ArgosProperties p = bind(Map.of());

    assertThat(p.postgres().url()).isNull();
    assertThat(p.postgres().app().user()).isEqualTo("argos_app");
    assertThat(p.postgres().owner().user()).isEqualTo("argos");
    assertThat(p.clickhouse().url()).isEqualTo("http://clickhouse:8123");
    assertThat(p.clickhouse().database()).isEqualTo("argos");
    assertThat(p.clickhouse().tablePrefix()).isEmpty();
    assertThat(p.clickhouse().timezone()).isEqualTo(ZoneId.of("UTC"));
    assertThat(p.clickhouse().writer().user()).isEqualTo("argos_writer");
    assertThat(p.clickhouse().reader().user()).isEqualTo("argos_reader");
    assertThat(p.clickhouse().migrator().user()).isEqualTo("argos_migrator");
    assertThat(p.retention().xlog()).isEqualTo(Duration.ofDays(30));
    assertThat(p.retention().profile()).isEqualTo(Duration.ofDays(7));
    assertThat(p.retention().stat()).isEqualTo(Duration.ofDays(14));
    assertThat(p.retention().metric()).isEqualTo(Duration.ofDays(14));
    assertThat(p.retention().rollup1m()).isEqualTo(Duration.ofDays(90));
    assertThat(p.retention().rollup1h()).isEqualTo(Duration.ofDays(365));
    assertThat(p.retention().commandResults()).isEqualTo(Duration.ofDays(30));
    assertThat(p.retention().audit()).isEqualTo(Duration.ofDays(365));
    assertThat(p.retention().alertEvents()).isEqualTo(Duration.ofDays(365));
  }

  @Test
  @DisplayName("the kebab-case keys of 12 §10 and C-96 bind (password-file, rollup-1m, owner.*)")
  void keysBind() {
    ArgosProperties p =
        bind(
            Map.ofEntries(
                Map.entry("argos.postgres.url", "jdbc:postgresql://db:5432/argos"),
                Map.entry("argos.postgres.user", "app"),
                Map.entry("argos.postgres.password-file", "/run/secrets/pg_app"),
                Map.entry("argos.postgres.owner.user", "owner"),
                Map.entry("argos.postgres.owner.password-file", "/run/secrets/pg_owner"),
                Map.entry("argos.clickhouse.url", "http://127.0.0.1:18123"),
                Map.entry("argos.clickhouse.database", "argos_test"),
                Map.entry("argos.clickhouse.table-prefix", "t1_"),
                Map.entry("argos.clickhouse.writer.password-file", "/run/secrets/ch_writer"),
                Map.entry("argos.clickhouse.reader.user", "reader2"),
                Map.entry("argos.clickhouse.migrator.password-file", "/run/secrets/ch_migrator"),
                Map.entry("argos.retention.xlog", "45d"),
                Map.entry("argos.retention.rollup-1m", "120d"),
                Map.entry("argos.retention.rollup-1h", "400d"),
                Map.entry("argos.collector.quota.metrics-per-5s", "500"),
                Map.entry("argos.wal.max-bytes", "20GB"),
                Map.entry("argos.alert.webhook.allow-cidrs[0]", "10.0.0.0/8"),
                Map.entry("argos.agent.auto-register.prod", "false")));

    assertThat(p.postgres().url()).isEqualTo("jdbc:postgresql://db:5432/argos");
    assertThat(p.postgres().app().user()).isEqualTo("app");
    assertThat(p.postgres().app().passwordFile()).isEqualTo("/run/secrets/pg_app");
    assertThat(p.postgres().owner().user()).isEqualTo("owner");
    assertThat(p.postgres().owner().passwordFile()).isEqualTo("/run/secrets/pg_owner");
    assertThat(p.clickhouse().database()).isEqualTo("argos_test");
    assertThat(p.clickhouse().tablePrefix()).isEqualTo("t1_");
    assertThat(p.clickhouse().writer().user()).isEqualTo("argos_writer");
    assertThat(p.clickhouse().writer().passwordFile()).isEqualTo("/run/secrets/ch_writer");
    assertThat(p.clickhouse().reader().user()).isEqualTo("reader2");
    assertThat(p.clickhouse().migrator().passwordFile()).isEqualTo("/run/secrets/ch_migrator");
    assertThat(p.retention().xlog()).isEqualTo(Duration.ofDays(45));
    assertThat(p.retention().rollup1m()).isEqualTo(Duration.ofDays(120));
    assertThat(p.retention().rollup1h()).isEqualTo(Duration.ofDays(400));
    assertThat(p.collector().quota().metricsPer5s()).isEqualTo(500);
    assertThat(p.wal().maxBytes()).isEqualTo(DataSize.ofGigabytes(20));
    assertThat(p.alert().webhook().allowCidrs()).isEqualTo(List.of("10.0.0.0/8"));
    assertThat(p.agent().autoRegister()).isEqualTo(Map.of("prod", false));
  }

  @Test
  @DisplayName("toString never shows a password given as a plain value")
  void passwordMasked() {
    String sentinel = "sentinel-" + Long.toHexString(System.nanoTime());
    ArgosProperties p =
        bind(
            Map.of(
                "argos.postgres.password", sentinel,
                "argos.postgres.owner.password", sentinel,
                "argos.clickhouse.writer.password", sentinel,
                "argos.alert.smtp.password", sentinel,
                "argos.metrics.token", sentinel,
                "argos.master-key", sentinel));

    assertThat(p.postgres().app().password()).isEqualTo(sentinel);
    assertThat(p.alert().smtp().password()).isEqualTo(sentinel);
    assertThat(p.metrics().token()).isEqualTo(sentinel);
    assertThat(p.masterKey()).isEqualTo(sentinel);
    assertThat(p.toString()).doesNotContain(sentinel).contains("password=****");
    assertThat(p.clickhouse().toString()).doesNotContain(sentinel);
  }

  @Test
  @DisplayName("keys added by WO-20 (C-96): limits, SMTP, bind, metrics token, testing hooks")
  void addedKeys() {
    ArgosProperties p = bind(Map.of());

    assertThat(p.collector().bind()).isEqualTo("0.0.0.0");
    assertThat(p.collector().limits().maxTransactionsPerBatch()).isEqualTo(5000);
    assertThat(p.collector().limits().maxStepsPerBatch()).isEqualTo(50_000);
    assertThat(p.collector().limits().maxDetailBytes()).isEqualTo(4096);
    assertThat(p.collector().limits().maxStringBytes()).isEqualTo(2048);
    assertThat(p.collector().limits().maxErrorFrames()).isEqualTo(30);
    assertThat(p.collector().limits().maxStackFrames()).isEqualTo(64);
    assertThat(p.collector().limits().maxActiveItems()).isEqualTo(500);
    assertThat(p.collector().limits().maxSeriesPerInstance()).isEqualTo(500);
    assertThat(p.collector().limits().maxDictionaryEntriesPerHour()).isEqualTo(10_000);
    assertThat(p.alert().smtp().port()).isEqualTo(587);
    assertThat(p.alert().smtp().starttls()).isEqualTo(ArgosProperties.Alert.Smtp.StartTls.REQUIRED);
    assertThat(p.alert().smtp().checkServerIdentity()).isTrue();
    assertThat(p.alert().smtp().insecure()).isFalse();
    assertThat(p.alert().smtp().allowedRecipientDomains()).isEmpty();
    assertThat(p.metrics().token()).isNull();
    assertThat(p.metrics().tokenFile()).isNull();
    assertThat(p.masterKey()).isNull();
    assertThat(p.testing().anySet()).isFalse();
    assertThat(p.testing().holdTimeout()).isEqualTo(Duration.ofMinutes(5));
    assertThat(p.web().publicUrl()).isNull();
  }

  @Test
  @DisplayName("other 12 §10 sections bind with their defaults")
  void otherDefaults() {
    ArgosProperties p = bind(Map.of());

    assertThat(p.collector().port()).isEqualTo(9100);
    assertThat(p.collector().tls().enabled()).isTrue();
    assertThat(p.collector().allowPlaintext()).isFalse();
    assertThat(p.wal().maxBytes()).isEqualTo(DataSize.ofGigabytes(10));
    assertThat(p.writer().flushRows()).isEqualTo(50_000);
    assertThat(p.masterKeyFile()).isEqualTo("conf/master.key");
    assertThat(p.auth().password().minLength()).isEqualTo(12);
    assertThat(p.agent().autoRegister()).isEqualTo(Map.of("default", true));
    assertThat(p.privacy().defaultIpMode()).isEqualTo(ArgosProperties.Privacy.IpMode.MASKED);
    assertThat(p.audit().sinks()).containsExactly("postgres");
    assertThat(p.metrics().allowCidrs()).isEmpty();
    assertThat(p.server().timezone()).isEqualTo(ZoneId.of("UTC"));
  }
}
