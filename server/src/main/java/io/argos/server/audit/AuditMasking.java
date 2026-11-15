/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.audit;

import io.argos.server.config.logging.LogMasking;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Masks the {@code before} and {@code after} values of an audit record (12 §8.1 "마스킹", 14 §7.3: the
 * record is stored masked and returned as stored). A last line of defence: callers never put
 * secrets there in the first place.
 *
 * <ul>
 *   <li>a key whose name ends with a secret word ({@code password}, {@code passwd}, {@code pwd},
 *       {@code secret}, {@code token}, {@code credential(s)}, {@code hash}, {@code cookie}, {@code
 *       salt}, {@code jwt}, {@code key}; any case, separators {@code _ - .} ignored) has its value
 *       replaced by {@code ****}, unless the value is a boolean or a number (flags such as {@code
 *       must_change_password} stay readable);
 *   <li>every other string value passes {@link LogMasking#mask(String)} (agent tokens, bearer
 *       credentials, URL passwords, {@code key=value} secrets inside text);
 *   <li>nested maps and collections are masked the same way, at most {@value #MAX_DEPTH} levels
 *       deep (deeper values become {@code ****}).
 * </ul>
 */
public final class AuditMasking {

  /** The replacement of a masked value. */
  public static final String MASK = LogMasking.MASK;

  static final int MAX_DEPTH = 8;

  private static final List<String> SECRET_SUFFIXES =
      List.of(
          "password",
          "passwd",
          "pwd",
          "secret",
          "token",
          "credential",
          "credentials",
          "hash",
          "cookie",
          "salt",
          "jwt",
          "key");

  private AuditMasking() {}

  /** A masked copy of {@code values} (insertion order kept), or null for null. */
  public static Map<String, Object> mask(Map<String, ?> values) {
    return values == null ? null : maskMap(values, 0);
  }

  /** Whether a key names a secret. */
  static boolean secretKey(String key) {
    if (key == null) {
      return false;
    }
    String normalized =
        key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "").replace(".", "");
    for (String suffix : SECRET_SUFFIXES) {
      if (normalized.endsWith(suffix)) {
        return true;
      }
    }
    return false;
  }

  private static Map<String, Object> maskMap(Map<?, ?> values, int depth) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (Map.Entry<?, ?> e : values.entrySet()) {
      String key = String.valueOf(e.getKey());
      Object value = e.getValue();
      if (secretKey(key)
          && value != null
          && !(value instanceof Boolean)
          && !(value instanceof Number)) {
        out.put(key, MASK);
      } else {
        out.put(key, maskValue(value, depth + 1));
      }
    }
    return out;
  }

  private static Object maskValue(Object value, int depth) {
    if (value == null || value instanceof Boolean || value instanceof Number) {
      return value;
    }
    if (depth > MAX_DEPTH) {
      return MASK;
    }
    if (value instanceof Map<?, ?> map) {
      return maskMap(map, depth);
    }
    if (value instanceof Collection<?> collection) {
      List<Object> out = new ArrayList<>(collection.size());
      for (Object item : collection) {
        out.add(maskValue(item, depth + 1));
      }
      return out;
    }
    if (value instanceof Enum<?> e) {
      return e.name();
    }
    if (value instanceof char[] || value instanceof byte[]) {
      return MASK;
    }
    return LogMasking.mask(value.toString());
  }
}
