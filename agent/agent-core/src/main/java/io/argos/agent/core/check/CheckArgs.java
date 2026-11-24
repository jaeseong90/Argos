/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.check;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The command line of {@code java -jar argos-agent.jar} outside agent mode (10 §13.3): {@code
 * --version}, or {@code --check [--config <file>] [--json] [--classpath <dir|jar>...]}. A {@code
 * --classpath} value may also hold several paths joined by the platform path separator. Anything
 * else is a usage error (exit code 2).
 */
final class CheckArgs {
  static final String USAGE =
      "usage: java -jar argos-agent.jar --check [--config <argos-agent.yml>] [--json]"
          + " [--classpath <dir|jar>...]\n"
          + "       java -jar argos-agent.jar --version";

  /** Upper bound of {@code --classpath} paths (bounded scan). */
  static final int MAX_CLASSPATH_PATHS = 64;

  enum Mode {
    VERSION,
    CHECK,
    HELP
  }

  final Mode mode;
  final File config;
  final boolean json;
  final List<File> classpath;

  private CheckArgs(Mode mode, File config, boolean json, List<File> classpath) {
    this.mode = mode;
    this.config = config;
    this.json = json;
    this.classpath = Collections.unmodifiableList(classpath);
  }

  /** Thrown for a usage error; the message says what is wrong (no secret is ever an argument). */
  static final class UsageException extends Exception {
    private static final long serialVersionUID = 1L;

    UsageException(String message) {
      super(message);
    }
  }

  static CheckArgs parse(String[] args) throws UsageException {
    if (args == null || args.length == 0) {
      throw new UsageException("no command given");
    }
    if (args.length == 1 && args[0].equals("--version")) {
      return new CheckArgs(Mode.VERSION, null, false, new ArrayList<File>());
    }
    if (args.length == 1 && (args[0].equals("--help") || args[0].equals("-h"))) {
      return new CheckArgs(Mode.HELP, null, false, new ArrayList<File>());
    }
    if (!args[0].equals("--check")) {
      throw new UsageException("unknown command '" + shown(args[0]) + "'");
    }
    File config = null;
    boolean json = false;
    boolean classpathGiven = false;
    List<File> classpath = new ArrayList<File>();
    int i = 1;
    while (i < args.length) {
      String arg = args[i];
      if (arg.equals("--json")) {
        if (json) {
          throw new UsageException("--json given twice");
        }
        json = true;
        i++;
      } else if (arg.equals("--config")) {
        if (config != null) {
          throw new UsageException("--config given twice");
        }
        if (i + 1 >= args.length || args[i + 1].startsWith("--") || args[i + 1].isEmpty()) {
          throw new UsageException("--config needs a file");
        }
        config = new File(args[i + 1]);
        i += 2;
      } else if (arg.equals("--classpath")) {
        int start = i + 1;
        i = start;
        while (i < args.length && !args[i].startsWith("--")) {
          for (String part : args[i].split(File.pathSeparator, -1)) {
            if (!part.trim().isEmpty()) {
              if (classpath.size() >= MAX_CLASSPATH_PATHS) {
                throw new UsageException(
                    "--classpath takes at most " + MAX_CLASSPATH_PATHS + " paths");
              }
              classpath.add(new File(part.trim()));
            }
          }
          i++;
        }
        if (i == start) {
          throw new UsageException("--classpath needs at least one directory or jar");
        }
        classpathGiven = true;
      } else {
        throw new UsageException("unknown option '" + shown(arg) + "'");
      }
    }
    if (classpathGiven && classpath.isEmpty()) {
      throw new UsageException("--classpath needs at least one directory or jar");
    }
    return new CheckArgs(Mode.CHECK, config, json, classpath);
  }

  /** An argument as quoted in a usage message: at most 64 characters, no control characters. */
  private static String shown(String arg) {
    String text = arg.length() > 64 ? arg.substring(0, 64) + "..." : arg;
    return CheckReport.clean(text);
  }
}
