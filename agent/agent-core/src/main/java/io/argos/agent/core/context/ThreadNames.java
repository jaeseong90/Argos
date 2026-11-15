/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.context;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread names as dictionary texts ({@code thread_name_id}, 10 §8 "가상 스레드", DSN-10-13, C-12).
 *
 * <ul>
 *   <li>{@link #normalize(String)} removes the parts of a name that differ between threads of the
 *       same pool: UUIDs, hexadecimal runs of 8 or more characters, and trailing digits after a
 *       {@code -} ({@code tomcat-handler-1234} becomes {@code tomcat-handler-}). It works on the
 *       first {@value #MAX_NAME_CHARS} characters of a name and uses no regular expression (it runs
 *       when a thread begins its first transaction).
 *   <li>{@link #admit(String)} caps the distinct normalized names of one agent at {@code max} (10
 *       §8: 1,000); later new names become {@value #OTHER} and are counted ({@link
 *       #overflowCount()}, {@code argos_agent_dictionary_overflow_total{kind="thread"}}).
 * </ul>
 *
 * <p>Memory: at most {@code max} names of at most {@value #MAX_NAME_CHARS} characters (about 0.6 MB
 * at the default cap).
 */
public final class ThreadNames {
  /** Name of every thread name beyond the cap. */
  public static final String OTHER = "_other_";

  /** Distinct normalized names per agent (10 §8). */
  public static final int DEFAULT_MAX = 1_000;

  /** Characters of a raw name that are looked at; the rest is cut. */
  public static final int MAX_NAME_CHARS = 256;

  private static final int UUID_LENGTH = 36;
  private static final int MIN_HEX_RUN = 8;

  private final int max;
  private final ConcurrentHashMap<String, Boolean> names = new ConcurrentHashMap<String, Boolean>();
  private final AtomicInteger size = new AtomicInteger();
  private final AtomicLong overflow = new AtomicLong();

  /** A cap of {@code max} distinct names (at least 1). */
  public ThreadNames(int max) {
    this.max = Math.max(1, max);
  }

  /** The cap of distinct names. */
  public int max() {
    return max;
  }

  /**
   * {@code normalized} while the cap is not reached or the name is known, else {@value #OTHER}
   * (counted). Never blocks; at most {@link #max()} names are kept.
   */
  public String admit(String normalized) {
    if (normalized == null) {
      return null;
    }
    if (names.containsKey(normalized)) {
      return normalized;
    }
    while (true) {
      int current = size.get();
      if (current >= max) {
        overflow.incrementAndGet();
        return OTHER;
      }
      if (size.compareAndSet(current, current + 1)) {
        break;
      }
    }
    if (names.putIfAbsent(normalized, Boolean.TRUE) != null) {
      size.decrementAndGet(); // another thread added the same name
    }
    return normalized;
  }

  /** Distinct names admitted so far. */
  public int size() {
    return size.get();
  }

  /** Names mapped to {@value #OTHER} because the cap was reached. */
  public long overflowCount() {
    return overflow.get();
  }

  /**
   * The pool-independent form of {@code name}: the first {@value #MAX_NAME_CHARS} characters
   * without UUIDs ({@code 8-4-4-4-12} hex digits), without runs of 8 or more hexadecimal digits
   * that stand alone (bounded by the start, the end or a character that is not a letter or digit)
   * and without the digits that end the name after a {@code -}. Null becomes the empty string.
   */
  public static String normalize(String name) {
    if (name == null) {
      return "";
    }
    String text = name.length() > MAX_NAME_CHARS ? name.substring(0, MAX_NAME_CHARS) : name;
    StringBuilder out = null;
    int length = text.length();
    int i = 0;
    while (i < length) {
      int skip = 0;
      if (startsToken(text, i)) {
        if (isUuid(text, i)) {
          skip = UUID_LENGTH;
        } else {
          int run = hexRun(text, i);
          if (run >= MIN_HEX_RUN && endsToken(text, i + run)) {
            skip = run;
          }
        }
      }
      if (skip > 0) {
        if (out == null) {
          out = new StringBuilder(length);
          out.append(text, 0, i);
        }
        i += skip;
        continue;
      }
      if (out != null) {
        out.append(text.charAt(i));
      }
      i++;
    }
    String result = out == null ? text : out.toString();
    int end = result.length();
    while (end > 0 && isDigit(result.charAt(end - 1))) {
      end--;
    }
    if (end < result.length() && end > 0 && result.charAt(end - 1) == '-') {
      return result.substring(0, end);
    }
    return result;
  }

  private static boolean startsToken(String text, int index) {
    return index == 0 || !Character.isLetterOrDigit(text.charAt(index - 1));
  }

  private static boolean endsToken(String text, int index) {
    return index >= text.length() || !Character.isLetterOrDigit(text.charAt(index));
  }

  private static boolean isUuid(String text, int start) {
    if (start + UUID_LENGTH > text.length() || !endsToken(text, start + UUID_LENGTH)) {
      return false;
    }
    for (int k = 0; k < UUID_LENGTH; k++) {
      char c = text.charAt(start + k);
      boolean dash = k == 8 || k == 13 || k == 18 || k == 23;
      if (dash ? c != '-' : !isHex(c)) {
        return false;
      }
    }
    return true;
  }

  private static int hexRun(String text, int start) {
    int end = start;
    while (end < text.length() && isHex(text.charAt(end))) {
      end++;
    }
    return end - start;
  }

  private static boolean isHex(char c) {
    return isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
  }

  private static boolean isDigit(char c) {
    return c >= '0' && c <= '9';
  }
}
