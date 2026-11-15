/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.config;

import io.argos.server.bootstrap.PhaseOrder;
import io.argos.server.bootstrap.StartupPhase;
import io.argos.server.config.ConfigKeyCatalog.ConfigKey;
import io.argos.server.config.ConfigKeyCatalog.Kind;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.BindHandler;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.UnboundConfigurationPropertiesException;
import org.springframework.boot.context.properties.bind.handler.NoUnboundElementsBindHandler;
import org.springframework.boot.context.properties.source.ConfigurationProperty;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.origin.Origin;
import org.springframework.boot.origin.PropertySourceOrigin;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.stereotype.Component;

/**
 * Start step {@code config-warnings} ({@code CONFIG}, first): warnings that never stop the start.
 *
 * <ul>
 *   <li>Unknown {@code argos.*} keys (O-R03, 17 §6.1): one WARN per key, naming the key and where
 *       it was set. Configuration files, system properties and command line arguments are checked
 *       with Spring's binder; {@code ARGOS_*} environment variables are compared with the relaxed
 *       names of every known key, the short names of 17 §3.8 and the installation variables ({@link
 *       EnvironmentAliases}).
 *   <li>Secrets given as plain values outside the environment (17 §6.1): one WARN per key, naming
 *       the {@code *-file} key to use instead. A value from the environment ({@code
 *       ARGOS_METRICS_TOKEN}, {@code ARGOS_MASTER_KEY}, ...) is accepted silently.
 *   <li>{@code argos.alert.smtp.insecure=true} (SEC-54).
 *   <li>{@code argos.testing.*} set in a release build: ignored, said once ({@link TestingHooks}).
 * </ul>
 */
@Component
@Order(ConfigValidation.ORDER - 100)
public class ConfigKeyWarnings implements StartupPhase {

  private static final Logger LOG = LoggerFactory.getLogger(ConfigKeyWarnings.class);

  private final ConfigurableEnvironment environment;
  private final ArgosProperties properties;
  private final EffectiveServerConfig effective;
  private final TestingHooks testingHooks;

  @Autowired
  public ConfigKeyWarnings(
      ConfigurableEnvironment environment,
      ArgosProperties properties,
      EffectiveServerConfig effective,
      TestingHooks testingHooks) {
    this.environment = environment;
    this.properties = properties;
    this.effective = effective;
    this.testingHooks = testingHooks;
  }

  /** Without the test hook gate (unit tests of the key checks). */
  ConfigKeyWarnings(
      ConfigurableEnvironment environment,
      ArgosProperties properties,
      EffectiveServerConfig effective) {
    this(environment, properties, effective, null);
  }

  @Override
  public PhaseOrder order() {
    return PhaseOrder.CONFIG;
  }

  @Override
  public String name() {
    return "config-warnings";
  }

  @Override
  public void run() {
    for (String unknown : unknownKeys()) {
      LOG.warn("Unknown setting {} is ignored (O-R03)", unknown);
    }
    for (String warning : plaintextSecrets()) {
      LOG.warn(warning);
    }
    if (properties.alert().smtp().insecure()) {
      LOG.warn(
          "argos.alert.smtp.insecure=true: e-mail may be sent without TLS and without"
              + " certificate verification (SEC-54)");
    }
    if (testingHooks != null) {
      // A release build says once that it ignores argos.testing.* (18 §15).
      testingHooks.active();
    }
  }

  /**
   * Every unknown {@code argos.*} setting as {@code <key> (<source>)} or {@code <VARIABLE>
   * (environment)}, sorted.
   */
  public List<String> unknownKeys() {
    Set<String> unknown = new TreeSet<>();
    for (ConfigurationProperty property : unboundOutsideEnvironment()) {
      unknown.add(describe(property));
    }
    for (String variable : unknownEnvironmentVariables()) {
      unknown.add(variable + " (environment)");
    }
    return List.copyOf(unknown);
  }

  private Set<ConfigurationProperty> unboundOutsideEnvironment() {
    BindHandler handler =
        new NoUnboundElementsBindHandler(
            BindHandler.DEFAULT, source -> !isSystemEnvironment(source));
    try {
      Binder.get(environment)
          .bind(ConfigKeyCatalog.PREFIX, Bindable.of(ArgosProperties.class), handler);
      return Set.of();
    } catch (BindException e) {
      if (e.getCause() instanceof UnboundConfigurationPropertiesException unbound) {
        return unbound.getUnboundProperties();
      }
      // Other binding failures stop the start when ArgosProperties itself is bound.
      return Set.of();
    }
  }

  private static boolean isSystemEnvironment(ConfigurationPropertySource source) {
    return source.getUnderlyingSource() instanceof PropertySource<?> ps
        && StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME.equals(ps.getName());
  }

  /** {@code ARGOS_*} variables that match no key, alias or installation variable. */
  List<String> unknownEnvironmentVariables() {
    PropertySource<?> env =
        environment
            .getPropertySources()
            .get(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
    if (env == null || !(env.getSource() instanceof Map<?, ?> variables)) {
      return List.of();
    }
    Set<String> exact = new HashSet<>();
    List<String> prefixes = new ArrayList<>();
    for (ConfigKey key : ConfigKeyCatalog.keys()) {
      List<String> names = environmentNames(key.name());
      exact.addAll(names);
      if (key.type().startsWith("list<") || key.type().startsWith("map<")) {
        names.forEach(n -> prefixes.add(n + "_"));
      }
    }
    List<String> unknown = new ArrayList<>();
    for (Object name : variables.keySet()) {
      String variable = String.valueOf(name);
      if (!variable.toUpperCase(Locale.ROOT).startsWith("ARGOS_")) {
        continue;
      }
      String upper = variable.toUpperCase(Locale.ROOT);
      boolean known =
          exact.contains(upper)
              || EnvironmentAliases.isDocumented(upper)
              || prefixes.stream().anyMatch(upper::startsWith);
      if (!known) {
        unknown.add(variable);
      }
    }
    return unknown;
  }

  /**
   * The two relaxed environment names Spring accepts for a key (dashes removed or as {@code _}).
   */
  static List<String> environmentNames(String key) {
    String upper = key.toUpperCase(Locale.ROOT).replace('.', '_');
    String withoutDashes = upper.replace("-", "");
    String dashesAsUnderscores = upper.replace('-', '_');
    return withoutDashes.equals(dashesAsUnderscores)
        ? List.of(withoutDashes)
        : List.of(withoutDashes, dashesAsUnderscores);
  }

  /** One warning per secret given as a plain value outside the environment. */
  List<String> plaintextSecrets() {
    List<String> warnings = new ArrayList<>();
    for (ConfigKey key : ConfigKeyCatalog.keys()) {
      if (key.kind() != Kind.SECRET || key.valueIn(properties) == null) {
        continue;
      }
      String source = effective.sourceOf(key.name());
      if (source.startsWith("environment")) {
        continue;
      }
      warnings.add(
          key.name()
              + " is set as a plain value ("
              + source
              + "); put the secret into a file and set "
              + key.name()
              + "-file instead (17 §6.1)");
    }
    return warnings;
  }

  /** {@code <key> (<source>)}, with the key as written where the source knows it. */
  private static String describe(ConfigurationProperty property) {
    if (property.getOrigin() instanceof PropertySourceOrigin origin) {
      return origin.getPropertyName() + " (" + origin.getPropertySource().getName() + ")";
    }
    return property.getName() + " (" + describe(property.getOrigin()) + ")";
  }

  private static String describe(Origin origin) {
    return origin == null ? "unknown source" : origin.toString();
  }
}
