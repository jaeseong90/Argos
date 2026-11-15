/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.config.ArgosProperties;
import io.argos.server.config.PackagedSettingsPostProcessor;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * The environment names of the documented setup (16 §7.1, §7.8, 17 §3.8, §6.4): they reach the keys
 * that the code reads, and {@code ARGOS_IP_MODE} counts as an explicit choice only when it is set
 * (the unattended setup depends on that, SEC-31).
 */
@Tag("req:SEC-47")
class AuthEnvironmentNamesTest {

  private static StandardEnvironment environment(Map<String, Object> variables) throws IOException {
    StandardEnvironment environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .replace(
            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
            new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables));
    List<PropertySource<?>> packaged =
        new YamlPropertySourceLoader()
            .load(
                "Config resource 'class path resource [application.yml]' via location"
                    + " 'optional:classpath:/'",
                new ClassPathResource("application.yml"));
    packaged.forEach(environment.getPropertySources()::addLast);
    PackagedSettingsPostProcessor.apply(environment);
    ConfigurationPropertySources.attach(environment);
    return environment;
  }

  @Test
  @DisplayName(
      "T-SEC-47a ARGOS_ADMIN_USERNAME, ARGOS_ADMIN_PASSWORD_FILE, ARGOS_JWT_KEY and"
          + " ARGOS_JWT_KEY_FILE bind to their keys")
  void variablesBind() throws IOException {
    StandardEnvironment environment =
        environment(
            Map.of(
                "ARGOS_ADMIN_USERNAME", "ops",
                "ARGOS_ADMIN_PASSWORD_FILE", "/run/secrets/admin_password",
                "ARGOS_JWT_KEY", "value-of-key",
                "ARGOS_JWT_KEY_FILE", "/data/jwt.key",
                "ARGOS_AUTH_SETUP_TOKEN_FILE", "/data/setup-token",
                "ARGOS_SECURITY_COOKIE_SECURE", "false",
                "ARGOS_WEB_PUBLIC_URL", "https://argos.example.test"));
    ArgosProperties p = Binder.get(environment).bindOrCreate("argos", ArgosProperties.class);
    assertThat(p.adminUsername()).isEqualTo("ops");
    assertThat(p.adminPasswordFile()).isEqualTo("/run/secrets/admin_password");
    assertThat(p.jwtKey()).isEqualTo("value-of-key");
    assertThat(p.jwtKeyFile()).isEqualTo("/data/jwt.key");
    assertThat(p.auth().setupTokenFile()).isEqualTo("/data/setup-token");
    assertThat(p.security().cookie().secure()).isFalse();
    assertThat(p.toString()).doesNotContain("value-of-key");
  }

  @Test
  @DisplayName("T-SEC-47a the IP mode is explicit only when ARGOS_IP_MODE or the key is set")
  void explicitIpMode() throws IOException {
    String key = "argos.privacy.default-ip-mode";
    assertThat(environment(Map.of()).containsProperty(key)).isFalse();
    StandardEnvironment set = environment(Map.of("ARGOS_IP_MODE", "off"));
    assertThat(set.containsProperty(key)).isTrue();
    assertThat(set.getProperty(key)).isEqualTo("off");
    assertThat(environment(Map.of("ARGOS_PRIVACY_DEFAULT_IP_MODE", "full")).containsProperty(key))
        .isTrue();
  }
}
