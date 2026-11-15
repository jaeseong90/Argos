/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.config;

import static io.argos.server.config.ConfigTestSupport.applicationYaml;
import static io.argos.server.config.ConfigTestSupport.bind;
import static io.argos.server.config.ConfigTestSupport.environment;
import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.config.ConfigKeyCatalog.ConfigKey;
import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;

/**
 * The packaged {@code application.yml}: its placeholders are exactly the short environment names of
 * {@link EnvironmentAliases} (17 §3.8, ADR-0010 C-96), its defaults equal the record defaults, and
 * the management, error and connector settings of WO-20 are in place.
 */
@Tag("req:O-R03")
class ApplicationYamlTest {

  @BeforeAll
  static void warmUp() {
    ConfigTestSupport.warmUp();
  }

  private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{(ARGOS_[A-Z0-9_]+):");

  private static Map<String, Object> flat() {
    Map<String, Object> all = new TreeMap<>();
    for (PropertySource<?> source : applicationYaml()) {
      EnumerablePropertySource<?> e = (EnumerablePropertySource<?>) source;
      for (String name : e.getPropertyNames()) {
        all.put(name, String.valueOf(e.getProperty(name)));
      }
    }
    return all;
  }

  @Test
  @DisplayName("the placeholders of application.yml are exactly the documented short names")
  void placeholdersMatchAliases() {
    Map<String, String> found = new TreeMap<>();
    flat()
        .forEach(
            (key, value) -> {
              Matcher m = PLACEHOLDER.matcher(String.valueOf(value));
              while (m.find()) {
                found.put(m.group(1), key);
              }
            });
    assertThat(found).isEqualTo(new TreeMap<>(EnvironmentAliases.ALIASES));
    assertThat(EnvironmentAliases.DEPLOYMENT_VARIABLES).doesNotContainAnyElementsOf(found.keySet());
  }

  @Test
  @DisplayName("T-API-009 the placeholder defaults of application.yml equal the record defaults")
  void defaultsAgree() {
    ArgosProperties withYaml = bind(ConfigTestSupport.rawEnvironment(true, Map.of()));
    ArgosProperties without = bind(environment(false, Map.of()));
    for (ConfigKey key : ConfigKeyCatalog.keys()) {
      assertThat(ConfigKeyCatalog.render(key.valueIn(withYaml)))
          .as(key.name())
          .isEqualTo(ConfigKeyCatalog.render(key.valueIn(without)));
    }
  }

  @Test
  @DisplayName("short environment names set their keys; the canonical environment name wins")
  void shortNamesApply() {
    Map<String, Object> env = new TreeMap<>();
    env.put("ARGOS_PG_URL", "jdbc:postgresql://db.internal:5432/argos");
    env.put("ARGOS_CH_WRITER_PASSWORD_FILE", "/run/secrets/ch_writer_password");
    env.put("ARGOS_RETENTION_XLOG_DAYS", "45");
    env.put("ARGOS_RETENTION_RAW_STAT_DAYS", "21");
    env.put("ARGOS_TZ_DISPLAY", "UTC");
    env.put("ARGOS_IP_MODE", "full");
    env.put("ARGOS_CH_URL", "http://ch-short:8123");
    env.put("ARGOS_CLICKHOUSE_URL", "http://ch-canonical:8123");
    ConfigurableEnvironment environment = environment(true, env);
    ArgosProperties p = bind(environment);

    assertThat(p.postgres().url()).isEqualTo("jdbc:postgresql://db.internal:5432/argos");
    assertThat(p.clickhouse().writer().passwordFile()).isEqualTo("/run/secrets/ch_writer_password");
    assertThat(p.retention().xlog()).isEqualTo(Duration.ofDays(45));
    assertThat(p.retention().stat()).isEqualTo(Duration.ofDays(21));
    assertThat(p.server().timezone().getId()).isEqualTo("UTC");
    assertThat(p.privacy().defaultIpMode()).isEqualTo(ArgosProperties.Privacy.IpMode.FULL);
    assertThat(p.clickhouse().url()).isEqualTo("http://ch-canonical:8123");
    // Unset short names leave Spring's defaults: root level INFO, all interfaces.
    assertThat(environment.getProperty("logging.level.root")).isNull();
    assertThat(environment.getProperty("server.address")).isNull();
  }

  @Test
  @DisplayName("ARGOS_LOG_LEVEL maps onto a Spring key")
  void springAliases() {
    ConfigurableEnvironment environment = environment(true, Map.of("ARGOS_LOG_LEVEL", "DEBUG"));
    assertThat(environment.getProperty("logging.level.root")).isEqualTo("DEBUG");
  }

  @Test
  @DisplayName(
      "C-113 ARGOS_HTTP_BIND and ARGOS_GRPC_BIND are Compose publish addresses: the server ignores"
          + " them and listens on every interface")
  void bindVariablesAreNotServerSettings() {
    ConfigurableEnvironment environment =
        environment(true, Map.of("ARGOS_HTTP_BIND", "127.0.0.1", "ARGOS_GRPC_BIND", "127.0.0.1"));
    ArgosProperties p = bind(environment);
    assertThat(environment.getProperty("server.address")).isNull();
    assertThat(p.collector().bind()).isEqualTo("0.0.0.0");
    assertThat(EnvironmentAliases.ALIASES).doesNotContainKeys("ARGOS_HTTP_BIND", "ARGOS_GRPC_BIND");
    // Documented installation variables: never reported as unknown settings.
    assertThat(EnvironmentAliases.isDocumented("ARGOS_HTTP_BIND")).isTrue();
    assertThat(EnvironmentAliases.isDocumented("ARGOS_GRPC_BIND")).isTrue();
  }

  @Test
  @DisplayName(
      "T-SEC-55 management port 8081 on 127.0.0.1, health/info/prometheus only, no details")
  void managementSettings() {
    ConfigurableEnvironment e = environment(true, Map.of());
    assertThat(e.getProperty("management.server.port")).isEqualTo("8081");
    assertThat(e.getProperty("management.server.address")).isEqualTo("127.0.0.1");
    assertThat(e.getProperty("management.endpoints.web.exposure.include"))
        .isEqualTo("health,info,prometheus");
    assertThat(e.getProperty("management.endpoints.access.default")).isEqualTo("none");
    assertThat(e.getProperty("management.endpoint.health.show-details")).isEqualTo("never");
    assertThat(e.getProperty("management.endpoint.health.show-components")).isEqualTo("never");
    assertThat(e.getProperty("management.endpoint.health.group.readiness.include"))
        .isEqualTo("readinessState,migrations,db,clickhouse,ingest");
    assertThat(e.getProperty("management.endpoint.health.group.liveness.include"))
        .isEqualTo("livenessState");
    assertThat(e.getProperty("management.metrics.tags.application")).isEqualTo("argos-server");
  }

  @Test
  @DisplayName("connector limits, error details off, virtual threads, graceful shutdown (14 §7.2)")
  void webSettings() {
    ConfigurableEnvironment e = environment(true, Map.of());
    assertThat(e.getProperty("server.port")).isEqualTo("8080");
    assertThat(e.getProperty("server.max-http-request-header-size")).isEqualTo("16KB");
    assertThat(e.getProperty("server.tomcat.max-http-form-post-size")).isEqualTo("1MB");
    assertThat(e.getProperty("server.tomcat.max-swallow-size")).isEqualTo("1MB");
    for (String key :
        new String[] {
          "server.error.include-message",
          "server.error.include-stacktrace",
          "server.error.include-binding-errors",
          "server.error.include-path"
        }) {
      assertThat(e.getProperty(key)).as(key).isEqualTo("never");
    }
    assertThat(e.getProperty("server.error.include-exception")).isEqualTo("false");
    assertThat(e.getProperty("spring.threads.virtual.enabled")).isEqualTo("true");
    assertThat(e.getProperty("server.shutdown")).isEqualTo("graceful");
  }

  @Test
  @DisplayName("default properties (embedding, tests) override the packaged application.yml")
  void defaultPropertiesOverridePackagedFile() {
    MapPropertySource defaults =
        new MapPropertySource(
            "defaultProperties",
            Map.of(
                "argos.postgres.url", "jdbc:postgresql://embedded:5432/argos",
                "server.port", "0",
                "management.server.port", "0",
                "management.server.address", "127.0.0.1"));
    ConfigurableEnvironment e =
        ConfigTestSupport.rawEnvironment(true, Map.of("ARGOS_CH_URL", "http://short:8123"));
    e.getPropertySources().addLast(defaults);
    PackagedSettingsPostProcessor.apply(e);

    assertThat(bind(e).postgres().url()).isEqualTo("jdbc:postgresql://embedded:5432/argos");
    assertThat(e.getProperty("server.port")).isEqualTo("0");
    assertThat(e.getProperty("management.server.port")).isEqualTo("0");
    // Packaged values that the defaults do not set stay, and so does a set short name.
    assertThat(e.getProperty("management.endpoint.health.show-details")).isEqualTo("never");
    assertThat(bind(e).clickhouse().url()).isEqualTo("http://short:8123");
  }

  @Test
  @DisplayName("an operator file overrides a packaged default")
  void operatorFileWins() {
    ConfigurableEnvironment e =
        environment(
            true,
            Map.of("ARGOS_RETENTION_XLOG_DAYS", "45"),
            new MapPropertySource("operator", Map.of("argos.retention.xlog", "60d")));
    assertThat(bind(e).retention().xlog()).isEqualTo(Duration.ofDays(60));
  }
}
