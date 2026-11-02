/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.overhead;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Command-line options: {@code --name value}, {@code --name=value} and {@code --flag}. */
final class Options {
  private static final Pattern DURATION_PART = Pattern.compile("(\\d+)(ms|s|m|h)");
  private static final Pattern COMMA = Pattern.compile(",");

  private final Map<String, String> values = new LinkedHashMap<>();
  private final Set<String> read = new HashSet<>();

  private Options() {}

  /** Parses {@code args}; names in {@code flags} take no value. */
  static Options parse(List<String> args, Set<String> flags) {
    Options options = new Options();
    for (int i = 0; i < args.size(); i++) {
      String arg = args.get(i);
      if (!arg.startsWith("--") || arg.length() == 2) {
        throw new UsageException("unexpected argument '" + arg + "'");
      }
      String name = arg.substring(2);
      String value;
      int eq = name.indexOf('=');
      if (eq > 0) {
        value = name.substring(eq + 1);
        name = name.substring(0, eq);
      } else if (flags.contains(name)) {
        value = "true";
      } else {
        if (i + 1 >= args.size()) {
          throw new UsageException(arg + " needs a value");
        }
        i++;
        value = args.get(i);
      }
      if (options.values.put(name, value) != null) {
        throw new UsageException("--" + name + " given twice");
      }
    }
    return options;
  }

  boolean has(String name) {
    read.add(name);
    return values.containsKey(name);
  }

  Optional<String> optional(String name) {
    read.add(name);
    return Optional.ofNullable(values.get(name));
  }

  String string(String name, String fallback) {
    return optional(name).orElse(fallback);
  }

  String required(String name) {
    return optional(name).orElseThrow(() -> new UsageException("--" + name + " is required"));
  }

  boolean flag(String name) {
    return optional(name).map(v -> !v.equalsIgnoreCase("false")).orElse(false);
  }

  int integer(String name, int fallback, int min, int max) {
    Optional<String> raw = optional(name);
    if (raw.isEmpty()) {
      return fallback;
    }
    try {
      int value = Integer.parseInt(raw.get().trim());
      if (value < min || value > max) {
        throw new UsageException("--" + name + " must be " + min + ".." + max);
      }
      return value;
    } catch (NumberFormatException e) {
      throw new UsageException("--" + name + " must be a number: " + raw.get());
    }
  }

  double decimal(String name, double fallback) {
    Optional<String> raw = optional(name);
    if (raw.isEmpty()) {
      return fallback;
    }
    try {
      return Double.parseDouble(raw.get().trim());
    } catch (NumberFormatException e) {
      throw new UsageException("--" + name + " must be a number: " + raw.get());
    }
  }

  Duration duration(String name, String fallback) {
    String raw = string(name, fallback);
    try {
      return parseDuration(raw);
    } catch (IllegalArgumentException e) {
      throw new UsageException("--" + name + ": " + e.getMessage());
    }
  }

  /** Comma-separated values, trimmed, empty entries dropped. */
  List<String> list(String name, String fallback) {
    List<String> out = new ArrayList<>();
    for (String part : COMMA.split(string(name, fallback), -1)) {
      String trimmed = part.trim();
      if (!trimmed.isEmpty()) {
        out.add(trimmed);
      }
    }
    return out;
  }

  /** Fails on any option that no code asked for (typos). */
  void rejectUnknown() {
    for (String name : values.keySet()) {
      if (!read.contains(name)) {
        throw new UsageException("unknown option --" + name);
      }
    }
  }

  /** k6-style duration: 500ms, 30s, 3m, 1m30s, 2h; a bare number is seconds. */
  static Duration parseDuration(String text) {
    String value = text.trim().toLowerCase(Locale.ROOT);
    if (value.matches("\\d+")) {
      return Duration.ofSeconds(Long.parseLong(value));
    }
    Matcher m = DURATION_PART.matcher(value);
    long millis = 0;
    int position = 0;
    while (position < value.length()) {
      if (!m.find(position) || m.start() != position) {
        throw new IllegalArgumentException(
            "bad duration '" + text + "' (examples: 30s, 3m, 1m30s)");
      }
      long n = Long.parseLong(m.group(1));
      millis +=
          switch (m.group(2)) {
            case "ms" -> n;
            case "s" -> n * 1000;
            case "m" -> n * 60_000;
            default -> n * 3_600_000;
          };
      position = m.end();
    }
    if (millis <= 0) {
      throw new IllegalArgumentException("duration '" + text + "' must be positive");
    }
    return Duration.ofMillis(millis);
  }

  /** A duration as k6 reads it ({@code 600s}, {@code 1500ms}). */
  static String k6Duration(Duration d) {
    return d.toMillis() % 1000 == 0 ? (d.toMillis() / 1000) + "s" : d.toMillis() + "ms";
  }
}
