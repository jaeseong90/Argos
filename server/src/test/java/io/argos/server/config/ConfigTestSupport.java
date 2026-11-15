/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

/** Helpers of the configuration unit tests: environments, generated secrets, bound properties. */
final class ConfigTestSupport {

  private static final SecureRandom RANDOM = new SecureRandom();

  private ConfigTestSupport() {}

  /**
   * Loads what the first binding of a JVM loads (binder, YAML parser, key catalog, validator), so
   * that this one-time cost is not counted as the duration of a test (18 §2.2 slow-test warning).
   * Called from {@code @BeforeAll}.
   */
  static void warmUp() {
    bind(environment(true, Map.of()));
    ConfigKeyCatalog.keys();
    ConfigValidation.defaultValidator();
  }

  /** A random password of 32 bytes (base64url, 43 characters); never a literal in a test. */
  static String newSecret() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /** Writes {@code value} plus a line break to {@code dir/name} and returns the path. */
  static Path secretFile(Path dir, String name, String value) {
    try {
      Path file = dir.resolve(name);
      Files.writeString(file, value + "\n", StandardCharsets.UTF_8);
      return file;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * The minimum settings of a valid configuration with good passwords from files in {@code dir}.
   */
  static Map<String, Object> validSettings(Path dir) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("argos.web.public-url", "https://argos.example.test");
    m.put("argos.postgres.url", "jdbc:postgresql://127.0.0.1:5432/argos");
    for (String prefix :
        List.of(
            "argos.postgres",
            "argos.postgres.owner",
            "argos.clickhouse.writer",
            "argos.clickhouse.reader",
            "argos.clickhouse.migrator")) {
      m.put(
          prefix + ".password-file",
          secretFile(dir, prefix.replace('.', '-') + ".pw", newSecret()).toString());
    }
    return m;
  }

  /**
   * An environment as the server builds it, without the real system environment and system
   * properties: the packaged {@code application.yml} (when {@code withApplicationYaml}; unset short
   * names dropped like {@link PackagedSettingsPostProcessor} does), then the given layers, each
   * with a higher precedence than the one before.
   */
  static ConfigurableEnvironment environment(
      boolean withApplicationYaml, Map<String, Object> env, PropertySource<?>... higher) {
    ConfigurableEnvironment environment = rawEnvironment(withApplicationYaml, env, higher);
    PackagedSettingsPostProcessor.apply(environment);
    return environment;
  }

  /** {@link #environment} without dropping the unset short names of {@code application.yml}. */
  static ConfigurableEnvironment rawEnvironment(
      boolean withApplicationYaml, Map<String, Object> env, PropertySource<?>... higher) {
    StandardEnvironment environment = new StandardEnvironment();
    var sources = environment.getPropertySources();
    sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
    sources.replace(
        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
        new SystemEnvironmentPropertySource(
            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, env));
    if (withApplicationYaml) {
      applicationYaml().forEach(sources::addLast);
    }
    for (PropertySource<?> source : higher) {
      sources.addFirst(source);
    }
    ConfigurationPropertySources.attach(environment);
    return environment;
  }

  /** The packaged {@code application.yml}, named as Spring Boot names it. */
  static List<PropertySource<?>> applicationYaml() {
    try {
      return new YamlPropertySourceLoader()
          .load(
              "Config resource 'class path resource [application.yml]' via location"
                  + " 'optional:classpath:/'",
              new ClassPathResource("application.yml"));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** A configuration file layer named like an operator file of Spring Boot. */
  static MapPropertySource file(Map<String, Object> values) {
    return new MapPropertySource(
        "Config resource 'file [conf/application.yml]' via location 'optional:file:./conf/'",
        values);
  }

  /** Binds {@link ArgosProperties} from {@code environment}, placeholders resolved. */
  static ArgosProperties bind(ConfigurableEnvironment environment) {
    return Binder.get(environment).bindOrCreate("argos", ArgosProperties.class);
  }
}
