/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.config;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * An address range in CIDR notation ({@code 10.0.0.0/8}, {@code 127.0.0.1/32}, {@code fd00::/8}); a
 * single address without a prefix length is a range of one. Only IP literals are accepted: a host
 * name is rejected, never resolved (no DNS lookups from configuration).
 *
 * @param network the network address (host bits cleared)
 * @param prefixLength the prefix length in bits
 */
public record CidrRange(InetAddress network, int prefixLength) {

  private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
  private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:.]*:[0-9A-Fa-f:.]*");

  /**
   * Parses a range.
   *
   * @throws IllegalArgumentException with an operator-facing reason
   */
  public static CidrRange parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("empty address range");
    }
    String value = text.strip();
    int slash = value.indexOf('/');
    String address = slash < 0 ? value : value.substring(0, slash);
    InetAddress parsed =
        literal(address)
            .orElseThrow(() -> new IllegalArgumentException("not an IP address range: " + value));
    int bits = parsed.getAddress().length * 8;
    int prefix = bits;
    if (slash >= 0) {
      String length = value.substring(slash + 1);
      if (!length.matches("\\d{1,3}")) {
        throw new IllegalArgumentException("bad prefix length in " + value);
      }
      prefix = Integer.parseInt(length);
      if (prefix > bits) {
        throw new IllegalArgumentException("prefix length above " + bits + " in " + value);
      }
    }
    return new CidrRange(mask(parsed, prefix), prefix);
  }

  /** Whether {@code address} lies in this range (an IPv4 address never matches an IPv6 range). */
  public boolean contains(InetAddress address) {
    byte[] a = address.getAddress();
    byte[] n = network.getAddress();
    if (a.length != n.length) {
      return false;
    }
    return Arrays.equals(mask(address, prefixLength).getAddress(), n);
  }

  /** An IP literal, never a DNS lookup. */
  public static Optional<InetAddress> literal(String text) {
    if (text == null) {
      return Optional.empty();
    }
    String value = text.strip();
    if (value.startsWith("[") && value.endsWith("]")) {
      value = value.substring(1, value.length() - 1);
    }
    boolean v4 = IPV4.matcher(value).matches();
    boolean v6 = !v4 && IPV6.matcher(value).matches();
    if (!v4 && !v6) {
      return Optional.empty();
    }
    if (v4) {
      for (String part : value.split("\\.", -1)) {
        if (Integer.parseInt(part) > 255) {
          return Optional.empty();
        }
      }
    }
    try {
      // A literal: InetAddress parses it without a name service lookup.
      return Optional.of(InetAddress.getByName(value));
    } catch (UnknownHostException e) {
      return Optional.empty();
    }
  }

  private static InetAddress mask(InetAddress address, int prefix) {
    byte[] bytes = address.getAddress();
    for (int i = 0; i < bytes.length; i++) {
      int keep = Math.max(0, Math.min(8, prefix - i * 8));
      bytes[i] = (byte) (bytes[i] & (0xFF << (8 - keep)));
    }
    try {
      return InetAddress.getByAddress(bytes);
    } catch (UnknownHostException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public String toString() {
    return network.getHostAddress() + "/" + prefixLength;
  }
}
