/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.context;

import java.util.ArrayList;
import java.util.List;

/**
 * W3C {@code tracestate} handling (10 §8, 11 §4.1 rule 5, SEC-38) and the Argos entry {@code
 * argos=<app>:<inst>:<svc>}: the app id and the instance id as u32 and the service id as u64, each
 * in lower-case hex without leading zeros (about 40 characters at most; the {@code tracestate} key
 * {@code argos} is a public contract, 02 §11).
 *
 * <p>Input bounds: a header value longer than {@value #MAX_CHARS} characters, with more than
 * {@value #MAX_ENTRIES} list members, with a member that breaks the W3C key or value rules, or with
 * a key twice is dropped as a whole ({@link #sanitize} returns null): the agent never forwards a
 * value it cannot vouch for. Empty members (only optional white space between commas) are allowed
 * and removed.
 */
public final class TraceStateCodec {
  /** Characters of a {@code tracestate} value kept at most (11 §4.1 rule 5: 32 x (256 + 256)). */
  public static final int MAX_CHARS = 512;

  /** List members at most (W3C). */
  public static final int MAX_ENTRIES = 32;

  /** The key of the Argos entry. */
  public static final String ARGOS_KEY = "argos";

  private static final int MAX_KEY_CHARS = 256;
  private static final int MAX_VALUE_CHARS = 256;

  private TraceStateCodec() {}

  /**
   * The members of a valid {@code tracestate}, trimmed and joined by {@code ,}; null when {@code
   * raw} is null, empty after trimming or invalid (see the class comment).
   */
  public static String sanitize(String raw) {
    List<String> members = members(raw);
    return members == null || members.isEmpty() ? null : join(members);
  }

  /**
   * The app id of the {@code argos} entry of a {@link #sanitize sanitized} value; 0 when there is
   * no such entry, the value is not {@code <app>:<inst>:<svc>} in lower-case hex (at most 8, 8 and
   * 16 digits), or the app id is 0.
   */
  public static int callerAppId(String sanitized) {
    String value = argosValue(sanitized);
    if (value == null) {
      return 0;
    }
    int first = value.indexOf(':');
    int second = first < 0 ? -1 : value.indexOf(':', first + 1);
    if (first < 0 || second < 0 || value.indexOf(':', second + 1) >= 0) {
      return 0;
    }
    if (!validHex(value, 0, first, 8)
        || !validHex(value, first + 1, second, 8)
        || !validHex(value, second + 1, value.length(), 16)) {
      return 0;
    }
    long app = 0;
    for (int i = 0; i < first; i++) {
      app = (app << 4) | Propagation.digit(value.charAt(i));
    }
    return (int) app;
  }

  /** The {@code argos} list member for the ids (u32, u32, u64). */
  public static String argosEntry(long appId, long instanceId, long serviceId) {
    return ARGOS_KEY
        + "="
        + Long.toHexString(appId & 0xFFFF_FFFFL)
        + ":"
        + Long.toHexString(instanceId & 0xFFFF_FFFFL)
        + ":"
        + Long.toHexString(serviceId);
  }

  /**
   * {@code base} (sanitized or null) with its {@code argos} members removed and, when {@code
   * argosEntry} is not null, that entry first (W3C: a vendor that changes its entry moves it to the
   * front). Members are dropped from the right until there are at most {@value #MAX_ENTRIES} and
   * the value fits {@value #MAX_CHARS} characters. Null when nothing is left.
   */
  public static String outgoing(String base, String argosEntry) {
    List<String> members = members(base);
    List<String> out = new ArrayList<String>(MAX_ENTRIES);
    if (argosEntry != null) {
      out.add(argosEntry);
    }
    if (members != null) {
      for (String member : members) {
        if (!ARGOS_KEY.equals(key(member))) {
          out.add(member);
        }
      }
    }
    while (out.size() > MAX_ENTRIES) {
      out.remove(out.size() - 1);
    }
    String joined = out.isEmpty() ? null : join(out);
    while (joined != null && joined.length() > MAX_CHARS) {
      out.remove(out.size() - 1);
      joined = out.isEmpty() ? null : join(out);
    }
    return joined;
  }

  /** True when the sanitized value has an {@code argos} member. */
  public static boolean hasArgos(String sanitized) {
    return argosValue(sanitized) != null;
  }

  private static String argosValue(String sanitized) {
    List<String> members = members(sanitized);
    if (members == null) {
      return null;
    }
    for (String member : members) {
      if (ARGOS_KEY.equals(key(member))) {
        return member.substring(ARGOS_KEY.length() + 1);
      }
    }
    return null;
  }

  /** The valid members of {@code raw}, null when invalid, empty when there are none. */
  static List<String> members(String raw) {
    if (raw == null || raw.length() > MAX_CHARS) {
      return null;
    }
    List<String> out = new ArrayList<String>(4);
    int start = 0;
    int length = raw.length();
    while (start <= length) {
      int comma = raw.indexOf(',', start);
      int end = comma < 0 ? length : comma;
      String member = trimOws(raw, start, end);
      if (!member.isEmpty()) {
        if (out.size() >= MAX_ENTRIES || !validMember(member)) {
          return null;
        }
        String key = key(member);
        for (String seen : out) {
          if (key.equals(key(seen))) {
            return null;
          }
        }
        out.add(member);
      }
      if (comma < 0) {
        break;
      }
      start = comma + 1;
    }
    return out;
  }

  private static String trimOws(String s, int start, int end) {
    int a = start;
    int b = end;
    while (a < b && (s.charAt(a) == ' ' || s.charAt(a) == '\t')) {
      a++;
    }
    while (b > a && (s.charAt(b - 1) == ' ' || s.charAt(b - 1) == '\t')) {
      b--;
    }
    return s.substring(a, b);
  }

  private static String key(String member) {
    int eq = member.indexOf('=');
    return eq < 0 ? member : member.substring(0, eq);
  }

  /**
   * W3C list member: {@code key=value}. Key: {@code lcalpha 0*255(lcalpha / DIGIT / "_" / "-" / "*"
   * / "/")}, or {@code tenant@system} with a tenant of {@code (lcalpha / DIGIT)} plus up to 240
   * such characters and a system of {@code lcalpha} plus up to 13. Value: 1 to 256 printable ASCII
   * characters except {@code ,} and {@code =}, not ending with a space.
   */
  static boolean validMember(String member) {
    int eq = member.indexOf('=');
    if (eq <= 0 || eq > MAX_KEY_CHARS) {
      return false;
    }
    if (!validKey(member, eq)) {
      return false;
    }
    int valueLength = member.length() - eq - 1;
    if (valueLength < 1 || valueLength > MAX_VALUE_CHARS) {
      return false;
    }
    for (int i = eq + 1; i < member.length(); i++) {
      char c = member.charAt(i);
      if (c < 0x20 || c > 0x7e || c == ',' || c == '=') {
        return false;
      }
    }
    return member.charAt(member.length() - 1) != ' ';
  }

  private static boolean validKey(String member, int end) {
    int at = member.indexOf('@');
    if (at >= end) {
      at = -1;
    }
    if (at < 0) {
      if (!lcalpha(member.charAt(0))) {
        return false;
      }
      return keyChars(member, 1, end);
    }
    int tenant = at;
    int system = end - at - 1;
    if (tenant < 1 || tenant > 241 || system < 1 || system > 14) {
      return false;
    }
    char t0 = member.charAt(0);
    if (!(lcalpha(t0) || digit(t0)) || !keyChars(member, 1, at)) {
      return false;
    }
    return lcalpha(member.charAt(at + 1)) && keyChars(member, at + 2, end);
  }

  private static boolean keyChars(String s, int from, int to) {
    for (int i = from; i < to; i++) {
      char c = s.charAt(i);
      if (!(lcalpha(c) || digit(c) || c == '_' || c == '-' || c == '*' || c == '/')) {
        return false;
      }
    }
    return true;
  }

  private static boolean lcalpha(char c) {
    return c >= 'a' && c <= 'z';
  }

  private static boolean digit(char c) {
    return c >= '0' && c <= '9';
  }

  /** True for 1 to {@code maxDigits} lower-case hex digits in {@code [from, to)}. */
  private static boolean validHex(String s, int from, int to, int maxDigits) {
    int n = to - from;
    if (n < 1 || n > maxDigits) {
      return false;
    }
    for (int i = from; i < to; i++) {
      if (Propagation.digit(s.charAt(i)) < 0) {
        return false;
      }
    }
    return true;
  }

  private static String join(List<String> members) {
    StringBuilder out = new StringBuilder(64);
    for (int i = 0; i < members.size(); i++) {
      if (i > 0) {
        out.append(',');
      }
      out.append(members.get(i));
    }
    return out.toString();
  }
}
