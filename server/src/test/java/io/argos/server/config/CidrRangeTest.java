/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Address ranges of {@code argos.metrics.allow-cidrs} and {@code alert.webhook.allow-cidrs}. */
@Tag("req:SEC-55")
class CidrRangeTest {

  private static InetAddress ip(String literal) {
    return CidrRange.literal(literal).orElseThrow();
  }

  @Test
  @DisplayName("IPv4 and IPv6 ranges contain their addresses only")
  void contains() {
    CidrRange ten = CidrRange.parse("10.0.0.0/8");
    assertThat(ten.contains(ip("10.255.1.2"))).isTrue();
    assertThat(ten.contains(ip("11.0.0.1"))).isFalse();
    assertThat(ten.contains(ip("::1"))).isFalse();
    CidrRange one = CidrRange.parse("127.0.0.1");
    assertThat(one.prefixLength()).isEqualTo(32);
    assertThat(one.contains(ip("127.0.0.1"))).isTrue();
    assertThat(one.contains(ip("127.0.0.2"))).isFalse();
    CidrRange v6 = CidrRange.parse("fd00::/8");
    assertThat(v6.contains(ip("fd12:3456::1"))).isTrue();
    assertThat(v6.contains(ip("fe80::1"))).isFalse();
    assertThat(CidrRange.parse("::1/128").contains(ip("0:0:0:0:0:0:0:1"))).isTrue();
    assertThat(CidrRange.parse("0.0.0.0/0").contains(ip("203.0.113.9"))).isTrue();
    assertThat(CidrRange.parse("192.168.1.77/24").toString()).isEqualTo("192.168.1.0/24");
  }

  @Test
  @DisplayName("host names, bad prefixes and malformed addresses are rejected without DNS")
  void rejects() {
    for (String bad :
        new String[] {
          "localhost",
          "argos.example.test",
          "10.0.0.0/33",
          "::/129",
          "256.1.1.1",
          "1.2.3",
          "10.0.0.0/x",
          ""
        }) {
      assertThatThrownBy(() -> CidrRange.parse(bad))
          .as(bad)
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(CidrRange.literal("example.test")).isEmpty();
    assertThat(CidrRange.literal("[::1]")).isPresent();
  }
}
