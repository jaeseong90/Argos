/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.config;

import static io.argos.server.config.ConfigTestSupport.bind;
import static io.argos.server.config.ConfigTestSupport.environment;
import static io.argos.server.config.ConfigTestSupport.file;
import static io.argos.server.config.ConfigTestSupport.newSecret;
import static io.argos.server.config.ConfigTestSupport.validSettings;
import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.config.EffectiveServerConfig.Entry;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.core.env.StandardEnvironment;

/** Effective configuration: values, masking and sources (17 §6.1, 02 §7). */
@Tag("req:O-R03")
class EffectiveServerConfigTest {

  @BeforeAll
  static void warmUp() {
    ConfigTestSupport.warmUp();
  }

  @TempDir Path dir;

  private static Map<String, Entry> entries(ConfigurableEnvironment e) {
    return new EffectiveServerConfig(e, bind(e))
        .entries().stream().collect(Collectors.toMap(Entry::key, Function.identity()));
  }

  @Test
  @DisplayName("every key appears once; without any setting every source is default")
  void defaults() {
    Map<String, Entry> entries = entries(environment(true, Map.of()));
    assertThat(entries).hasSameSizeAs(ConfigKeyCatalog.keys());
    assertThat(entries.values()).allMatch(Entry::isDefault);
    assertThat(entries.get("argos.retention.xlog").value()).isEqualTo("30d");
    assertThat(entries.get("argos.wal.max-bytes").value()).isEqualTo("10GB");
    assertThat(entries.get("argos.metrics.token").value()).isEqualTo("(none)");
  }

  @Test
  @DisplayName("sources: file, environment (relaxed and short), system property, command line")
  void sources() {
    Map<String, Object> settings = validSettings(dir);
    settings.put("argos.metrics.allow-cidrs[0]", "127.0.0.1/32");
    Map<String, Object> env =
        Map.of("ARGOS_PG_URL", "jdbc:postgresql://db:5432/argos", "ARGOS_WAL_DIR", "/data/wal");
    ConfigurableEnvironment e =
        environment(
            true,
            env,
            file(settings),
            new MapPropertySource(
                StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
                Map.of("argos.writer.flush-rows", "1000")),
            new SimpleCommandLinePropertySource("--argos.collector.port=9200"));
    Map<String, Entry> entries = entries(e);

    assertThat(entries.get("argos.web.public-url").source())
        .isEqualTo("file [conf/application.yml]");
    assertThat(entries.get("argos.metrics.allow-cidrs").source())
        .isEqualTo("file [conf/application.yml]");
    assertThat(entries.get("argos.metrics.allow-cidrs").value()).isEqualTo("[127.0.0.1/32]");
    assertThat(entries.get("argos.wal.dir").source()).isEqualTo("environment ARGOS_WAL_DIR");
    assertThat(entries.get("argos.writer.flush-rows").source()).isEqualTo("system property");
    assertThat(entries.get("argos.collector.port").source()).isEqualTo("command line");
    assertThat(entries.get("argos.collector.port").value()).isEqualTo("9200");
    // The URL is set in the file and by the short name; the file wins over the short name.
    assertThat(entries.get("argos.postgres.url").source()).isEqualTo("file [conf/application.yml]");
    assertThat(entries.get("argos.retention.xlog").isDefault()).isTrue();
  }

  @Test
  @DisplayName("a short environment name is reported as the source of its key")
  void shortNameSource() {
    ConfigurableEnvironment e =
        environment(true, Map.of("ARGOS_RETENTION_XLOG_DAYS", "45", "ARGOS_TZ_DISPLAY", "UTC"));
    Map<String, Entry> entries = entries(e);
    assertThat(entries.get("argos.retention.xlog").source())
        .isEqualTo("environment ARGOS_RETENTION_XLOG_DAYS");
    assertThat(entries.get("argos.retention.xlog").value()).isEqualTo("45d");
    assertThat(entries.get("argos.server.timezone").source())
        .isEqualTo("environment ARGOS_TZ_DISPLAY");
    assertThat(entries.get("argos.retention.profile").isDefault()).isTrue();
  }

  @Test
  @DisplayName("secrets are shown as ****, credentials inside URLs are masked")
  void masking() {
    String secret = newSecret();
    Map<String, Object> settings = validSettings(dir);
    settings.put("argos.postgres.owner.password", secret);
    settings.put("argos.metrics.token", secret);
    settings.put("argos.postgres.url", "jdbc:postgresql://db:5432/argos?password=" + secret);
    settings.put("argos.clickhouse.url", "http://user:" + secret + "@ch:8123");
    Map<String, Entry> entries = entries(environment(true, Map.of(), file(settings)));

    assertThat(entries.get("argos.postgres.owner.password").value()).isEqualTo("****");
    assertThat(entries.get("argos.metrics.token").value()).isEqualTo("****");
    assertThat(entries.get("argos.postgres.url").value())
        .isEqualTo("jdbc:postgresql://db:5432/argos?password=****");
    assertThat(entries.get("argos.clickhouse.url").value()).isEqualTo("http://user:****@ch:8123");
    assertThat(entries.get("argos.postgres.password-file").value()).endsWith(".pw");
    assertThat(entries.values()).noneMatch(en -> en.value().contains(secret));
  }
}
