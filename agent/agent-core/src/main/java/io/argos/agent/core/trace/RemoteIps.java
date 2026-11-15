/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.trace;

import io.argos.agent.bootstrap.CaptureConfig;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code capture.remote-ip} (SEC-31, 10 §15): {@code full} keeps the address, {@code masked} (the
 * default) zeroes the last IPv4 octet or the low 80 bits of an IPv6 address, {@code off} drops it.
 * Addresses are parsed as text only (never resolved); text that is not an IP literal is dropped in
 * {@code masked} mode so that nothing unmasked leaks.
 *
 * <p>The client address ({@link #client}, 16 §6.2, SEC-37): the socket address unless {@code
 * capture.trusted-proxies} is set. With trusted proxies the hops are read from the right: the
 * socket peer first, then the {@code X-Forwarded-For} entries from right to left; trusted hops are
 * dropped and the first untrusted address is the client. A peer that is not a trusted proxy is the
 * client itself, so a client that connects directly cannot choose its address with a forged header
 * (SEC-37 "스푸핑 XFF → 소켓 IP"). A header with an entry that is not an IP literal, or an empty header,
 * gives the socket address; a chain of trusted hops only gives its leftmost entry.
 *
 * <p>At most 32 entries ({@code MAX_FORWARDED_HOPS}) are read, the rightmost ones (the hops that
 * the trusted proxies appended). When all 32 are trusted, the client is the leftmost entry read
 * (the 32nd from the right, a trusted proxy address): entries further left, which the sender of the
 * request may have forged, are never used. The servlet helper hands over the last 32 {@code
 * X-Forwarded-For} header lines, cut on the left at an entry boundary to 2,048 characters, so the
 * rightmost entries always arrive here.
 */
public final class RemoteIps {
  /** {@code X-Forwarded-For} entries read at most (from the right). */
  static final int MAX_FORWARDED_HOPS = 32;

  private RemoteIps() {}

  /**
   * The client address of a request (class comment): {@code socket} when {@code trusted} is empty,
   * else the first untrusted hop from the right of {@code socket} preceded by {@code forwardedFor}.
   */
  public static String client(String socket, String forwardedFor, Trusted trusted) {
    if (socket == null || trusted == null || trusted.isEmpty()) {
      return socket;
    }
    byte[] peer = bytes(socket);
    if (peer == null || !trusted.contains(peer)) {
      return socket;
    }
    if (forwardedFor == null) {
      return socket;
    }
    String[] hops = forwardedFor.split(",", -1);
    String leftmost = null;
    int read = 0;
    for (int i = hops.length - 1; i >= 0 && read < MAX_FORWARDED_HOPS; i--, read++) {
      String hop = stripPort(hops[i].trim());
      byte[] address = bytes(hop);
      if (address == null) {
        return socket; // 16 §6.2: parse failure or empty value -> socket address
      }
      if (!trusted.contains(address)) {
        return hop;
      }
      leftmost = hop;
    }
    return leftmost == null ? socket : leftmost;
  }

  /**
   * An IPv4 {@code a.b.c.d:port} or a bracketed IPv6 {@code [x::y]:port} without the port; other
   * text unchanged (an unbracketed IPv6 literal has colons of its own).
   */
  static String stripPort(String hop) {
    if (hop.startsWith("[")) {
      int close = hop.indexOf(']');
      return close > 0 ? hop.substring(1, close) : hop;
    }
    int colon = hop.indexOf(':');
    if (colon > 0 && hop.indexOf(':', colon + 1) < 0 && hop.indexOf('.') >= 0) {
      return hop.substring(0, colon);
    }
    return hop;
  }

  /**
   * {@code capture.trusted-proxies} compiled once (immutable): CIDRs {@code 10.0.0.0/8}, {@code
   * 2001:db8::/32} or single addresses. Entries that do not parse are skipped (the configuration
   * validates the format before).
   */
  public static final class Trusted {
    /** No trusted proxy: {@code X-Forwarded-For} is ignored. */
    public static final Trusted NONE = new Trusted(null, new byte[0][], new int[0]);

    private final String[] source;
    private final byte[][] networks;
    private final int[] prefixes;

    private Trusted(String[] source, byte[][] networks, int[] prefixes) {
      this.source = source;
      this.networks = networks;
      this.prefixes = prefixes;
    }

    /** Compiles {@code cidrs} (null or empty: {@link #NONE}). */
    public static Trusted of(String[] cidrs) {
      if (cidrs == null || cidrs.length == 0) {
        return NONE;
      }
      List<byte[]> networks = new ArrayList<byte[]>();
      List<Integer> prefixes = new ArrayList<Integer>();
      for (String cidr : cidrs) {
        if (cidr == null) {
          continue;
        }
        String value = cidr.trim();
        int slash = value.indexOf('/');
        byte[] network = bytes(slash < 0 ? value : value.substring(0, slash));
        if (network == null) {
          continue;
        }
        int bits = network.length * 8;
        int prefix = bits;
        if (slash >= 0) {
          try {
            prefix = Integer.parseInt(value.substring(slash + 1).trim());
          } catch (NumberFormatException e) {
            continue;
          }
          if (prefix < 0 || prefix > bits) {
            continue;
          }
        }
        networks.add(network);
        prefixes.add(prefix);
      }
      int[] prefixArray = new int[prefixes.size()];
      for (int i = 0; i < prefixArray.length; i++) {
        prefixArray[i] = prefixes.get(i);
      }
      return new Trusted(cidrs, networks.toArray(new byte[0][]), prefixArray);
    }

    /** True when this was compiled from exactly {@code cidrs} (the same array instance). */
    @SuppressWarnings("ReferenceEquality") // CaptureConfig shares one array per snapshot
    public boolean compiledFrom(String[] cidrs) {
      return source == cidrs || (source == null && (cidrs == null || cidrs.length == 0));
    }

    public boolean isEmpty() {
      return networks.length == 0;
    }

    /** True when {@code address} (4 or 16 bytes) is in one of the networks. */
    boolean contains(byte[] address) {
      byte[] candidate = address;
      for (int i = 0; i < networks.length; i++) {
        byte[] network = networks[i];
        if (candidate.length != network.length) {
          byte[] mapped = candidate.length == 16 ? ipv4Mapped(candidate) : null;
          if (mapped == null || mapped.length != network.length) {
            continue;
          }
          if (inNetwork(mapped, network, prefixes[i])) {
            return true;
          }
          continue;
        }
        if (inNetwork(candidate, network, prefixes[i])) {
          return true;
        }
      }
      return false;
    }

    private static boolean inNetwork(byte[] address, byte[] network, int prefix) {
      int full = prefix / 8;
      for (int i = 0; i < full; i++) {
        if (address[i] != network[i]) {
          return false;
        }
      }
      int rest = prefix % 8;
      if (rest == 0) {
        return true;
      }
      int mask = (0xff << (8 - rest)) & 0xff;
      return (address[full] & mask) == (network[full] & mask);
    }

    /** The IPv4 address of an IPv4-mapped IPv6 address ({@code ::ffff:a.b.c.d}), else null. */
    private static byte[] ipv4Mapped(byte[] address) {
      for (int i = 0; i < 10; i++) {
        if (address[i] != 0) {
          return null;
        }
      }
      if (address[10] != (byte) 0xff || address[11] != (byte) 0xff) {
        return null;
      }
      return new byte[] {address[12], address[13], address[14], address[15]};
    }
  }

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

  /**
   * The bytes of an address literal for {@code TransactionRecord.remote_ip} (11 §4.3): 4 for IPv4,
   * 16 for IPv6 (brackets and a zone id are ignored), null for anything else. Never resolves a name
   * (no DNS, DSN-02-22).
   */
  public static byte[] bytes(String address) {
    if (address == null) {
      return null;
    }
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
      if (v4 == null) {
        return null;
      }
      return new byte[] {(byte) v4[0], (byte) v4[1], (byte) v4[2], (byte) v4[3]};
    }
    int[] groups = parseIpv6(value);
    if (groups == null) {
      return null;
    }
    byte[] out = new byte[16];
    for (int i = 0; i < 8; i++) {
      out[2 * i] = (byte) (groups[i] >> 8);
      out[2 * i + 1] = (byte) groups[i];
    }
    return out;
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
