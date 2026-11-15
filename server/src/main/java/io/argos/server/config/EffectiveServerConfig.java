/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.config;

import io.argos.server.bootstrap.PhaseOrder;
import io.argos.server.bootstrap.StartupPhase;
import io.argos.server.config.ConfigKeyCatalog.ConfigKey;
import io.argos.server.config.ConfigKeyCatalog.Kind;
import io.argos.server.config.logging.LogMasking;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.source.ConfigurationProperty;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.ConfigurationPropertyState;
import org.springframework.boot.context.properties.source.IterableConfigurationPropertySource;
import org.springframework.boot.origin.Origin;
import org.springframework.boot.origin.PropertySourceOrigin;
import org.springframework.boot.origin.SystemEnvironmentOrigin;
import org.springframework.boot.origin.TextResourceOrigin;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.CommandLinePropertySource;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.stereotype.Component;

/**
 * The effective {@code argos.*} configuration (17 §6.1, 02 §7): every key of {@link
 * ConfigKeyCatalog} with its bound value and where the value came from. Secrets are shown as {@code
 * ****} and every value passes {@link LogMasking} (credentials inside URLs). Used by the start
 * summary below and, later, by {@code argos-admin config effective} and the administration UI
 * (WO-28).
 *
 * <p>Sources: {@code default} (the built-in default, including the packaged {@code application.yml}
 * when its short environment name is not set), {@code file <resource>} (an operator configuration
 * file), {@code environment <VARIABLE>} (a relaxed environment name or a short name of 17 §3.8),
 * {@code system property}, {@code command line}, {@code default properties} (set by code that
 * embeds the server, e.g. tests) or {@code other <name>}.
 *
 * <p>Start step {@code effective-config} ({@code CONFIG}, after the warnings and before validation,
 * so the summary is there when the start is refused): one INFO line with the number of settings
 * that differ from the defaults, then one INFO line per such setting.
 */
@Component
@Order(ConfigValidation.ORDER - 50)
public class EffectiveServerConfig implements StartupPhase {

  private static final Logger LOG = LoggerFactory.getLogger(EffectiveServerConfig.class);

  /** Source of a value that nobody configured. */
  public static final String DEFAULT = "default";

  private static final Pattern PLACEHOLDER_VARIABLE = Pattern.compile("\\$\\{(ARGOS_[A-Z0-9_]+)");

  /** Spring Boot's name of a configuration file source: {@code Config resource '<r>' via ...}. */
  private static final Pattern CONFIG_RESOURCE = Pattern.compile("Config resource '([^']*)'");

  private final ConfigurableEnvironment environment;
  private final ArgosProperties properties;

  public EffectiveServerConfig(ConfigurableEnvironment environment, ArgosProperties properties) {
    this.environment = environment;
    this.properties = properties;
  }

  /**
   * One setting.
   *
   * @param key the key
   * @param value the bound value, rendered; {@code ****} for a set secret
   * @param source where the value came from
   */
  public record Entry(String key, String value, String source) {

    /** Whether nobody configured this setting. */
    public boolean isDefault() {
      return DEFAULT.equals(source);
    }
  }

  @Override
  public PhaseOrder order() {
    return PhaseOrder.CONFIG;
  }

  @Override
  public String name() {
    return "effective-config";
  }

  @Override
  public void run() {
    List<Entry> changed = entries().stream().filter(e -> !e.isDefault()).toList();
    LOG.info(
        "Effective configuration: {} of {} argos.* settings differ from the defaults",
        changed.size(),
        ConfigKeyCatalog.keys().size());
    for (Entry entry : changed) {
      LOG.info("Setting {} = {} ({})", entry.key(), entry.value(), entry.source());
    }
  }

  /** Every key, sorted. */
  public List<Entry> entries() {
    List<Entry> entries = new ArrayList<>();
    for (ConfigKey key : ConfigKeyCatalog.keys()) {
      entries.add(
          new Entry(key.name(), display(key, key.valueIn(properties)), sourceOf(key.name())));
    }
    return Collections.unmodifiableList(entries);
  }

  /** The value as shown: secrets masked, credentials inside values masked. */
  static String display(ConfigKey key, Object value) {
    if (key.kind() == Kind.SECRET) {
      return value == null ? ConfigKeyCatalog.render(null) : LogMasking.MASK;
    }
    return LogMasking.mask(ConfigKeyCatalog.render(value));
  }

  /** Where the value of {@code key} (or of its elements, for lists and maps) came from. */
  public String sourceOf(String key) {
    ConfigurationPropertyName name = ConfigurationPropertyName.of(key);
    boolean container = isContainer(key);
    for (ConfigurationPropertySource source : ConfigurationPropertySources.get(environment)) {
      ConfigurationProperty property = source.getConfigurationProperty(name);
      if (property == null
          && container
          && source.containsDescendantOf(name) == ConfigurationPropertyState.PRESENT) {
        property = firstDescendant(source, name);
      }
      if (property != null) {
        return label(property, source.getUnderlyingSource());
      }
    }
    return DEFAULT;
  }

  /** Lists and maps are set through their elements ({@code key[0]}, {@code key.name}). */
  private static boolean isContainer(String key) {
    return ConfigKeyCatalog.keys().stream()
        .anyMatch(
            k ->
                k.name().equals(key)
                    && (k.type().startsWith("list<") || k.type().startsWith("map<")));
  }

  private static ConfigurationProperty firstDescendant(
      ConfigurationPropertySource source, ConfigurationPropertyName name) {
    if (source instanceof IterableConfigurationPropertySource iterable) {
      Iterator<ConfigurationPropertyName> names = iterable.filter(name::isAncestorOf).iterator();
      if (names.hasNext()) {
        return iterable.getConfigurationProperty(names.next());
      }
    }
    return null;
  }

  private String label(ConfigurationProperty property, Object underlying) {
    String sourceName = underlying instanceof PropertySource<?> ps ? ps.getName() : "";
    Origin origin = property.getOrigin();
    if (StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME.equals(sourceName)) {
      if (origin instanceof SystemEnvironmentOrigin env) {
        return "environment " + env.getProperty();
      }
      return origin instanceof PropertySourceOrigin ps
          ? "environment " + ps.getPropertyName()
          : "environment";
    }
    if (StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME.equals(sourceName)) {
      return "system property";
    }
    if (CommandLinePropertySource.COMMAND_LINE_PROPERTY_SOURCE_NAME.equals(sourceName)) {
      return "command line";
    }
    if (isPackagedApplicationYaml(sourceName)) {
      return packagedSource(property.getValue());
    }
    if (origin instanceof TextResourceOrigin text && text.getResource() != null) {
      return fileLabel(text.getResource().getDescription());
    }
    Matcher resource = CONFIG_RESOURCE.matcher(sourceName);
    if (resource.lookingAt()) {
      return fileLabel(resource.group(1));
    }
    if (PackagedSettingsPostProcessor.DEFAULT_PROPERTIES.equals(sourceName)) {
      return "default properties";
    }
    return "other " + sourceName;
  }

  /** {@code file [conf/application.yml]} for a resource description. */
  private static String fileLabel(String description) {
    return description.startsWith("file [") ? description : "file " + description;
  }

  /** The {@code application.yml} inside the server jar (not an operator file). */
  static boolean isPackagedApplicationYaml(String sourceName) {
    return sourceName.contains("class path resource [application.yml]")
        || sourceName.contains("class path resource [application.yaml]");
  }

  /**
   * The packaged file holds only defaults and short-name placeholders: the source is the short
   * environment name when that variable is set, otherwise the default.
   */
  private String packagedSource(Object rawValue) {
    PropertySource<?> env =
        environment
            .getPropertySources()
            .get(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
    if (rawValue instanceof CharSequence raw && env != null) {
      Matcher m = PLACEHOLDER_VARIABLE.matcher(raw);
      while (m.find()) {
        if (env.getProperty(m.group(1)) != null) {
          return "environment " + m.group(1);
        }
      }
    }
    return DEFAULT;
  }
}
