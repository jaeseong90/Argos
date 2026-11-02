/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.log;

/** {@code {}} placeholder formatting (no {@code String.format}, no regular expressions). */
final class LogFormat {
  private LogFormat() {}

  /**
   * Replaces each {@code {}} in {@code pattern} with the next argument. A trailing {@link
   * Throwable} that no placeholder consumes is left for the stack trace; surplus arguments are
   * ignored, missing ones leave {@code {}} as is.
   */
  static String format(String pattern, Object[] args) {
    if (pattern == null) {
      return "null";
    }
    if (args == null || args.length == 0 || pattern.indexOf("{}") < 0) {
      return pattern;
    }
    StringBuilder out = new StringBuilder(pattern.length() + 32 * args.length);
    int argument = 0;
    int from = 0;
    while (true) {
      int at = pattern.indexOf("{}", from);
      if (at < 0 || argument >= args.length) {
        out.append(pattern, from, pattern.length());
        break;
      }
      out.append(pattern, from, at);
      out.append(String.valueOf(args[argument++]));
      from = at + 2;
    }
    return out.toString();
  }
}
