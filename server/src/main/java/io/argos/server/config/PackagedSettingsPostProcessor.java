/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.config;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.OriginTrackedMapPropertySource;
import org.springframework.boot.origin.OriginTrackedValue;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * Keeps the packaged {@code application.yml} what it is meant to be, the server's defaults, by
 * removing two kinds of its entries when the environment is prepared:
 *
 * <ul>
 *   <li>a short-name placeholder ({@code argos.postgres.url: ${ARGOS_PG_URL:}}, {@link
 *       EnvironmentAliases}, 17 §3.8, §6.1) whose variable is not set: the key keeps the default of
 *       {@link ArgosProperties}, as if the line were not there; with the variable set the entry
 *       stays and works as the placeholder says;
 *   <li>a key that {@code SpringApplication.setDefaultProperties} also sets (code that embeds the
 *       server, test helpers such as {@code ServerTestEnvironment}): Spring Boot ranks those below
 *       every configuration file, so without this the packaged file would override them ({@code
 *       server.port}, {@code management.server.port}, ...).
 * </ul>
 *
 * Operator configuration files, environment variables, system properties and command line arguments
 * are not touched and still override everything. Runs after Spring Boot has loaded the
 * configuration files; registered in {@code META-INF/spring.factories}.
 */
public class PackagedSettingsPostProcessor implements EnvironmentPostProcessor, Ordered {

  /** Name of Spring Boot's default properties source. */
  static final String DEFAULT_PROPERTIES = "defaultProperties";

  /** An entry that is nothing but one short-name placeholder, plus an optional unit suffix. */
  private static final Pattern ALIAS_ONLY =
      Pattern.compile("\\$\\{(ARGOS_[A-Z0-9_]+):[^${}]*}[a-z]*");

  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE;
  }

  @Override
  public void postProcessEnvironment(
      ConfigurableEnvironment environment, SpringApplication application) {
    apply(environment);
  }

  /** Removes the entries described above from the packaged {@code application.yml}. */
  public static void apply(ConfigurableEnvironment environment) {
    MutablePropertySources sources = environment.getPropertySources();
    PropertySource<?> variables =
        sources.get(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
    PropertySource<?> defaults = sources.get(DEFAULT_PROPERTIES);
    Set<String> defaultKeys =
        defaults instanceof EnumerablePropertySource<?> e ? Set.of(e.getPropertyNames()) : Set.of();
    for (PropertySource<?> source : sources) {
      if (!EffectiveServerConfig.isPackagedApplicationYaml(source.getName())
          || !(source.getSource() instanceof Map<?, ?> entries)) {
        continue;
      }
      Map<String, Object> kept = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : entries.entrySet()) {
        String key = String.valueOf(entry.getKey());
        Object raw = entry.getValue();
        Object value = raw instanceof OriginTrackedValue tracked ? tracked.getValue() : raw;
        if (!defaultKeys.contains(key) && !unsetAlias(String.valueOf(value), variables)) {
          kept.put(key, raw);
        }
      }
      if (kept.size() != entries.size()) {
        sources.replace(
            source.getName(), new OriginTrackedMapPropertySource(source.getName(), kept, true));
      }
    }
  }

  private static boolean unsetAlias(String value, PropertySource<?> variables) {
    Matcher m = ALIAS_ONLY.matcher(value);
    return m.matches()
        && EnvironmentAliases.ALIASES.containsKey(m.group(1))
        && (variables == null || variables.getProperty(m.group(1)) == null);
  }
}
