/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.trace;

/**
 * Removes credentials and parameters from a JDBC URL before it becomes a dictionary text (SEC-35):
 * everything from the first {@code ?} or {@code ;}, and a {@code user[:password]@} or {@code
 * user/password@} part. The vendor-aware parser of 10 §6.3 ({@code JdbcUrlParser}) arrives with the
 * jdbc module (WO-13); this is the safe fallback.
 */
final class JdbcUrls {
  private JdbcUrls() {}

  static String withoutCredentials(String url) {
    String value = url;
    int cut = indexOfAny(value, '?', ';');
    if (cut >= 0) {
      value = value.substring(0, cut);
    }
    int at = value.lastIndexOf('@');
    if (at >= 0) {
      int slashes = value.indexOf("//");
      if (slashes >= 0 && slashes < at) {
        // jdbc:mysql://user:password@host/db
        value = value.substring(0, slashes + 2) + value.substring(at + 1);
      } else {
        // jdbc:oracle:thin:user/password@host:1521/svc
        int colon = value.lastIndexOf(':', at);
        if (colon >= 0) {
          value = value.substring(0, colon + 1) + "@" + value.substring(at + 1);
        }
      }
    }
    return value;
  }

  private static int indexOfAny(String value, char first, char second) {
    int a = value.indexOf(first);
    int b = value.indexOf(second);
    if (a < 0) {
      return b;
    }
    return b < 0 ? a : Math.min(a, b);
  }
}
