/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.boot;

import java.util.regex.Pattern;

/**
 * Class name globs of {@code instrumentation.exclude-classes} (10 §15): {@code *} matches within a
 * package segment, {@code **} across segments, {@code ?} one character; {@code $} and every other
 * character match literally.
 */
final class Glob {
  private Glob() {}

  static Pattern toPattern(String glob) {
    StringBuilder regex = new StringBuilder(glob.length() + 16);
    for (int i = 0; i < glob.length(); i++) {
      char c = glob.charAt(i);
      if (c == '*') {
        if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
          regex.append(".*");
          i++;
        } else {
          regex.append("[^.]*");
        }
      } else if (c == '?') {
        regex.append("[^.]");
      } else if ("\\.[]{}()<>+-=!^$|".indexOf(c) >= 0) {
        regex.append('\\').append(c);
      } else {
        regex.append(c);
      }
    }
    return Pattern.compile(regex.toString());
  }
}
