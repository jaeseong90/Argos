/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** k6 {@code --out json} lines as k6 v2.3.0 writes them (captured on 2026-11-03, trimmed). */
class K6RawTest {

  static final String METRIC_LINE =
      "{\"type\":\"Metric\",\"data\":{\"name\":\"http_req_duration\",\"type\":\"trend\",\"contains\":\"time\",\"thresholds\":[],\"submetrics\":null},\"metric\":\"http_req_duration\"}";

  static String point(String metric, String time, double value, String phase, String route) {
    String tags =
        "\"expected_response\":\"true\",\"group\":\"\",\"method\":\"GET\",\"name\":\"GET /api/orders/{id}\""
            + (phase == null ? "" : ",\"phase\":\"" + phase + "\"")
            + ",\"proto\":\"HTTP/1.1\""
            + (route == null ? "" : ",\"route\":\"" + route + "\"")
            + ",\"scenario\":\"mix\",\"status\":\"200\",\"url\":\"GET /api/orders/{id}\"";
    return "{\"metric\":\""
        + metric
        + "\",\"type\":\"Point\",\"data\":{\"time\":\""
        + time
        + "\",\"value\":"
        + value
        + ",\"tags\":{"
        + tags
        + "}}}";
  }

  static List<String> lines() {
    return List.of(
        METRIC_LINE,
        // setup request (phase setup) and warm-up requests are not measured
        point("http_req_duration", "2026-11-03T02:56:11.1367417+09:00", 52.3944, "setup", null),
        point(
            "http_req_duration", "2026-11-03T02:56:11.2000000+09:00", 9.0, "warmup", "orders.get"),
        point("http_reqs", "2026-11-03T02:56:12.0000000+09:00", 1, "measure", "orders.get"),
        point(
            "http_req_duration",
            "2026-11-03T02:56:12.0000000+09:00",
            1.269,
            "measure",
            "orders.get"),
        point(
            "http_req_waiting", "2026-11-03T02:56:12.0000000+09:00", 1.1, "measure", "orders.get"),
        point("http_req_duration", "2026-11-03T02:56:13.5000000+09:00", 2.5, "measure", "pay"),
        point("http_req_duration", "2026-11-03T02:56:14.0000000+09:00", 301.25, "measure", "slow"),
        point("http_req_duration", "2026-11-03T02:56:15.0000000+09:00", 3.75e0, "measure", "pay"));
  }

  @Test
  @DisplayName("only measured http_req_duration points count, per route too")
  void measuredDurationsOnly() throws IOException {
    K6Raw.Result r =
        K6Raw.parse(new BufferedReader(new StringReader(String.join("\n", lines()))), 0);
    assertThat(r.points()).isEqualTo(4);
    assertThat(r.otherPhase()).isEqualTo(2);
    assertThat(r.discarded()).isZero();
    assertThat(r.all().count()).isEqualTo(4);
    assertThat(r.routes()).containsOnlyKeys("orders.get", "pay", "slow");
    assertThat(r.routes().get("pay").count()).isEqualTo(2);
    assertThat(r.all().maxMillis()).isEqualTo(301.25);
    assertThat(r.all().quantileMillis(0.5)).isCloseTo(2.5, within(0.002));
    assertThat(r.firstTime()).isEqualTo("2026-11-03T02:56:12.0000000+09:00");
    assertThat(r.lastTime()).isEqualTo("2026-11-03T02:56:15.0000000+09:00");
  }

  @Test
  @DisplayName("the first seconds after the first measured point can be discarded")
  void discardOffset() throws IOException {
    K6Raw.Result r =
        K6Raw.parse(new BufferedReader(new StringReader(String.join("\n", lines()))), 2.0);
    // 12.0 s (first) and 13.5 s fall in the first 2 s; 14.0 and 15.0 remain
    assertThat(r.discarded()).isEqualTo(2);
    assertThat(r.points()).isEqualTo(2);
    assertThat(r.routes()).containsOnlyKeys("slow", "pay");
  }

  @Test
  @DisplayName("gzip files are read and the JSON summary keeps the histograms")
  void gzipFileAndSummary(@TempDir Path dir) throws IOException {
    Path file = dir.resolve(K6Raw.FILE_NAME);
    try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(file))) {
      out.write(String.join("\n", lines()).getBytes(StandardCharsets.UTF_8));
    }
    K6Raw.Result r = K6Raw.read(file, 0);
    assertThat(r.points()).isEqualTo(4);
    Object json = Json.parse(Json.write(r.toJson(K6Raw.FILE_NAME, 0)));
    assertThat(Json.number(json, "points")).isEqualTo(4.0);
    assertThat(LatencyHistogram.fromJson(Json.at(json, "routes", "pay")).count()).isEqualTo(2);
    // plain text works too
    Path plain = dir.resolve("raw.json");
    Files.writeString(plain, String.join("\n", lines()), StandardCharsets.UTF_8);
    assertThat(K6Raw.read(plain, 0).points()).isEqualTo(4);
  }

  @Test
  @DisplayName("tags and numbers are read from the line without a JSON parse")
  void tagAndNumberScanning() {
    String line = point("http_req_duration", "t", 12.5, "measure", "orders.page");
    assertThat(K6Raw.tag(line, "route")).isEqualTo("orders.page");
    assertThat(K6Raw.tag(line, "phase")).isEqualTo("measure");
    assertThat(K6Raw.tag(line, "missing")).isNull();
    assertThat(K6Raw.number(line, "\"value\":")).isEqualTo(12.5);
    assertThat(K6Raw.number("{\"value\":x}", "\"value\":")).isNaN();
    assertThat(K6Raw.number("{}", "\"value\":")).isNaN();
  }
}
