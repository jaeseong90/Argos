/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert.channel;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.api.error.ErrorCode;
import io.argos.server.auth.AuthTestProperties;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Destination validation of 16 §8.1 without any name lookup leaving the machine: names resolve
 * through a fake resolver.
 */
@Tag("req:SEC-51")
class DestinationValidatorTest {

  private final Map<String, List<InetAddress>> dns = new HashMap<>();
  private final AtomicInteger lookups = new AtomicInteger();

  private DestinationValidator validator(Map<String, Object> config) {
    Map<String, Object> values = new HashMap<>(config);
    values.putIfAbsent("argos.web.public-url", "https://argos.example.test");
    return new DestinationValidator(
        AuthTestProperties.of(values),
        host -> {
          lookups.incrementAndGet();
          List<InetAddress> a = dns.get(host);
          if (a == null) {
            throw new UnknownHostException("no address");
          }
          return a;
        },
        Clock.systemUTC());
  }

  private static InetAddress ip(String literal) {
    try {
      return InetAddress.getByName(literal);
    } catch (UnknownHostException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String reason(DestinationValidator v, String url) {
    try {
      v.validate(url, false);
      return "allowed";
    } catch (DestinationBlockedException e) {
      assertThat(e.errorCode()).isEqualTo(ErrorCode.WEBHOOK_DESTINATION_BLOCKED);
      assertThat(e.errorCode().status()).isEqualTo(422);
      assertThat(e.getMessage()).doesNotContain(url);
      assertThat(e.details())
          .singleElement()
          .satisfies(d -> assertThat(d.field()).isEqualTo("target"));
      return e.reason();
    }
  }

  @ParameterizedTest(name = "T-SEC-51a {0} is blocked: {1}")
  @CsvSource({
    "https://0.0.0.0/hook, this_network",
    "https://10.1.2.3/hook, private",
    "https://100.64.0.1/hook, shared_address",
    "https://127.0.0.1:8443/hook, loopback",
    "https://169.254.169.254/latest/meta-data, link_local",
    "https://172.16.5.4/hook, private",
    "https://172.31.255.255/hook, private",
    "https://192.0.0.8/hook, protocol_assignment",
    "https://192.0.2.10/hook, documentation",
    "https://192.168.1.1/hook, private",
    "https://198.18.0.1/hook, benchmarking",
    "https://198.51.100.7/hook, documentation",
    "https://203.0.113.9/hook, documentation",
    "https://224.0.0.1/hook, multicast",
    "https://240.0.0.1/hook, reserved",
    "https://255.255.255.255/hook, broadcast",
    "https://[::]/hook, unspecified",
    "https://[::1]/hook, loopback",
    "https://[fc00::1]/hook, unique_local",
    "https://[fd12:3456::1]/hook, unique_local",
    "https://[fe80::1]/hook, link_local",
    "https://[ff02::1]/hook, multicast",
    "https://[::ffff:10.0.0.1]/hook, private",
    "https://[::ffff:169.254.169.254]/hook, link_local",
    "https://[64:ff9b::7f00:1]/hook, loopback",
    "https://[2002:c0a8:101::1]/hook, private"
  })
  void blockedRanges(String url, String expected) {
    assertThat(reason(validator(Map.of()), url)).isEqualTo(expected);
  }

  @Test
  @DisplayName("T-SEC-51a every 16 §8.1 range is in the table, the IPv4 and IPv6 ones included")
  void table() {
    List<String> ranges = new ArrayList<>();
    BlockedRanges.RANGES.forEach(b -> ranges.add(b.range().toString()));
    assertThat(ranges)
        .containsExactlyInAnyOrder(
            "0.0.0.0/8",
            "10.0.0.0/8",
            "100.64.0.0/10",
            "127.0.0.0/8",
            "169.254.0.0/16",
            "172.16.0.0/12",
            "192.0.0.0/24",
            "192.0.2.0/24",
            "192.168.0.0/16",
            "198.18.0.0/15",
            "198.51.100.0/24",
            "203.0.113.0/24",
            "224.0.0.0/4",
            "240.0.0.0/4",
            "255.255.255.255/32",
            "0:0:0:0:0:0:0:0/128",
            "0:0:0:0:0:0:0:1/128",
            "fc00:0:0:0:0:0:0:0/7",
            "fe80:0:0:0:0:0:0:0/10",
            "ff00:0:0:0:0:0:0:0/8");
  }

  @Test
  @DisplayName("T-SEC-51b http is refused by default, accepted only with allow-http and allow_http")
  void http() {
    dns.put("hooks.example.com", List.of(ip("93.184.216.34")));
    assertThat(reason(validator(Map.of()), "http://hooks.example.com/x")).isEqualTo("scheme");
    DestinationValidator allowing = validator(Map.of("argos.alert.webhook.allow-http", "true"));
    assertThat(reason(allowing, "http://hooks.example.com/x")).isEqualTo("scheme");
    assertThat(allowing.validate("http://hooks.example.com/x", true).https()).isFalse();
    assertThat(reason(validator(Map.of()), "ftp://hooks.example.com/x")).isEqualTo("scheme");
  }

  @ParameterizedTest(name = "T-SEC-51a {0} is refused: {1}")
  @CsvSource({
    "https://user:pass@hooks.example.com/x, userinfo",
    "https://hooks.example.com@10.0.0.1/x, userinfo",
    "https://2130706433/x, invalid_url",
    "https://0x7f.0.0.1/x, invalid_url",
    "https://127.1/x, invalid_url",
    "https://017700000001/x, invalid_url",
    "not a url, invalid_url",
    "https:///nohost, invalid_url",
    "https://unknown.example.net/x, dns"
  })
  void malformed(String url, String expected) {
    assertThat(reason(validator(Map.of()), url)).isEqualTo(expected);
  }

  @Test
  @DisplayName("T-SEC-51a a name is blocked when any of its A/AAAA addresses is blocked")
  void anyAddress() {
    dns.put("mixed.example.com", List.of(ip("93.184.216.34"), ip("10.0.0.5")));
    dns.put("v6.example.com", List.of(ip("93.184.216.34"), ip("::1")));
    assertThat(reason(validator(Map.of()), "https://mixed.example.com/h")).isEqualTo("private");
    assertThat(reason(validator(Map.of()), "https://v6.example.com/h")).isEqualTo("loopback");
  }

  @Test
  @DisplayName(
      "T-SEC-51 the connection is pinned to the first address that passed; every call resolves"
          + " again (DNS rebinding is caught at the next send)")
  void pinnedAndRebinding() {
    DestinationValidator v = validator(Map.of());
    dns.put("hooks.example.com", List.of(ip("93.184.216.34"), ip("93.184.216.35")));
    Destination d = v.validate("https://hooks.example.com:8443/services/x?y=1", false);
    assertThat(d.pinned()).isEqualTo(ip("93.184.216.34"));
    assertThat(d.host()).isEqualTo("hooks.example.com");
    assertThat(d.port()).isEqualTo(8443);
    assertThat(d.hostHeader()).isEqualTo("hooks.example.com:8443");
    assertThat(d.requestTarget()).isEqualTo("/services/x?y=1");
    assertThat(d.toString()).doesNotContain("services").doesNotContain("hooks.example.com");

    int before = lookups.get();
    dns.put("hooks.example.com", List.of(ip("169.254.169.254")));
    assertThat(reason(v, "https://hooks.example.com:8443/services/x?y=1")).isEqualTo("link_local");
    assertThat(lookups.get()).isGreaterThan(before);
  }

  @Test
  @DisplayName("T-SEC-51c argos.alert.webhook.allow-cidrs lets a closed network's range through")
  void allowCidrs() {
    DestinationValidator v =
        validator(
            Map.of(
                "argos.alert.webhook.allow-cidrs[0]", "10.20.0.0/16",
                "argos.alert.webhook.allow-cidrs[1]", "fd00::/8"));
    assertThat(v.validate("https://10.20.3.4/hook", false).pinned()).isEqualTo(ip("10.20.3.4"));
    assertThat(v.validate("https://[fd00::5]/hook", false).pinned()).isEqualTo(ip("fd00::5"));
    assertThat(reason(v, "https://10.21.0.1/hook")).isEqualTo("private");
    assertThat(reason(v, "https://169.254.169.254/latest")).isEqualTo("link_local");
  }

  @Test
  @DisplayName("T-SEC-51a an address of the public origin's host is refused as own_address")
  void ownAddress() {
    dns.put("argos.example.test", List.of(ip("93.184.216.99")));
    dns.put("alias.example.com", List.of(ip("93.184.216.99")));
    assertThat(reason(validator(Map.of()), "https://alias.example.com/x")).isEqualTo("own_address");
    assertThat(reason(validator(Map.of()), "https://93.184.216.99/x")).isEqualTo("own_address");
  }

  @Test
  @DisplayName("T-SEC-51 a public https destination passes")
  void allowed() {
    dns.put("hooks.slack.example", List.of(ip("93.184.216.34")));
    Destination d =
        validator(Map.of()).validate("https://hooks.slack.example/services/T/B/C", false);
    assertThat(d.https()).isTrue();
    assertThat(d.port()).isEqualTo(443);
    assertThat(d.hostHeader()).isEqualTo("hooks.slack.example");
  }
}
