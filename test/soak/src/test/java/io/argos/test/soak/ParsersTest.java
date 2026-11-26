/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.soak;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.test.soak.SoakVerdicts.ConsistencyRun;
import io.argos.test.soak.SoakVerdicts.Firing;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

@Tag("req:N-02")
class ParsersTest {

  private static final LocalDate DAY = LocalDate.of(2026, 11, 26);

  @Test
  @DisplayName(
      "T-SOAK-001a the load generator summary gives p99, sent, rates and the mix's intended 5xx"
          + " share")
  void loadSummary() {
    String json =
        """
        {"tool":"argos-demo-load","requests":{"sent":30000,"unexpectedRate":0.0002,
         "http5xxRate":0.05},"latencyMs":{"count":30000,"p50":4.1,"p99":312.5},
         "routes":[{"route":"orders","weightPercent":40,"expectedStatus":200},
                   {"route":"error-500","weightPercent":5,"expectedStatus":500},
                   {"route":"slow","weightPercent":5,"expectedStatus":200}]}
        """;
    LoadSegments.Summary s = LoadSegments.parse(json);
    assertThat(s).isNotNull();
    assertThat(s.p99Millis()).isEqualTo(312.5);
    assertThat(s.sent()).isEqualTo(30_000);
    assertThat(s.unexpectedRate()).isEqualTo(0.0002);
    assertThat(s.http5xxRate()).isEqualTo(0.05);
    assertThat(s.errorShare()).isEqualTo(0.05);
    assertThat(LoadSegments.parse("not json")).isNull();
    assertThat(LoadSegments.parse("{\"requests\":{}}")).isNull();
  }

  @Test
  @DisplayName(
      "T-SOAK-001a the consistency check output: rollup minutes counted, D-12 recorded"
          + " separately, a failure document is an error")
  void consistency() {
    String ok =
        """
        {"command":"consistency check","day":"2026-11-26","result":"ok","checks":[
          {"app_id":1,"check":"d03","table":"app_stat_1m","mismatch_minutes":0},
          {"app_id":1,"check":"d12","table":"sql_stat_raw","mismatch_minutes":4}]}
        """;
    ConsistencyRun r = ConsistencyCheck.parse(DAY, 0, ok);
    assertThat(r.clean()).isTrue();
    assertThat(r.d12MismatchMinutes()).isEqualTo(4);
    String bad =
        """
        {"result":"mismatch","checks":[
          {"check":"d03","mismatch_minutes":3},{"check":"rollup_1h","mismatch_minutes":60}]}
        """;
    ConsistencyRun m = ConsistencyCheck.parse(DAY, 1, bad);
    assertThat(m.rollupMismatchMinutes()).isEqualTo(63);
    assertThat(m.clean()).isFalse();
    ConsistencyRun failed =
        ConsistencyCheck.parse(
            DAY, 1, "{\"command\":\"consistency check\",\"error\":\"advisory lock held\"}");
    assertThat(failed.error()).isEqualTo("advisory lock held");
    assertThat(ConsistencyCheck.parse(DAY, 1, "").error()).isEqualTo("no result in the output");
    assertThat(ConsistencyCheck.parse(DAY, 1, "Exception in thread").error())
        .startsWith("unreadable output");
  }

  @Test
  @DisplayName(
      "T-SOAK-001a alert events: FIRING rows only; error_rate at the load's intended 5% share is"
          + " expected, any other metric is a false positive")
  void alertEvents() throws Exception {
    String data =
        """
        [{"ts":"2026-11-26T10:00:00Z","state":"FIRING","severity":"warn","value":0.051,
          "threshold":0.05,"app":{"id":1,"name":"soak-shop"},"rule":{"id":"r1","metric":"error_rate"}},
         {"ts":"2026-11-26T10:05:00Z","state":"RESOLVED","severity":"warn","value":0.01,
          "threshold":0.05,"app":{"id":1,"name":"soak-shop"},"rule":{"id":"r1","metric":"error_rate"}},
         {"ts":"2026-11-26T11:00:00Z","state":"FIRING","severity":"critical","value":0.21,
          "threshold":0.20,"app":{"id":1,"name":"soak-shop"},"rule":{"id":"r1","metric":"error_rate"}},
         {"ts":"2026-11-26T12:00:00Z","state":"FIRING","severity":"warn","value":12,
          "threshold":0,"app":null,"rule":{"id":"r9","metric":"argos_server_dropped_total"}},
         {"ts":"2026-11-26T12:30:00Z","state":"FIRING","severity":"critical","value":1.99,
          "threshold":7,"app":null,"rule":{"id":"r8","metric":"argos_server_tls_cert_expiry_days"}}]
        """;
    List<Firing> f = AlertWatch.parse(JsonMapper.builder().build().readTree(data), 0.05);
    assertThat(f).hasSize(4);
    // The harness's 2-day test certificate: expected, not a false positive.
    assertThat(f.get(3).expectedByLoad()).isTrue();
    assertThat(f.get(0).expectedByLoad()).isTrue();
    assertThat(f.get(0).app()).isEqualTo("soak-shop");
    // The critical threshold (20%) is above the intended 5%: a real finding.
    assertThat(f.get(1).expectedByLoad()).isFalse();
    assertThat(f.get(2).app()).isEqualTo("server");
    assertThat(f.get(2).expectedByLoad()).isFalse();
    assertThat(f.get(2).metric()).isEqualTo("argos_server_dropped_total");
    // The apply answer: rules the server had applied on its own are skipped as 'exists'.
    String applied =
        """
        {"created":["batch_elapsed"],"skipped":[{"key":"error_rate","reason":"exists"},
         {"key":"latency","reason":"exists"},{"key":"disk","reason":"server_scope"}]}
        """;
    assertThat(AlertWatch.describeApplied(JsonMapper.builder().build().readTree(applied)))
        .isEqualTo(
            "created [batch_elapsed]; skipped (exists) [error_rate, latency]; skipped"
                + " (server_scope) [disk]");
  }

  @Test
  @DisplayName(
      "T-SOAK-001a small parsers: batch execution state, Windows handle count, samples.tsv lines")
  void small() {
    assertThat(BatchDriver.state("{\"runId\":\"7\",\"state\":\"RUNNING\"}")).isEqualTo("RUNNING");
    assertThat(BatchDriver.state("{\"state\" : \"DONE\",\"status\":\"COMPLETED\"}"))
        .isEqualTo("DONE");
    assertThat(BatchDriver.state("not found")).isEqualTo("?");
    assertThat(FdProbe.parseCount("\r\n1234\r\n")).isEqualTo(1234);
    assertThat(FdProbe.parseCount("Get-Process : Cannot find a process")).isEqualTo(-1);
    Map<String, Long> v = new LinkedHashMap<>();
    v.put("agent_heap_bytes", 20_971_520L);
    v.put("agent_threads", -1L);
    assertThat(SoakSamples.tsvLines(Instant.parse("2026-11-26T10:00:00.700Z"), 60.04, "agent", v))
        .containsExactly("2026-11-26T10:00:00Z\t60.0\tagent\tagent_heap_bytes\t20971520");
  }
}
