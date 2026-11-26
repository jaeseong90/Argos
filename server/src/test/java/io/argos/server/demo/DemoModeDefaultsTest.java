/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.demo;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.config.ArgosProperties;
import io.argos.server.config.EnvironmentAliases;
import io.argos.server.config.PackagedSettingsPostProcessor;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.UrlResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

/**
 * Demo mode configuration (WO-41; 17 §3.2, §3.8): {@code ARGOS_DEMO_MODE} and {@code
 * ARGOS_BOOTSTRAP_APPS_FILE} map onto {@code argos.demo.enabled} and {@code
 * argos.bootstrap.apps-file}; only demo mode supplies the plaintext gRPC defaults, and an explicit
 * setting of a collector key wins over them.
 */
@Tag("req:N-16")
@Tag("req:O-A01")
class DemoModeDefaultsTest {

  @BeforeAll
  static void warmUp() {
    bind(environment(Map.of(), Map.of()));
  }

  /**
   * An environment as the server builds it, without the real system environment: the packaged
   * {@code application.yml}, the given variables, default properties, then this processor as Spring
   * Boot calls it (before {@link PackagedSettingsPostProcessor}, which orders last), then that one.
   */
  private static ConfigurableEnvironment environment(
      Map<String, Object> variables, Map<String, Object> defaultProperties) {
    StandardEnvironment environment = new StandardEnvironment();
    var sources = environment.getPropertySources();
    sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
    sources.replace(
        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
        new SystemEnvironmentPropertySource(
            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables));
    try {
      new YamlPropertySourceLoader()
          .load(
              "Config resource 'class path resource [application.yml]' via location"
                  + " 'optional:classpath:/'",
              new ClassPathResource("application.yml"))
          .forEach(sources::addLast);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    if (!defaultProperties.isEmpty()) {
      sources.addLast(new MapPropertySource("defaultProperties", defaultProperties));
    }
    ConfigurationPropertySources.attach(environment);
    new DemoModeDefaults().postProcessEnvironment(environment, null);
    PackagedSettingsPostProcessor.apply(environment);
    return environment;
  }

  private static ArgosProperties bind(ConfigurableEnvironment environment) {
    return Binder.get(environment).bindOrCreate("argos", ArgosProperties.class);
  }

  @Test
  @DisplayName(
      "T-NFR-16d demo mode off (default, or ARGOS_DEMO_MODE=false): no demo source, TLS required"
          + " and plaintext refused as before, no bootstrap file")
  void offChangesNothing() {
    for (Map<String, Object> variables :
        java.util.List.<Map<String, Object>>of(Map.of(), Map.of("ARGOS_DEMO_MODE", "false"))) {
      ConfigurableEnvironment environment = environment(variables, Map.of());
      ArgosProperties p = bind(environment);
      assertThat(p.demo().enabled()).isFalse();
      assertThat(p.collector().tls().enabled()).isTrue();
      assertThat(p.collector().allowPlaintext()).isFalse();
      assertThat(p.bootstrap().appsFile()).isNull();
      assertThat(environment.getPropertySources().contains(DemoModeDefaults.SOURCE_NAME)).isFalse();
    }
  }

  @Test
  @DisplayName(
      "T-NFR-16a ARGOS_DEMO_MODE=true sets argos.demo.enabled and supplies"
          + " collector.tls.enabled=false and allow-plaintext=true; ARGOS_BOOTSTRAP_APPS_FILE sets"
          + " argos.bootstrap.apps-file")
  void onSuppliesPlaintextDefaults() {
    Map<String, Object> variables = new LinkedHashMap<>();
    variables.put("ARGOS_DEMO_MODE", "true");
    variables.put("ARGOS_BOOTSTRAP_APPS_FILE", "/conf/demo/bootstrap-apps.yaml");
    ConfigurableEnvironment environment = environment(variables, Map.of());
    ArgosProperties p = bind(environment);
    assertThat(p.demo().enabled()).isTrue();
    assertThat(p.collector().tls().enabled()).isFalse();
    assertThat(p.collector().allowPlaintext()).isTrue();
    assertThat(p.bootstrap().appsFile()).isEqualTo("/conf/demo/bootstrap-apps.yaml");
    assertThat(environment.getPropertySources().contains(DemoModeDefaults.SOURCE_NAME)).isTrue();
  }

  @Test
  @DisplayName(
      "T-NFR-16a an explicit collector setting wins over the demo defaults (environment and default"
          + " properties)")
  void explicitSettingWins() {
    Map<String, Object> variables = new LinkedHashMap<>();
    variables.put("ARGOS_DEMO_MODE", "true");
    variables.put("ARGOS_COLLECTOR_TLS_ENABLED", "true");
    ArgosProperties p = bind(environment(variables, Map.of()));
    assertThat(p.collector().tls().enabled()).isTrue();
    assertThat(p.collector().allowPlaintext()).isTrue();

    ArgosProperties q =
        bind(
            environment(
                Map.of("ARGOS_DEMO_MODE", "true"),
                Map.of("argos.collector.allow-plaintext", "false")));
    assertThat(q.collector().tls().enabled()).isFalse();
    assertThat(q.collector().allowPlaintext()).isFalse();
  }

  @Test
  @DisplayName(
      "T-API-009 ARGOS_DEMO_MODE and ARGOS_BOOTSTRAP_APPS_FILE are short names of their keys, no"
          + " longer installation-only variables")
  void aliases() {
    assertThat(EnvironmentAliases.ALIASES)
        .containsEntry("ARGOS_DEMO_MODE", "argos.demo.enabled")
        .containsEntry("ARGOS_BOOTSTRAP_APPS_FILE", "argos.bootstrap.apps-file");
    assertThat(EnvironmentAliases.DEPLOYMENT_VARIABLES)
        .doesNotContain("ARGOS_DEMO_MODE", "ARGOS_BOOTSTRAP_APPS_FILE");
    // The canonical relaxed name works as well.
    assertThat(bind(environment(Map.of("ARGOS_DEMO_ENABLED", "true"), Map.of())).demo().enabled())
        .isTrue();
  }

  @Test
  @DisplayName(
      "T-NFR-16a the start warning names demo mode and the plaintext gRPC settings in force")
  void warning() {
    assertThat(DemoModeNotice.message(false, true))
        .startsWith("DEMO MODE (argos.demo.enabled=true): plaintext gRPC is enabled")
        .contains("argos.collector.tls.enabled=false, argos.collector.allow-plaintext=true")
        .contains("demo banner");
    assertThat(DemoModeNotice.message(true, false))
        .startsWith("DEMO MODE (argos.demo.enabled=true): plaintext gRPC defaults are overridden")
        .contains("argos.collector.tls.enabled=true, argos.collector.allow-plaintext=false");
  }

  @Test
  @DisplayName(
      "T-NFR-16a the processor is registered in META-INF/spring.factories after Spring Boot's own"
          + " and sees demo mode from default properties (embedding code, tests) although the"
          + " packaged application.yml maps ARGOS_DEMO_MODE")
  void registeredProcessor() throws IOException {
    Properties factories = null;
    var resources =
        DemoModeDefaults.class.getClassLoader().getResources("META-INF/spring.factories");
    while (resources.hasMoreElements()) {
      Properties p = PropertiesLoaderUtils.loadProperties(new UrlResource(resources.nextElement()));
      String processors = p.getProperty("org.springframework.boot.EnvironmentPostProcessor", "");
      if (processors.contains(PackagedSettingsPostProcessor.class.getName())) {
        factories = p;
      }
    }
    assertThat(factories).as("the server's spring.factories").isNotNull();
    assertThat(factories.getProperty("org.springframework.boot.EnvironmentPostProcessor"))
        .contains(DemoModeDefaults.class.getName());
    assertThat(new DemoModeDefaults().getOrder())
        .isGreaterThan(Ordered.HIGHEST_PRECEDENCE + 10)
        .isLessThan(new PackagedSettingsPostProcessor().getOrder());

    ArgosProperties p = bind(environment(Map.of(), Map.of("argos.demo.enabled", "true")));
    assertThat(p.demo().enabled()).isTrue();
    assertThat(p.collector().tls().enabled()).isFalse();
    assertThat(p.collector().allowPlaintext()).isTrue();
  }
}
