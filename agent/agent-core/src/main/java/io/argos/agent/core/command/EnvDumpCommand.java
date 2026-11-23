/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.command;

import io.argos.agent.core.config.AgentConfig;
import io.argos.agent.core.log.AgentLogger;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * {@code ENV_DUMP} (10 §12.1, 16 §5.4, SEC-20): environment variables, system properties and JVM
 * input arguments, masked by {@link EnvMasker} before they leave the agent, as {@code text/plain}:
 *
 * <pre>
 * # env (2)
 * DB_PASSWORD=****(len=6)
 * PATH=/usr/bin:/bin
 *
 * # properties (1)
 * java.version=21
 *
 * # args (1)
 * -Dspring.datasource.password=****(len=1)
 * </pre>
 *
 * <p>Keys are sorted within a section, arguments keep their order. Control characters are escaped
 * ({@code \n}, {@code \r}, {@code \t}, {@code \}{@code uXXXX}) after masking, so an entry is one
 * line. A value longer than {@value #MAX_VALUE_CHARS} characters is cut after masking. The result
 * is at most {@value #MAX_BYTES} bytes (whole lines are dropped, {@code truncated}). Masking cannot
 * be turned off: operator patterns ({@code commands.env-dump.mask-patterns}) are added to the fixed
 * rules. Values are never logged.
 */
final class EnvDumpCommand implements CommandHandler {
  /** Result cap (10 §12.1). */
  static final int MAX_BYTES = 256 * 1024;

  /** Characters of one value kept (after masking). */
  static final int MAX_VALUE_CHARS = 8_192;

  /** Entries per section at most. */
  static final int MAX_ENTRIES = 4_096;

  /** Marker line of a cut result. */
  static final String TRUNCATED_LINE = "# truncated";

  private final CommandEnv env;
  private volatile boolean rejectedLogged;

  EnvDumpCommand(CommandEnv env) {
    this.env = env;
  }

  @Override
  public CommandOutput run(Map<String, String> args) {
    EnvMasker masker = masker(env.config());
    if (masker.rejectedUserPatterns() > 0 && !rejectedLogged) {
      rejectedLogged = true;
      AgentLogger.warn(
          "commands.env-dump.mask-patterns: {} entries ignored (invalid regular expression, longer"
              + " than {} characters or more than {} entries); the built-in masking still applies",
          masker.rejectedUserPatterns(),
          EnvMasker.MAX_USER_PATTERN_CHARS,
          EnvMasker.MAX_USER_PATTERNS);
    }
    return dump(masker, environment(), properties(), inputArguments());
  }

  /** The masker of {@code config}: the fixed rules plus the operator patterns. */
  static EnvMasker masker(AgentConfig config) {
    List<String> patterns = Collections.emptyList();
    if (config != null) {
      try {
        patterns = config.envDumpMaskPatterns();
      } catch (RuntimeException e) {
        patterns = Collections.emptyList();
      }
    }
    return new EnvMasker(patterns);
  }

  /** The masked dump of the given sources (null source: section reported as unavailable). */
  static CommandOutput dump(
      EnvMasker masker,
      Map<String, String> environment,
      Map<String, String> properties,
      List<String> arguments) {
    Output out = new Output();
    out.section("env", environment == null ? -1 : environment.size());
    if (environment != null) {
      int count = 0;
      for (Map.Entry<String, String> e : new TreeMap<String, String>(environment).entrySet()) {
        if (count++ >= MAX_ENTRIES) {
          out.truncated = true;
          break;
        }
        out.entry(e.getKey(), masker.maskEnv(e.getKey(), e.getValue()));
      }
    }
    out.blank();
    out.section("properties", properties == null ? -1 : properties.size());
    if (properties != null) {
      int count = 0;
      for (Map.Entry<String, String> e : new TreeMap<String, String>(properties).entrySet()) {
        if (count++ >= MAX_ENTRIES) {
          out.truncated = true;
          break;
        }
        out.entry(e.getKey(), masker.maskProperty(e.getKey(), e.getValue()));
      }
    }
    out.blank();
    out.section("args", arguments == null ? -1 : arguments.size());
    if (arguments != null) {
      int count = 0;
      for (String argument : arguments) {
        if (count++ >= MAX_ENTRIES) {
          out.truncated = true;
          break;
        }
        out.line(escape(cutValue(masker.maskArgument(argument))));
      }
    }
    return out.finish();
  }

  static Map<String, String> environment() {
    try {
      return System.getenv();
    } catch (RuntimeException e) {
      return null;
    }
  }

  static Map<String, String> properties() {
    try {
      Properties system = System.getProperties();
      Map<String, String> copy = new TreeMap<String, String>();
      for (String name : system.stringPropertyNames()) {
        String value = system.getProperty(name);
        if (value != null) {
          copy.put(name, value);
        }
      }
      return copy;
    } catch (RuntimeException e) {
      return null;
    }
  }

  static List<String> inputArguments() {
    try {
      return new ArrayList<String>(ManagementFactory.getRuntimeMXBean().getInputArguments());
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static String cutValue(String value) {
    if (value == null || value.length() <= MAX_VALUE_CHARS) {
      return value;
    }
    int end = MAX_VALUE_CHARS;
    if (Character.isHighSurrogate(value.charAt(end - 1))) {
      end--;
    }
    return value.substring(0, end) + "...(cut " + (value.length() - end) + " chars)";
  }

  /** Control characters escaped so that an entry stays on one line. */
  static String escape(String value) {
    if (value == null) {
      return "";
    }
    StringBuilder out = null;
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      String replacement = null;
      if (c == '\n') {
        replacement = "\\n";
      } else if (c == '\r') {
        replacement = "\\r";
      } else if (c == '\t') {
        replacement = "\\t";
      } else if (c < 0x20 || c == 0x7f || c == 0x2028 || c == 0x2029) {
        replacement = String.format(java.util.Locale.ROOT, "\\u%04x", (int) c);
      }
      if (replacement != null) {
        if (out == null) {
          out = new StringBuilder(value.length() + 16);
          out.append(value, 0, i);
        }
        out.append(replacement);
      } else if (out != null) {
        out.append(c);
      }
    }
    return out == null ? value : out.toString();
  }

  /** Lines within {@link #MAX_BYTES}. */
  private static final class Output {
    private final StringBuilder text = new StringBuilder(16 * 1024);
    private int bytes;
    private boolean full;
    boolean truncated;

    void section(String name, int size) {
      line("# " + name + (size < 0 ? " (unavailable)" : " (" + size + ")"));
    }

    void entry(String key, String maskedValue) {
      line(escape(key) + "=" + escape(cutValue(maskedValue)));
    }

    void blank() {
      line("");
    }

    void line(String line) {
      if (full) {
        return;
      }
      int size = line.getBytes(StandardCharsets.UTF_8).length + 1;
      if (bytes + size > MAX_BYTES - TRUNCATED_LINE.length() - 1) {
        truncated = true;
        full = true;
        return;
      }
      text.append(line).append('\n');
      bytes += size;
    }

    CommandOutput finish() {
      if (truncated) {
        text.append(TRUNCATED_LINE).append('\n');
      }
      return CommandOutput.text(text.toString().getBytes(StandardCharsets.UTF_8), truncated);
    }
  }
}
