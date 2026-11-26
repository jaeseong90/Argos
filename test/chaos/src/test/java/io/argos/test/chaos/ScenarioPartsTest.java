/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.chaos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The parts of T-CH-004~008 that need no Docker (proposed T-CH-020j~o). */
@DisplayName("chaos scenarios T-CH-004~008 parts")
class ScenarioPartsTest {

  @TempDir Path dir;

  @Test
  @DisplayName(
      "T-CH-020j T-CH-004 fault file phases: UNAVAILABLE, INTERNAL, RESOURCE_EXHAUSTED, QUOTA in"
          + " equal parts")
  void serverErrorPhases() {
    assertThat(ServerErrorFault.PHASES)
        .containsExactly("UNAVAILABLE", "INTERNAL", "RESOURCE_EXHAUSTED", "QUOTA:0.5");
    Instant start = Instant.parse("2026-11-26T10:00:00Z");
    Duration length = Duration.ofMinutes(2);
    assertThat(ServerErrorFault.phaseStart(start, length, 0, 4)).isEqualTo(start);
    assertThat(ServerErrorFault.phaseStart(start, length, 1, 4)).isEqualTo(start.plusSeconds(30));
    assertThat(ServerErrorFault.phaseStart(start, length, 3, 4)).isEqualTo(start.plusSeconds(90));
  }

  private static String line(String time, String level, String message) {
    return time + " " + level + " [argos-sender] " + message;
  }

  @Test
  @DisplayName(
      "T-CH-020k T-CH-005 stderr fallback: agent records only, one-minute windows from the first"
          + " line, cap 10")
  void stderrFallback() {
    StringBuilder text = new StringBuilder("OpenJDK 64-Bit Server VM warning: not an agent line\n");
    for (int i = 0; i < 10; i++) {
      text.append(
              line("2026-11-26T10:00:" + String.format("%02d", i) + ".000+09:00", "WARN", "w" + i))
          .append('\n');
    }
    text.append(line("2026-11-26T10:01:05.000+09:00", "ERROR", "e1")).append('\n');
    text.append(line("2026-11-26T10:01:06.000+09:00", "INFO", "i1")).append('\n');
    StderrFallback f = StderrFallback.parse(text.toString());
    assertThat(f.lines()).hasSize(12);
    assertThat(f.perMinute()).containsExactly(10, 2);
    assertThat(f.maxPerMinute()).isEqualTo(10);
    assertThat(f.withinCap()).isTrue();
    assertThat(f.since(Instant.parse("2026-11-26T01:01:00Z"))).hasSize(2);

    StringBuilder flood = new StringBuilder();
    for (int i = 0; i < 11; i++) {
      flood
          .append(line("2026-11-26T10:00:" + String.format("%02d", i) + ".000Z", "WARN", "w" + i))
          .append('\n');
    }
    assertThat(StderrFallback.parse(flood.toString()).withinCap()).isFalse();
    assertThat(StderrFallback.parse("").maxPerMinute()).isZero();
  }

  @Test
  @DisplayName(
      "T-CH-020l T-CH-006/007 hosts file: base entries, names added and removed, JVM options")
  void hostsFile() throws Exception {
    HostsFile hosts = new HostsFile(dir.resolve("h").resolve("hosts"));
    String text = Files.readString(hosts.file(), StandardCharsets.UTF_8);
    assertThat(text).contains("127.0.0.1 localhost");
    assertThat(hosts.contains(ChaosStack.COLLECTOR_NAME)).isFalse();
    hosts.put(ChaosStack.COLLECTOR_NAME, "127.0.0.1");
    assertThat(Files.readString(hosts.file(), StandardCharsets.UTF_8))
        .contains("127.0.0.1 " + ChaosStack.COLLECTOR_NAME);
    hosts.remove(ChaosStack.COLLECTOR_NAME);
    assertThat(Files.readString(hosts.file(), StandardCharsets.UTF_8))
        .doesNotContain(ChaosStack.COLLECTOR_NAME)
        .contains("localhost");
    assertThat(hosts.jvmOptions())
        .containsExactly(
            "-Djdk.net.hosts.file=" + hosts.file(),
            "-Dsun.net.inetaddr.ttl=1",
            "-Dsun.net.inetaddr.negative.ttl=1");
    assertThatThrownBy(() -> hosts.put("bad name; rm", "127.0.0.1"))
        .isInstanceOf(IllegalArgumentException.class);
    try (var files = Files.list(hosts.file().getParent())) {
      assertThat(files.toList()).as("no temporary file left").containsExactly(hosts.file());
    }
  }

  @Test
  @DisplayName(
      "T-CH-020m T-CH-008 XLog API instances: points without a dictionary name are unknown")
  void unknownInstances() {
    InstanceView reused =
        InstanceView.parse(
            "{\"instance_id\":[1,1,1],\"dictionary\":{\"instance\":{\"1\":\"chaos-1\"},"
                + "\"service\":{}},\"tx_id\":[\"1\",\"2\",\"3\"]}");
    assertThat(reused.unknown()).isEmpty();
    assertThat(reused.allUnknown()).isFalse();
    InstanceView unknown =
        InstanceView.parse(
            "{\"instance_id\":[1,2],\"dictionary\":{\"instance\":{\"3\":\"chaos-1\"},"
                + "\"service\":{}}}");
    assertThat(unknown.unknown()).containsExactly(1L, 2L);
    assertThat(unknown.allUnknown()).isTrue();
    InstanceView empty =
        InstanceView.parse("{\"instance_id\":[],\"dictionary\":{\"instance\":{}}}");
    assertThat(empty.allUnknown()).as("no earlier points: nothing proven").isFalse();
  }

  @Test
  @DisplayName(
      "T-CH-020n T-CH-007 WARN classification: connect failures naming TLS; skip-verify WARN text")
  void tlsWarnings() {
    assertThat(
            TlsMismatchFault.tlsFailure(
                "collector connection failed (UNAVAILABLE, java.security.cert.CertificateException);"
                    + " the agent keeps retrying with back-off"))
        .isTrue();
    assertThat(
            TlsMismatchFault.tlsFailure(
                "collector connection failed (UNAVAILABLE, javax.net.ssl.SSLHandshakeException);"
                    + " the agent keeps retrying with back-off"))
        .isTrue();
    assertThat(
            TlsMismatchFault.tlsFailure(
                "collector connection failed (UNAVAILABLE, java.net.ConnectException); the agent"
                    + " keeps retrying with back-off"))
        .isFalse();
    assertThat(TlsMismatchFault.SKIP_VERIFY_WARN).isEqualTo("TLS verification disabled");
    assertThat(DnsFailureFault.CONNECT_FAILED).isEqualTo(TlsMismatchFault.CONNECT_FAILED);
  }

  @Test
  @DisplayName("T-CH-020o toxiproxy enable/disable body; scenario order and out-of-scope IDs")
  void toxiproxyEnabledAndIds() {
    assertThat(ToxiproxyClient.enabledJson(false)).isEqualTo("{\"enabled\":false}");
    assertThat(ToxiproxyClient.enabledJson(true)).isEqualTo("{\"enabled\":true}");
    assertThat(
            List.of(
                ServerErrorFault.ID,
                LogDiskFullFault.ID,
                DnsFailureFault.ID,
                TlsMismatchFault.ID,
                InstanceReassignFault.ID))
        .containsExactly("T-CH-004", "T-CH-005", "T-CH-006", "T-CH-007", "T-CH-008");
    assertThat(new TlsMismatchFault()).isInstanceOf(StandaloneFault.class);
    assertThat(ChaosProfile.SHORT.fault("T-CH-004")).isEqualTo(Duration.ofMinutes(2));
    assertThat(ChaosProfile.FULL.fault("T-CH-008")).isEqualTo(Duration.ofMinutes(10));
  }
}
