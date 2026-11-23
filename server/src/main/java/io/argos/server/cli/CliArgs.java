/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.cli;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.Locale;

/**
 * Option parsing shared by the {@code argos-admin} commands: {@code --name value} or {@code
 * --name=value}, dates {@code yyyy-MM-dd} (strict), positive ids. Messages quote at most 80
 * characters of a value, control characters replaced.
 */
final class CliArgs {

  private static final DateTimeFormatter DATE =
      DateTimeFormatter.ofPattern("uuuu-MM-dd", Locale.ROOT)
          .withResolverStyle(ResolverStyle.STRICT);

  private final List<String> args;
  private int index;
  private String name;
  private String inlineValue;

  CliArgs(List<String> args) {
    this.args = List.copyOf(args);
    this.index = -1;
  }

  /** Moves to the next option; false at the end. */
  boolean next() {
    index++;
    if (index >= args.size()) {
      return false;
    }
    String arg = args.get(index);
    int eq = arg.indexOf('=');
    if (arg.startsWith("--") && eq > 0) {
      name = arg.substring(0, eq);
      inlineValue = arg.substring(eq + 1);
    } else {
      name = arg;
      inlineValue = null;
    }
    return true;
  }

  /** The current option name ({@code --date}, or the whole argument when it is not an option). */
  String name() {
    return name;
  }

  /** The value of the current option (inline or the next argument). */
  String value() throws CliUsageException {
    if (inlineValue != null) {
      return inlineValue;
    }
    if (index + 1 >= args.size() || args.get(index + 1).startsWith("--")) {
      throw new CliUsageException(name + " needs a value");
    }
    index++;
    return args.get(index);
  }

  /** Refuses a value on a flag ({@code --json=x}). */
  void noValue() throws CliUsageException {
    if (inlineValue != null) {
      throw new CliUsageException(name + " takes no value");
    }
  }

  /** The value of the current option as a date {@code yyyy-MM-dd}. */
  LocalDate date() throws CliUsageException {
    String value = value();
    try {
      return LocalDate.parse(value, DATE);
    } catch (DateTimeParseException e) {
      throw new CliUsageException(name + " must be a date yyyy-MM-dd, got: " + shorten(value));
    }
  }

  /** The value of the current option as an application id (1 to 2^31-1). */
  int appId() throws CliUsageException {
    String value = value();
    try {
      int id = Integer.parseInt(value);
      if (id >= 1) {
        return id;
      }
    } catch (NumberFormatException e) {
      // reported below
    }
    throw new CliUsageException(
        name + " must be a positive application id, got: " + shorten(value));
  }

  /**
   * The value of the current option as an unsigned 64-bit id (a dictionary key such as a service
   * id, decimal 0 to 2^64-1), returned as the signed long with the same bits.
   */
  long unsignedId() throws CliUsageException {
    String value = value();
    if (!value.isEmpty() && value.chars().allMatch(c -> c >= '0' && c <= '9')) {
      try {
        return Long.parseUnsignedLong(value);
      } catch (NumberFormatException e) {
        // reported below
      }
    }
    throw new CliUsageException(
        name
            + " must be an unsigned decimal id (0 to 18446744073709551615), got: "
            + shorten(value));
  }

  /** Text quoted in a message: control characters replaced, at most 80 characters. */
  static String shorten(String text) {
    String oneLine = text.replaceAll("\\p{Cntrl}", "?");
    return oneLine.length() > 80 ? oneLine.substring(0, 77) + "..." : oneLine;
  }
}
