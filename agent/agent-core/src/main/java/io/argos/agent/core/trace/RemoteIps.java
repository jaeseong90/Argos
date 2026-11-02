/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.trace;

import io.argos.agent.bootstrap.CaptureConfig;

/**
 * {@code capture.remote-ip} (SEC-31, 10 §15): {@code full} keeps the address, {@code masked} (the
 * default) zeroes the last IPv4 octet or the low 80 bits of an IPv6 address, {@code off} drops it.
 * Addresses are parsed as text only (never resolved); text that is not an IP literal is dropped in
 * {@code masked} mode so that nothing unmasked leaks.
 */
public final class RemoteIps {
  private RemoteIps() {}

  /** The address to record for {@code mode} ({@link CaptureConfig#REMOTE_IP_MASKED}, ...). */
  public static String apply(String address, int mode) {
    if (address == null || mode == CaptureConfig.REMOTE_IP_OFF) {
      return null;
    }
    if (mode == CaptureConfig.REMOTE_IP_FULL) {
      return address;
    }
    return mask(address);
  }

  /** {@code 192.0.2.17} -> {@code 192.0.2.0}; {@code 2001:db8:1:2::7} -> {@code 2001:db8:1::}. */
  public static String mask(String address) {
    String value = address.trim();
    if (value.startsWith("[") && value.endsWith("]")) {
      value = value.substring(1, value.length() - 1);
    }
    int zone = value.indexOf('%');
    if (zone >= 0) {
      value = value.substring(0, zone);
    }
    if (value.indexOf(':') < 0) {
      int[] v4 = parseIpv4(value);
      return v4 == null ? null : v4[0] + "." + v4[1] + "." + v4[2] + ".0";
    }
    int[] groups = parseIpv6(value);
    if (groups == null) {
      return null;
    }
    if (isIpv4Mapped(groups)) {
      return (groups[6] >> 8) + "." + (groups[6] & 0xff) + "." + (groups[7] >> 8) + ".0";
    }
    // Keep the high 48 bits (three groups); the low 80 bits are zero.
    return Integer.toHexString(groups[0])
        + ":"
        + Integer.toHexString(groups[1])
        + ":"
        + Integer.toHexString(groups[2])
        + "::";
  }

  static int[] parseIpv4(String value) {
    String[] parts = value.split("\\.", -1);
    if (parts.length != 4) {
      return null;
    }
    int[] octets = new int[4];
    for (int i = 0; i < 4; i++) {
      int octet = parseNumber(parts[i], 10, 3);
      if (octet < 0 || octet > 255) {
        return null;
      }
      octets[i] = octet;
    }
    return octets;
  }

  /** Eight 16-bit groups, or null when {@code value} is not an IPv6 literal. */
  static int[] parseIpv6(String value) {
    int[] groups = new int[8];
    String head = value;
    String tail = "";
    int gap = value.indexOf("::");
    if (gap >= 0) {
      if (value.indexOf("::", gap + 1) >= 0) {
        return null;
      }
      head = value.substring(0, gap);
      tail = value.substring(gap + 2);
    }
    int[] headGroups = groups(head);
    int[] tailGroups = groups(tail);
    if (headGroups == null || tailGroups == null) {
      return null;
    }
    int total = headGroups.length + tailGroups.length;
    if (gap < 0 ? total != 8 : total > 7) {
      return null;
    }
    System.arraycopy(headGroups, 0, groups, 0, headGroups.length);
    System.arraycopy(tailGroups, 0, groups, 8 - tailGroups.length, tailGroups.length);
    return groups;
  }

  /** Groups of one side of {@code ::}; an embedded IPv4 address at the end counts as two. */
  private static int[] groups(String part) {
    if (part.isEmpty()) {
      return new int[0];
    }
    String[] items = part.split(":", -1);
    int last = items.length - 1;
    boolean v4 = items[last].indexOf('.') >= 0;
    int[] out = new int[items.length + (v4 ? 1 : 0)];
    for (int i = 0; i < items.length; i++) {
      if (i == last && v4) {
        int[] octets = parseIpv4(items[i]);
        if (octets == null) {
          return null;
        }
        out[i] = (octets[0] << 8) | octets[1];
        out[i + 1] = (octets[2] << 8) | octets[3];
      } else {
        int group = parseNumber(items[i], 16, 4);
        if (group < 0) {
          return null;
        }
        out[i] = group;
      }
    }
    return out;
  }

  private static boolean isIpv4Mapped(int[] groups) {
    for (int i = 0; i < 5; i++) {
      if (groups[i] != 0) {
        return false;
      }
    }
    return groups[5] == 0xffff;
  }

  /** A non-negative number of at most {@code maxDigits} digits in {@code radix}, else -1. */
  private static int parseNumber(String text, int radix, int maxDigits) {
    if (text.isEmpty() || text.length() > maxDigits) {
      return -1;
    }
    int value = 0;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      int digit;
      if (c >= '0' && c <= '9') {
        digit = c - '0';
      } else if (radix == 16 && c >= 'a' && c <= 'f') {
        digit = c - 'a' + 10;
      } else if (radix == 16 && c >= 'A' && c <= 'F') {
        digit = c - 'A' + 10;
      } else {
        return -1;
      }
      value = value * radix + digit;
    }
    return value;
  }
}
