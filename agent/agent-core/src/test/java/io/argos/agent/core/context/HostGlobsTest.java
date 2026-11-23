/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.context;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Host globs of {@code propagation.exclude-hosts} and {@code argos-state.hosts} (SEC-38). */
@Tag("req:SEC-38")
class HostGlobsTest {
  @Test
  @DisplayName("T-SEC-38a no globs (the default) match nothing")
  void emptyMatchesNothing() {
    assertThat(HostGlobs.NONE.matches("127.0.0.1")).isFalse();
    assertThat(HostGlobs.of(null).isEmpty()).isTrue();
    assertThat(HostGlobs.of(Collections.<String>emptyList()).isEmpty()).isTrue();
    assertThat(HostGlobs.of(Arrays.asList(" ", null, "")).isEmpty()).isTrue();
  }

  @Test
  @DisplayName("T-SEC-38a * and ? ignore case and compare the host only")
  void wildcards() {
    HostGlobs globs = HostGlobs.of(Arrays.asList("*.internal.example", "api-?.example.com"));
    assertThat(globs.matches("orders.internal.example")).isTrue();
    assertThat(globs.matches("a.b.INTERNAL.example")).isTrue();
    assertThat(globs.matches("internal.example")).isFalse();
    assertThat(globs.matches("api-1.example.com")).isTrue();
    assertThat(globs.matches("api-12.example.com")).isFalse();
    assertThat(globs.matches("evil.example.com")).isFalse();
    assertThat(globs.matches(null)).isFalse();
  }

  @Test
  @DisplayName("T-SEC-38a exact hosts, IP addresses, IPv6 brackets and a trailing dot")
  void exactAndAddresses() {
    HostGlobs globs = HostGlobs.of(Arrays.asList("127.0.0.1", "[::1]", "Pay.Example.com"));
    assertThat(globs.matches("127.0.0.1")).isTrue();
    assertThat(globs.matches("127.0.0.10")).isFalse();
    assertThat(globs.matches("[::1]")).isTrue();
    assertThat(globs.matches("::1")).isTrue();
    assertThat(globs.matches("pay.example.com.")).isTrue();
    HostGlobs all = HostGlobs.of(Collections.singletonList("*"));
    assertThat(all.matches("anything.example")).isTrue();
  }

  @Test
  @DisplayName("T-SEC-38a at most 256 globs of at most 256 characters are kept")
  void bounds() {
    List<String> many = new ArrayList<String>();
    for (int i = 0; i < 300; i++) {
      many.add("h" + i + ".example");
    }
    HostGlobs globs = HostGlobs.of(many);
    assertThat(globs.size()).isEqualTo(HostGlobs.MAX_ITEMS);
    assertThat(globs.matches("h255.example")).isTrue();
    assertThat(globs.matches("h256.example")).isFalse();
    StringBuilder longGlob = new StringBuilder();
    while (longGlob.length() <= HostGlobs.MAX_GLOB_CHARS) {
      longGlob.append('a');
    }
    assertThat(HostGlobs.of(Collections.singletonList(longGlob.toString())).isEmpty()).isTrue();
  }

  @Test
  @Timeout(5)
  @DisplayName("T-SEC-38a matching stays linear-bounded on pathological globs (no backtracking)")
  void noBlowUp() {
    StringBuilder glob = new StringBuilder();
    for (int i = 0; i < 100; i++) {
      glob.append("*a");
    }
    glob.append("b");
    StringBuilder host = new StringBuilder();
    for (int i = 0; i < 250; i++) {
      host.append('a');
    }
    HostGlobs globs = HostGlobs.of(Collections.singletonList(glob.toString()));
    for (int i = 0; i < 1_000; i++) {
      assertThat(globs.matches(host.toString())).isFalse();
    }
  }
}
