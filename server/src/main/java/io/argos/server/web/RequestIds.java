/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.web;

import java.time.Clock;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * Request ids in the ULID layout (14 §2.6 {@code "request_id": "01J..."}): 26 characters of
 * Crockford base32, the first 10 the creation time in milliseconds (sortable), the last 16 random
 * (80 bits). Not a secret and not a security token; unique enough to find a request in the logs.
 */
public final class RequestIds {

  private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();

  /** Shape of an id produced here. */
  public static final Pattern FORMAT = Pattern.compile("[0-9A-HJKMNP-TV-Z]{26}");

  private final Clock clock;

  /** Ids stamped with the system clock. */
  public RequestIds() {
    this(Clock.systemUTC());
  }

  /** Ids stamped with {@code clock} (tests). */
  public RequestIds(Clock clock) {
    this.clock = clock;
  }

  /** A new id. */
  public String next() {
    ThreadLocalRandom random = ThreadLocalRandom.current();
    return format(clock.millis(), random.nextLong(), random.nextInt(1 << 16));
  }

  /** The id of a timestamp and 80 random bits (64 + 16). */
  static String format(long millis, long randomHigh, int randomLow) {
    char[] out = new char[26];
    long time = millis & 0xFFFF_FFFF_FFFFL; // 48 bits
    for (int i = 9; i >= 0; i--) {
      out[i] = ALPHABET[(int) (time & 31)];
      time >>>= 5;
    }
    // 80 random bits as 16 characters: 64 bits in the high part, 16 in the low part.
    long high = randomHigh;
    int low = randomLow & 0xFFFF;
    // Low 16 bits first (characters 22..25 take 20 bits: 16 low bits + 4 bits of high).
    long tail = ((high & 0xF) << 16) | low; // 20 bits
    for (int i = 25; i >= 22; i--) {
      out[i] = ALPHABET[(int) (tail & 31)];
      tail >>>= 5;
    }
    high >>>= 4; // 60 bits left
    for (int i = 21; i >= 10; i--) {
      out[i] = ALPHABET[(int) (high & 31)];
      high >>>= 5;
    }
    return new String(out);
  }
}
