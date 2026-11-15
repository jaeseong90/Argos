/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.query;

/**
 * The rules of 14 §2.10 (DSN-14-13, SEC-60) for strings that come from collected data (service
 * names, URLs, SQL, parameters, exceptions, stacks, headers, user ids, instance, host and thread
 * names, dictionary texts, command results):
 *
 * <ol>
 *   <li>never escaped by the server (no HTML or other escaping: the client renders text nodes only,
 *       and a second escaping would show the entity text to the user); the schema property carries
 *       {@code x-argos-untrusted: true} ({@code @ArgosUntrusted});
 *   <li>a length limit in UTF-8 bytes: {@value #NAME_MAX_BYTES} for names, {@value #SQL_MAX_BYTES}
 *       for SQL text, {@value #COMMAND_BODY_MAX_BYTES} for command result bodies; a longer value is
 *       cut at a character boundary and gets the suffix {@value #TRUNCATED_SUFFIX}, and the
 *       response says {@code truncated: true} next to it ({@link Text#truncated()});
 *   <li>control characters U+0000..U+001F except tab and line feed become U+FFFD.
 * </ol>
 *
 * The JSON writer applies rule 3 (and a 1 MB backstop of rule 2) to every {@code @ArgosUntrusted}
 * property on its own ({@link UntrustedStringsModule}); the length limits with their flag are the
 * endpoint's, through {@link #name}, {@link #sql} and {@link #commandBody}.
 */
public final class UntrustedStrings {

  /** Names (services, URLs, hosts, threads, classes, ...): 2 KB (14 §2.10). */
  public static final int NAME_MAX_BYTES = 2 * 1024;

  /** SQL text: 64 KB (14 §2.10, §4.8). */
  public static final int SQL_MAX_BYTES = 64 * 1024;

  /** Command result bodies: 1 MB (14 §2.10). */
  public static final int COMMAND_BODY_MAX_BYTES = 1024 * 1024;

  /** Suffix of a cut value (14 §2.10). */
  public static final String TRUNCATED_SUFFIX = "…[truncated]";

  /** The replacement of a control character. */
  public static final char REPLACEMENT = '�';

  private UntrustedStrings() {}

  /**
   * A value after the rules, and whether it was cut.
   *
   * @param value the value, null when the input was null
   * @param truncated whether the limit cut it
   */
  public record Text(String value, boolean truncated) {}

  /** Rule 3 only: control characters (except tab and line feed) replaced; null stays null. */
  public static String clean(String value) {
    if (value == null) {
      return null;
    }
    int first = -1;
    for (int i = 0; i < value.length(); i++) {
      if (isControl(value.charAt(i))) {
        first = i;
        break;
      }
    }
    if (first < 0) {
      return value;
    }
    char[] chars = value.toCharArray();
    for (int i = first; i < chars.length; i++) {
      if (isControl(chars[i])) {
        chars[i] = REPLACEMENT;
      }
    }
    return new String(chars);
  }

  /** Rules 2 and 3 with the name limit. */
  public static Text name(String value) {
    return cap(value, NAME_MAX_BYTES);
  }

  /** Rules 2 and 3 with the SQL limit. */
  public static Text sql(String value) {
    return cap(value, SQL_MAX_BYTES);
  }

  /** Rules 2 and 3 with the command body limit. */
  public static Text commandBody(String value) {
    return cap(value, COMMAND_BODY_MAX_BYTES);
  }

  /**
   * Rules 2 and 3 with {@code maxBytes}: at most {@code maxBytes} UTF-8 bytes of the value (whole
   * characters), then {@value #TRUNCATED_SUFFIX} when it was cut.
   */
  public static Text cap(String value, int maxBytes) {
    if (value == null) {
      return new Text(null, false);
    }
    String clean = clean(value);
    if (clean.length() <= maxBytes / 3) {
      return new Text(clean, false); // at most 3 bytes per UTF-16 unit: fits for sure
    }
    int bytes = 0;
    int i = 0;
    while (i < clean.length()) {
      int cp = clean.codePointAt(i);
      int size = cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
      if (bytes + size > maxBytes) {
        return new Text(clean.substring(0, i) + TRUNCATED_SUFFIX, true);
      }
      bytes += size;
      i += Character.charCount(cp);
    }
    return new Text(clean, false);
  }

  private static boolean isControl(char c) {
    return c < 0x20 && c != '\t' && c != '\n';
  }
}
