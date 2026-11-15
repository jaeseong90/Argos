/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ingest;

import io.argos.server.ingest.hash.Xxh3;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * The error group fingerprint (F-S19, 12 §3.5 (4), DSN-12-06) with the input of ADR-0010 C-131:
 *
 * <pre>
 * error_group_id = xxh3_64(error_class_id as 8 bytes big-endian
 *                          || pattern(error_message) as UTF-8
 *                          || error_top_frame_id as 8 bytes big-endian)
 * pattern(m)     = normalize(ServerMasking.mask(m))
 * </pre>
 *
 * <p>The ids are the content hashes of the dictionary (11 §7), so the group of an error does not
 * depend on whether, or in which order, its dictionary texts have arrived. {@code xxh3_64} is
 * {@link Xxh3#hash64(byte[], int, int)}: the reference XXH3-64 with seed 0 and the raw value 0
 * mapped to 1, because {@code error_group_id = 0} means "no error group" (a transaction without an
 * error). WO-27 uses the same rule.
 *
 * <p>Normalization of the masked message (12 §3.5 (4)), in this order: quoted strings ({@code '…'}
 * or {@code "…"} that start and end at a word boundary, on one line) → {@code '?'}; UUIDs → {@code
 * ?}; {@code 0x} hexadecimal literals → {@code ?}; runs of 8 or more hexadecimal characters that
 * hold both a digit and a letter → {@code ?}; runs of digits → {@code #}; whitespace runs → one
 * space, trimmed; cut to {@value #MAX_PATTERN_CHARS} characters. A pure digit run is a number, not
 * hex, so {@code id=1234567} and {@code id=12345678} share a pattern.
 */
public final class ErrorGroupFingerprinter {

  /** Length of the stored and hashed pattern. */
  public static final int MAX_PATTERN_CHARS = 256;

  private static final Pattern SINGLE_QUOTED =
      Pattern.compile("(?<![\\w'])'[^'\\r\\n]*+'(?![\\w'])");
  private static final Pattern DOUBLE_QUOTED =
      Pattern.compile("(?<![\\w\"])\"[^\"\\r\\n]*+\"(?![\\w\"])");
  private static final Pattern UUID =
      Pattern.compile(
          "(?<![0-9A-Za-z])[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}(?![0-9A-Za-z])");
  private static final Pattern HEX_LITERAL =
      Pattern.compile("(?<![0-9A-Za-z_])0[xX][0-9a-fA-F]++(?![0-9A-Za-z_])");
  private static final Pattern HEX_RUN =
      Pattern.compile(
          "(?<![0-9A-Za-z_])(?=[0-9]*+[a-fA-F])(?=[a-fA-F]*+[0-9])[0-9a-fA-F]{8,}+(?![0-9A-Za-z_])");
  private static final Pattern DIGITS = Pattern.compile("[0-9]++");
  private static final Pattern WHITESPACE = Pattern.compile("\\s++");

  private ErrorGroupFingerprinter() {}

  /** The stored pattern of an exception message ({@code error_groups.message_pattern}). */
  public static String pattern(String message) {
    if (message == null || message.isEmpty()) {
      return "";
    }
    String s = ServerMasking.mask(message);
    s = SINGLE_QUOTED.matcher(s).replaceAll("'?'");
    s = DOUBLE_QUOTED.matcher(s).replaceAll("'?'");
    s = UUID.matcher(s).replaceAll("?");
    s = HEX_LITERAL.matcher(s).replaceAll("?");
    s = HEX_RUN.matcher(s).replaceAll("?");
    s = DIGITS.matcher(s).replaceAll("#");
    s = WHITESPACE.matcher(s).replaceAll(" ").strip();
    if (s.length() > MAX_PATTERN_CHARS) {
      int end = MAX_PATTERN_CHARS;
      if (Character.isHighSurrogate(s.charAt(end - 1))) {
        end--;
      }
      s = s.substring(0, end);
    }
    return s;
  }

  /**
   * The group id of an error with the given dictionary ids and already computed pattern ({@link
   * #pattern}).
   */
  public static long groupId(long errorClassId, String pattern, long topFrameId) {
    byte[] text = pattern.getBytes(StandardCharsets.UTF_8);
    ByteBuffer in = ByteBuffer.allocate(8 + text.length + 8);
    in.putLong(errorClassId);
    in.put(text);
    in.putLong(topFrameId);
    return Xxh3.hash64(in.array(), 0, in.capacity());
  }

  /**
   * The group id of a transaction, 0 when it has no error or no error identity at all (no class, no
   * message, no frame: an error status without an exception has no group).
   */
  public static long groupIdOf(boolean error, long errorClassId, String message, long topFrameId) {
    if (!error) {
      return 0;
    }
    String pattern = pattern(message);
    if (errorClassId == 0 && pattern.isEmpty() && topFrameId == 0) {
      return 0;
    }
    return groupId(errorClassId, pattern, topFrameId);
  }
}
