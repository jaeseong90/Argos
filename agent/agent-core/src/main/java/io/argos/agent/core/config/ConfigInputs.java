/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.config;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * What {@link ConfigLoader#load(ConfigInputs)} reads (10 §4.3 P4, §15): the {@code argos-agent.yml}
 * file, the system properties, the environment and whether this is a release build. A value object
 * so that tests and {@code --check} can load a configuration without touching the JVM's own
 * properties or environment.
 *
 * <p>Where the yml comes from ({@link #forAgent(File, String, boolean)}, {@link #locateYaml(File,
 * String, Map, Map, List)}): {@code -Dargos.config}, else {@code ARGOS_CONFIG}, else the agent
 * argument {@code config=<path>} (17 §6.2: {@code
 * -javaagent:argos-agent.jar=config=/opt/argos-agent/argos-agent.yml}), else {@code
 * argos-agent.yml} next to the agent jar when that file exists. An explicitly named file that does
 * not exist is a WARN of the loader; a missing file next to the jar is silent.
 */
public final class ConfigInputs {
  /** File name of the configuration next to the agent jar. */
  public static final String DEFAULT_FILE_NAME = "argos-agent.yml";

  /** Environment variable naming the yml (the environment form of {@code -Dargos.config}). */
  public static final String CONFIG_ENVIRONMENT = "ARGOS_CONFIG";

  /** The agent argument naming the yml ({@code -javaagent:argos-agent.jar=config=<path>}). */
  public static final String CONFIG_ARGUMENT = "config=";

  /** Upper bound of {@link #inputWarnings()}. */
  static final int MAX_INPUT_WARNINGS = 8;

  private final File yamlFile;
  private final Map<String, String> systemProperties;
  private final Map<String, String> environment;
  private final boolean releaseBuild;
  private final String javaCommand;
  private final List<String> inputWarnings;

  /**
   * @param yamlFile the {@code argos-agent.yml} to read, or null when none was given ({@link
   *     ConfigKeys#CONFIG_FILE})
   * @param systemProperties the JVM system properties; only {@code argos.*} entries are kept
   * @param environment the process environment; only {@code ARGOS_*} entries are kept
   * @param releaseBuild true for a release build ({@code AgentInfo.isReleaseBuild()}, C-89): the
   *     test-only keys {@code test.*} are then ignored with one WARN (T-BLD-010)
   */
  public ConfigInputs(
      File yamlFile,
      Map<String, String> systemProperties,
      Map<String, String> environment,
      boolean releaseBuild) {
    this(
        yamlFile,
        systemProperties,
        environment,
        releaseBuild,
        null,
        Collections.<String>emptyList());
  }

  /**
   * As {@link #ConfigInputs(File, Map, Map, boolean)}, plus the JVM command line ({@code
   * sun.java.command}, the source of the fallback application name when {@code app.name} is
   * missing, {@link ConfigLoader#fallbackAppName(String)}) and warnings found while locating the
   * yml (unsupported agent arguments), at most {@value #MAX_INPUT_WARNINGS}.
   */
  public ConfigInputs(
      File yamlFile,
      Map<String, String> systemProperties,
      Map<String, String> environment,
      boolean releaseBuild,
      String javaCommand,
      List<String> inputWarnings) {
    this.yamlFile = yamlFile;
    this.systemProperties = filter(systemProperties, ConfigKeys.PROPERTY_PREFIX);
    this.environment = filter(environment, ConfigKeys.ENVIRONMENT_PREFIX);
    this.releaseBuild = releaseBuild;
    this.javaCommand = javaCommand;
    List<String> warnings = new ArrayList<String>();
    if (inputWarnings != null) {
      for (String warning : inputWarnings) {
        if (warnings.size() < MAX_INPUT_WARNINGS && warning != null) {
          warnings.add(warning);
        }
      }
    }
    this.inputWarnings = Collections.unmodifiableList(warnings);
  }

  /**
   * The inputs of this JVM: {@code yamlFile}, {@link System#getProperties()} and {@link
   * System#getenv()}.
   */
  public static ConfigInputs fromSystem(File yamlFile, boolean releaseBuild) {
    return new ConfigInputs(
        yamlFile,
        properties(System.getProperties()),
        System.getenv(),
        releaseBuild,
        System.getProperty("sun.java.command"),
        Collections.<String>emptyList());
  }

  /**
   * The inputs of the agent at premain (10 §4.3 P4): this JVM's properties and environment and the
   * yml located by {@link #locateYaml(File, String, Map, Map, List)} from the agent jar and the
   * agent argument string.
   */
  public static ConfigInputs forAgent(File agentJar, String agentArgs, boolean releaseBuild) {
    Map<String, String> properties = properties(System.getProperties());
    Map<String, String> env = System.getenv();
    List<String> warnings = new ArrayList<String>();
    File yaml = locateYaml(agentJar, agentArgs, properties, env, warnings);
    return new ConfigInputs(
        yaml, properties, env, releaseBuild, System.getProperty("sun.java.command"), warnings);
  }

  /**
   * The yml to read: {@code -Dargos.config} > {@code ARGOS_CONFIG} > agent argument {@code
   * config=<path>} > {@value #DEFAULT_FILE_NAME} next to {@code agentJar} when it exists; null when
   * none. An agent argument other than {@code config=<path>} adds a warning to {@code warnings}.
   */
  public static File locateYaml(
      File agentJar,
      String agentArgs,
      Map<String, String> systemProperties,
      Map<String, String> environment,
      List<String> warnings) {
    String argumentPath = null;
    String args = agentArgs == null ? "" : agentArgs.trim();
    if (args.startsWith(CONFIG_ARGUMENT)) {
      argumentPath = args.substring(CONFIG_ARGUMENT.length()).trim();
    } else if (!args.isEmpty()) {
      warnings.add(
          "agent arguments other than 'config=<path>' after '-javaagent:...=' are ignored;"
              + " configure with argos-agent.yml, -Dargos.<key> or ARGOS_<KEY>");
    }
    String property =
        systemProperties == null
            ? null
            : systemProperties.get(ConfigKeys.PROPERTY_PREFIX + ConfigKeys.CONFIG_FILE);
    String variable = environment == null ? null : environment.get(CONFIG_ENVIRONMENT);
    for (String explicit : new String[] {property, variable, argumentPath}) {
      if (explicit != null && !explicit.trim().isEmpty()) {
        return new File(explicit.trim());
      }
    }
    if (agentJar != null) {
      File directory = agentJar.getAbsoluteFile().getParentFile();
      File next = directory == null ? null : new File(directory, DEFAULT_FILE_NAME);
      if (next != null && next.isFile()) {
        return next;
      }
    }
    return null;
  }

  /** The string entries of {@code properties}. */
  public static Map<String, String> properties(Properties properties) {
    Map<String, String> map = new LinkedHashMap<String, String>();
    for (String name : properties.stringPropertyNames()) {
      map.put(name, properties.getProperty(name));
    }
    return map;
  }

  /** The yml file, or null. */
  public File yamlFile() {
    return yamlFile;
  }

  /** {@code argos.*} system properties (full names, with the prefix). */
  public Map<String, String> systemProperties() {
    return systemProperties;
  }

  /** {@code ARGOS_*} environment variables. */
  public Map<String, String> environment() {
    return environment;
  }

  public boolean releaseBuild() {
    return releaseBuild;
  }

  /** The JVM command line ({@code sun.java.command}), or null. */
  public String javaCommand() {
    return javaCommand;
  }

  /** Warnings found while locating the yml; the loader reports them with its own. */
  public List<String> inputWarnings() {
    return inputWarnings;
  }

  private static Map<String, String> filter(Map<String, String> source, String prefix) {
    Map<String, String> kept = new LinkedHashMap<String, String>();
    if (source != null) {
      for (Map.Entry<String, String> entry : source.entrySet()) {
        if (entry.getKey() != null
            && entry.getValue() != null
            && entry.getKey().startsWith(prefix)) {
          kept.put(entry.getKey(), entry.getValue());
        }
      }
    }
    return Collections.unmodifiableMap(kept);
  }
}
