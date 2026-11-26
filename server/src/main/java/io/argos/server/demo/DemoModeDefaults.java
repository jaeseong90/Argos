/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.demo;

import io.argos.server.config.PackagedSettingsPostProcessor;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;

/**
 * The collector defaults of demo mode (17 §3.2, §3.9; WO-41). With {@code argos.demo.enabled=true}
 * ({@code ARGOS_DEMO_MODE=true}, written only by {@code argos-env-init.sh --demo}) the demo agents
 * connect without TLS, so this processor supplies
 *
 * <ul>
 *   <li>{@code argos.collector.tls.enabled=false} and
 *   <li>{@code argos.collector.allow-plaintext=true}
 * </ul>
 *
 * as defaults: a key that any other source sets (configuration file, environment, system property,
 * command line, default properties of embedding code) keeps that value, and the source of these two
 * ranks below every other one except {@code defaultProperties}. Without demo mode nothing is added,
 * so a production installation never accepts plaintext unless an operator sets both keys (the
 * existing WARN of the collector says so). {@link DemoModeNotice} logs the warning of demo mode at
 * every start.
 *
 * <p>Registered in {@code META-INF/spring.factories}; runs after Spring Boot has loaded the
 * configuration files (the packaged {@code application.yml} maps {@code ARGOS_DEMO_MODE}).
 */
public class DemoModeDefaults implements EnvironmentPostProcessor, Ordered {

  /** Name of the property source this processor adds (shown by the effective configuration). */
  public static final String SOURCE_NAME = "demo mode defaults (argos.demo.enabled)";

  /** The demo mode key. */
  public static final String DEMO_KEY = "argos.demo.enabled";

  /** The collector keys and the values demo mode supplies. */
  static final Map<String, Object> DEFAULTS =
      Map.of(
          "argos.collector.tls.enabled", "false",
          "argos.collector.allow-plaintext", "true");

  @Override
  public int getOrder() {
    // After the configuration files (ConfigDataEnvironmentPostProcessor, highest + 10).
    return Ordered.LOWEST_PRECEDENCE - 10;
  }

  @Override
  public void postProcessEnvironment(
      ConfigurableEnvironment environment, SpringApplication application) {
    // The packaged application.yml says argos.demo.enabled: ${ARGOS_DEMO_MODE:false}; until
    // PackagedSettingsPostProcessor drops that unset placeholder, it would hide a value from
    // default properties (embedding code, tests). That processor is idempotent and orders last,
    // so it runs here first.
    PackagedSettingsPostProcessor.apply(environment);
    apply(environment);
  }

  /**
   * Adds the demo defaults that no other source sets when demo mode is enabled.
   *
   * @return the keys added (empty when demo mode is off or every key is set elsewhere)
   */
  public static Map<String, Object> apply(ConfigurableEnvironment environment) {
    if (!enabled(environment)) {
      return Map.of();
    }
    Map<String, Object> supplied = new LinkedHashMap<>();
    DEFAULTS.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .filter(e -> !environment.containsProperty(e.getKey()))
        .forEach(e -> supplied.put(e.getKey(), e.getValue()));
    if (supplied.isEmpty()) {
      return Map.of();
    }
    MutablePropertySources sources = environment.getPropertySources();
    MapPropertySource source = new MapPropertySource(SOURCE_NAME, Map.copyOf(supplied));
    if (sources.contains(SOURCE_NAME)) {
      sources.replace(SOURCE_NAME, source);
    } else {
      sources.addLast(source);
    }
    return Map.copyOf(supplied);
  }

  /** Whether {@code argos.demo.enabled} is true in {@code environment} (placeholders resolved). */
  public static boolean enabled(ConfigurableEnvironment environment) {
    try {
      return Binder.get(environment).bind(DEMO_KEY, Boolean.class).orElse(false);
    } catch (RuntimeException e) {
      // A value that is not a boolean: configuration validation reports it at start.
      return false;
    }
  }
}
