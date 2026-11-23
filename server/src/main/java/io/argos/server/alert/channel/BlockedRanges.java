/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert.channel;

import io.argos.server.config.CidrRange;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The address ranges a notification destination may not resolve to (16 §8.1 step 5, SEC-51), each
 * with the reason code of the 422 {@code webhook_destination_blocked} detail.
 *
 * <p>IPv4: {@code 0.0.0.0/8}, {@code 10.0.0.0/8}, {@code 100.64.0.0/10}, {@code 127.0.0.0/8},
 * {@code 169.254.0.0/16} (cloud metadata {@code 169.254.169.254} among them), {@code
 * 172.16.0.0/12}, {@code 192.0.0.0/24}, {@code 192.0.2.0/24}, {@code 192.168.0.0/16}, {@code
 * 198.18.0.0/15}, {@code 198.51.100.0/24}, {@code 203.0.113.0/24}, {@code 224.0.0.0/4}, {@code
 * 240.0.0.0/4}, {@code 255.255.255.255/32}. IPv6: {@code ::/128}, {@code ::1/128}, {@code
 * fc00::/7}, {@code fe80::/10}, {@code ff00::/8}.
 *
 * <p>IPv6 forms that carry an IPv4 address are checked against the IPv4 rules (16 §8.1 "매핑된 IPv4는
 * IPv4 규칙 재적용"): IPv4-mapped {@code ::ffff:0:0/96} (the JDK already turns those into {@link
 * Inet4Address}), and in addition the IPv4-compatible {@code ::/96}, NAT64 {@code 64:ff9b::/96} and
 * 6to4 {@code 2002::/16} prefixes, so that none of them reaches a blocked IPv4 range through a
 * translator.
 */
public final class BlockedRanges {

  /** A blocked range and its reason code. */
  record Blocked(CidrRange range, String reason) {}

  static final List<Blocked> RANGES =
      List.of(
          blocked("0.0.0.0/8", "this_network"),
          blocked("10.0.0.0/8", "private"),
          blocked("100.64.0.0/10", "shared_address"),
          blocked("127.0.0.0/8", "loopback"),
          blocked("169.254.0.0/16", "link_local"),
          blocked("172.16.0.0/12", "private"),
          blocked("192.0.0.0/24", "protocol_assignment"),
          blocked("192.0.2.0/24", "documentation"),
          blocked("192.168.0.0/16", "private"),
          blocked("198.18.0.0/15", "benchmarking"),
          blocked("198.51.100.0/24", "documentation"),
          blocked("203.0.113.0/24", "documentation"),
          blocked("224.0.0.0/4", "multicast"),
          blocked("255.255.255.255/32", "broadcast"),
          blocked("240.0.0.0/4", "reserved"),
          blocked("::/128", "unspecified"),
          blocked("::1/128", "loopback"),
          blocked("fc00::/7", "unique_local"),
          blocked("fe80::/10", "link_local"),
          blocked("ff00::/8", "multicast"));

  private static final byte[] NAT64 = {0x00, 0x64, (byte) 0xff, (byte) 0x9b};

  private BlockedRanges() {}

  /**
   * The reason {@code address} is blocked, or empty when it is a public address. An IPv6 address
   * that embeds an IPv4 address is blocked when either is.
   */
  public static Optional<String> reasonFor(InetAddress address) {
    for (Blocked b : RANGES) {
      if (b.range().contains(address)) {
        return Optional.of(b.reason());
      }
    }
    if (address instanceof Inet6Address) {
      Optional<Inet4Address> embedded = embeddedIpv4(address.getAddress());
      if (embedded.isPresent()) {
        return reasonFor(embedded.get());
      }
    }
    return Optional.empty();
  }

  /**
   * The IPv4 address inside an IPv6 address of the IPv4-mapped, IPv4-compatible, NAT64 or 6to4
   * form.
   */
  static Optional<Inet4Address> embeddedIpv4(byte[] v6) {
    if (v6.length != 16) {
      return Optional.empty();
    }
    boolean firstTenZero = allZero(v6, 0, 10);
    boolean mapped = firstTenZero && v6[10] == (byte) 0xff && v6[11] == (byte) 0xff;
    boolean compatible = firstTenZero && v6[10] == 0 && v6[11] == 0;
    boolean nat64 = Arrays.equals(v6, 0, 4, NAT64, 0, 4) && allZero(v6, 4, 12);
    if (mapped || compatible || nat64) {
      return Optional.of(v4(v6, 12));
    }
    if (v6[0] == 0x20 && v6[1] == 0x02) {
      return Optional.of(v4(v6, 2));
    }
    return Optional.empty();
  }

  private static boolean allZero(byte[] bytes, int from, int to) {
    for (int i = from; i < to; i++) {
      if (bytes[i] != 0) {
        return false;
      }
    }
    return true;
  }

  private static Inet4Address v4(byte[] v6, int offset) {
    try {
      return (Inet4Address) InetAddress.getByAddress(Arrays.copyOfRange(v6, offset, offset + 4));
    } catch (UnknownHostException e) {
      throw new IllegalStateException(e);
    }
  }

  private static Blocked blocked(String cidr, String reason) {
    return new Blocked(CidrRange.parse(cidr), reason);
  }
}
