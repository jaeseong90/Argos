/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * Effective agent configuration (10 §15, 02 §7): system properties {@code -Dargos.<key>}, then
 * environment variables {@code ARGOS_<KEY>} (the later source wins). Immutable.
 *
 * <p>WO-02 scope: no YAML file yet ({@code argos.config} is reported and ignored; WO-10 adds the
 * file as the lowest-precedence source), values are read as strings and converted on access with
 * range checks; invalid values fall back to the default and are reported in {@link #warnings()}.
 */
public final class AgentConfig {
  public static final String SOURCE_DEFAULT = "default";
  public static final String SOURCE_PROPERTY = "system property";
  public static final String SOURCE_ENVIRONMENT = "environment";

  private final Map<String, String> values;
  private final Map<String, String> sources;
  private final List<String> warnings;

  private AgentConfig(
      Map<String, String> values, Map<String, String> sources, List<String> warnings) {
    this.values = values;
    this.sources = sources;
    this.warnings = warnings;
  }

  /**
   * Reads the configuration of this JVM ({@link System#getProperties()}, {@link System#getenv()}).
   */
  public static AgentConfig fromSystem() {
    return load(System.getProperties(), System.getenv());
  }

  /** Reads {@code argos.*} properties and {@code ARGOS_*} variables of known keys. */
  public static AgentConfig load(Properties properties, Map<String, String> environment) {
    Map<String, String> values = new LinkedHashMap<String, String>();
    Map<String, String> sources = new LinkedHashMap<String, String>();
    List<String> warnings = new ArrayList<String>();
    for (String name : properties.stringPropertyNames()) {
      if (!name.startsWith(ConfigKeys.PROPERTY_PREFIX)) {
        continue;
      }
      String key = name.substring(ConfigKeys.PROPERTY_PREFIX.length());
      if (!ConfigKeys.KNOWN.contains(key)) {
        warnings.add("unknown configuration key '" + key + "' (system property " + name + ")");
        continue;
      }
      values.put(key, properties.getProperty(name).trim());
      sources.put(key, SOURCE_PROPERTY);
    }
    for (String key : ConfigKeys.KNOWN) {
      String value = environment.get(ConfigKeys.environmentName(key));
      if (value != null) {
        values.put(key, value.trim());
        sources.put(key, SOURCE_ENVIRONMENT);
      }
    }
    if (values.containsKey(ConfigKeys.CONFIG_FILE)) {
      warnings.add(
          "argos.config is not supported yet (YAML configuration arrives with WO-10); use"
              + " -Dargos.<key> or ARGOS_<KEY>");
    }
    return new AgentConfig(
        Collections.unmodifiableMap(values),
        Collections.unmodifiableMap(sources),
        new ArrayList<String>(warnings));
  }

  /** A configuration from explicit values (tests). */
  public static AgentConfig of(Map<String, String> values) {
    Map<String, String> sources = new LinkedHashMap<String, String>();
    for (String key : values.keySet()) {
      sources.put(key, SOURCE_PROPERTY);
    }
    return new AgentConfig(
        Collections.unmodifiableMap(new LinkedHashMap<String, String>(values)),
        Collections.unmodifiableMap(sources),
        new ArrayList<String>());
  }

  /** The raw value of {@code key}, or {@code defaultValue} when unset or empty. */
  public String get(String key, String defaultValue) {
    String value = values.get(key);
    return value == null || value.isEmpty() ? defaultValue : value;
  }

  public boolean getBoolean(String key, boolean defaultValue) {
    String value = values.get(key);
    if (value == null || value.isEmpty()) {
      return defaultValue;
    }
    String normalized = value.toLowerCase(Locale.ROOT);
    if (normalized.equals("true")) {
      return true;
    }
    if (normalized.equals("false")) {
      return false;
    }
    warn(key, value, "true or false", String.valueOf(defaultValue));
    return defaultValue;
  }

  /** An integer in {@code [min, max]}; other values fall back to {@code defaultValue}. */
  public int getInt(String key, int defaultValue, int min, int max) {
    long value = getLong(key, defaultValue, min, max);
    return (int) value;
  }

  /** A long in {@code [min, max]}; other values fall back to {@code defaultValue}. */
  public long getLong(String key, long defaultValue, long min, long max) {
    String value = values.get(key);
    if (value == null || value.isEmpty()) {
      return defaultValue;
    }
    try {
      long parsed = Long.parseLong(value);
      if (parsed >= min && parsed <= max) {
        return parsed;
      }
    } catch (NumberFormatException e) {
      // reported below
    }
    warn(key, value, "an integer in [" + min + ", " + max + "]", String.valueOf(defaultValue));
    return defaultValue;
  }

  /** A comma-separated list (blank items removed), empty when unset. */
  public List<String> getList(String key) {
    String value = values.get(key);
    if (value == null || value.isEmpty()) {
      return Collections.emptyList();
    }
    List<String> items = new ArrayList<String>();
    for (String item : value.split(",", -1)) {
      String trimmed = item.trim();
      if (!trimmed.isEmpty()) {
        items.add(trimmed);
      }
    }
    return Collections.unmodifiableList(items);
  }

  public boolean isSet(String key) {
    return values.containsKey(key);
  }

  /** Where {@code key} came from ({@link #SOURCE_PROPERTY}, ...). */
  public String source(String key) {
    String source = sources.get(key);
    return source == null ? SOURCE_DEFAULT : source;
  }

  /** Keys set explicitly, in source order. */
  public Iterable<String> keys() {
    return values.keySet();
  }

  /** Problems found while reading (unknown keys, invalid values); logged as WARN at start. */
  public synchronized List<String> warnings() {
    return new ArrayList<String>(warnings);
  }

  private synchronized void warn(String key, String value, String expected, String fallback) {
    if (warnings.size() < 100) {
      warnings.add(
          "invalid value '"
              + value
              + "' for "
              + key
              + " (expected "
              + expected
              + "), using "
              + fallback);
    }
  }
}
