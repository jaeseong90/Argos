/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.chaos;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("chaos runner parsers")
class ParsersTest {

  private static final Instant T = Instant.parse("2026-11-26T10:00:10Z");

  @Test
  @DisplayName("T-CH-020b load generator progress line: counts and interval p99, warm-up ignored")
  void progressLine() {
    String line =
        "[demo-load] t=10s rate=100.0/s sent=1000 expected=950 unexpected=2 5xx=50 dropped=0"
            + " inFlight=3 p50=4.2ms p95=301.0ms p99=305.5ms";
    LoadInterval i = LoadInterval.parse(line, T, Duration.ofSeconds(10)).orElseThrow();
    assertThat(i.sent()).isEqualTo(1000);
    assertThat(i.expected()).isEqualTo(950);
    assertThat(i.unexpected()).isEqualTo(2);
    assertThat(i.status5xx()).isEqualTo(50);
    assertThat(i.inFlight()).isEqualTo(3);
    assertThat(i.p99()).isEqualTo(305.5);
    assertThat(i.start()).isEqualTo(Instant.parse("2026-11-26T10:00:00Z"));
    assertThat(i.within(T.minusSeconds(10), T)).isTrue();
    assertThat(i.within(T.minusSeconds(5), T)).isFalse();
    String empty = line.replace("p99=305.5ms", "p99=NaNms");
    assertThat(LoadInterval.parse(empty, T, Duration.ofSeconds(10)).orElseThrow().p99()).isNaN();
    assertThat(
            LoadInterval.parse(
                line.replace("[demo-load] ", "[demo-load] warm-up "), T, Duration.ofSeconds(10)))
        .isEmpty();
    assertThat(LoadInterval.parse("[demo-load] summary: x.json", T, Duration.ofSeconds(10)))
        .isEmpty();
  }

  @Test
  @DisplayName("T-CH-020b load generator summary JSON (sample-apps/load Summary)")
  void summary() {
    String json =
        """
        {"tool":"argos-demo-load","requests":{"windowSeconds":60.0,"due":6000,"sent":6000,
         "dropped":0,"answered":5998,"expected":5990,"unexpected":10,"failedWithoutAnswer":2,
         "status5xx":300,"inFlightAtEnd":0},
         "latencyMs":{"count":5998,"mean":20.1,"p50":4.0,"p95":300.0,"p99":306.5,"p99_9":900.0,"max":1200.0}}
        """;
    LoadSummary s = LoadSummary.parse(json);
    assertThat(s.sent()).isEqualTo(6000);
    assertThat(s.answered()).isEqualTo(5998);
    assertThat(s.failedWithoutAnswer()).isEqualTo(2);
    assertThat(s.p99()).isEqualTo(306.5);
    assertThat(s.maxTransactions()).isEqualTo(6000);
    assertThat(LoadSummary.parse("{\"requests\":{},\"latencyMs\":{\"p99\":null}}").p99()).isNaN();
  }

  @Test
  @DisplayName("T-CH-020h agent log: records by level and time, retransformation failure lines")
  void agentLog() {
    String text =
        """
        2026-11-26T19:00:00.000+09:00 INFO  [main] Argos agent started
        2026-11-26T19:00:01.500+09:00 WARN  [argos-io] collector connection failed (UNAVAILABLE, java.net.ConnectException); the agent keeps retrying with back-off
        2026-11-26T19:00:02.000+09:00 ERROR [argos-sender] something broke
            at io.argos.agent.core.X.y(X.java:1)
        2026-11-26T19:00:03.000+09:00 WARN  [main] retransformation of 3 already loaded class(es) failed and they stay uninstrumented (this batch: 3, e.g. com.example.A): java.lang.UnsupportedOperationException; at most one such line per minute
        not a record
        """;
    AgentLog log = AgentLog.parse(text);
    assertThat(log.lines()).hasSize(4);
    Instant from = Instant.parse("2026-11-26T10:00:01Z");
    Instant to = Instant.parse("2026-11-26T10:00:05Z");
    assertThat(log.errors(from, to))
        .extracting(AgentLog.Line::message)
        .containsExactly("something broke");
    assertThat(log.errors(from, Instant.parse("2026-11-26T10:00:01.900Z"))).isEmpty();
    assertThat(log.retransformFailures(from, to)).hasSize(1);
    assertThat(log.lines().get(0).time()).isEqualTo(Instant.parse("2026-11-26T10:00:00Z"));
  }

  @Test
  @DisplayName("T-CH-020d reconnect back-off from toxiproxy 'Accepted client' times")
  void backoff() {
    List<String> lines =
        List.of(
            "2026-11-26T10:29:08.012104607Z {\"level\":\"info\",\"message\":\"Starting Toxiproxy\"}",
            accepted("2026-11-26T10:30:00.000Z"),
            "2026-11-26T10:30:00.010Z {\"level\":\"error\",\"message\":\"Unable to open connection to upstream\"}",
            accepted("2026-11-26T10:30:30.000Z"),
            accepted("2026-11-26T10:30:30.200Z"), // a second socket of the same attempt
            accepted("2026-11-26T10:30:31.100Z"),
            accepted("2026-11-26T10:31:05.000Z"),
            accepted("2026-11-26T10:40:00.000Z")); // after the window
    ReconnectGaps g =
        ReconnectGaps.fromToxiproxyLog(
            lines, Instant.parse("2026-11-26T10:29:50Z"), Instant.parse("2026-11-26T10:32:00Z"));
    assertThat(g.attempts()).hasSize(4);
    assertThat(g.gaps())
        .containsExactly(
            Duration.ofSeconds(30), Duration.ofMillis(1_100), Duration.ofMillis(33_900));
    assertThat(g.longest()).contains(Duration.ofMillis(33_900));
    assertThat(g.withinLimit()).isTrue();
    ReconnectGaps slow =
        ReconnectGaps.fromToxiproxyLog(
            List.of(accepted("2026-11-26T10:30:00Z"), accepted("2026-11-26T10:30:37Z")),
            Instant.parse("2026-11-26T10:29:00Z"),
            Instant.parse("2026-11-26T10:31:00Z"));
    assertThat(slow.withinLimit()).isFalse();
    assertThat(new ReconnectGaps(List.of(Instant.EPOCH)).withinLimit()).isFalse();
  }

  private static String accepted(String time) {
    return time
        + " {\"level\":\"info\",\"name\":\"collector\",\"client\":\"172.17.0.1:47884\","
        + "\"caller\":\"proxy.go:204\",\"message\":\"Accepted client\"}";
  }

  @Test
  @DisplayName("T-CH-020h collection-health: drops summed, outages by reason and overlap")
  void collectionHealth() {
    String json =
        """
        {"agent_drops":{"ts":["2026-11-26T10:00:00Z","2026-11-26T10:01:00Z"],"value":[null,4200.0]},
         "server_drops":{"ts":[],"value":[]},
         "server_drops_quota":{"ts":["2026-11-26T10:00:00Z"],"value":[3.0]},
         "excluded":{}, "ingest_lag_s":{"ts":[],"value":[]}, "late_rows":null, "clock_skew":null,
         "outages":[{"from":"2026-11-26T10:00:30Z","to":"2026-11-26T10:03:40Z","instance_id":null,"reason":"server_down"},
                    {"from":"2026-11-26T10:05:00Z","to":null,"instance_id":7,"reason":"session_lost"}],
         "per_instance":[]}
        """;
    HealthView v = HealthView.parse(json);
    assertThat(v.agentDrops()).isEqualTo(4200.0);
    assertThat(v.serverDrops()).isZero();
    assertThat(v.serverDropsQuota()).isEqualTo(3.0);
    assertThat(v.outages()).hasSize(2);
    Instant a = Instant.parse("2026-11-26T10:00:00Z");
    assertThat(v.hasOutage("server_down", a, a.plusSeconds(60))).isTrue();
    assertThat(v.hasOutage("server_down", a.plusSeconds(300), a.plusSeconds(400))).isFalse();
    assertThat(v.hasOutage("session_lost", a.plusSeconds(600), a.plusSeconds(700))).isTrue();
  }
}
