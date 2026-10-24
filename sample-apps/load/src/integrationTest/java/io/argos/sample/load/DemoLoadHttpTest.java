/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.load;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The demo load generator over real HTTP (java.net.http) against an in-process server on the
 * loopback interface: open model, outcome classification, the summary file and the exit code gate.
 */
@Tag("integration")
class DemoLoadHttpTest {

  private static final FixedRateGenerator.Reporter SILENT =
      (elapsed, snapshot, inFlight, warmup) -> {};

  private static LoadStats run(LoadConfig config, FixedRateGenerator.Result[] result) {
    LoadStats stats = new LoadStats(config.mix());
    try (JdkHttpTransport transport =
        new JdkHttpTransport(
            config.target(), config.connectTimeout(), config.httpThreads(), "argos-demo-load/it")) {
      result[0] = new FixedRateGenerator(config, transport, TimeSource.SYSTEM, stats, SILENT).run();
    }
    return stats;
  }

  @Test
  @DisplayName("400/s with 100 ms answers: every due request sent, ~40 in flight, exact mix")
  void openModelOverHttp() throws IOException {
    try (FakeShopServer server = new FakeShopServer(100, 300)) {
      LoadConfig config =
          LoadConfig.from(
              Map.of(),
              "--target-url",
              server.baseUri().toString(),
              "--rate",
              "400",
              "--mix",
              "bench",
              "--duration",
              "3s");
      FixedRateGenerator.Result[] result = new FixedRateGenerator.Result[1];
      LoadStats.Snapshot s = run(config, result).snapshot();

      assertThat(result[0].dueRequests()).isEqualTo(1200);
      assertThat(result[0].inFlightAtEnd()).isZero();
      assertThat(s.sent()).isEqualTo(1200);
      assertThat(s.dropped()).isZero();
      assertThat(s.expected()).as("200 and the intended 500").isEqualTo(1200);
      assertThat(server.totalRequests()).isEqualTo(1200);
      assertThat(server.requests("orders.get")).isEqualTo(480);
      assertThat(server.requests("orders.page")).isEqualTo(240);
      assertThat(server.requests("pay")).isEqualTo(240);
      assertThat(server.requests("orders.items")).isEqualTo(120);
      assertThat(server.requests("slow")).isEqualTo(60);
      assertThat(server.requests("error-500")).isEqualTo(60);
      // Open model: arrivals keep their pace while answers take 100-300 ms, so about
      // 400 x 0.11 s = 44 requests overlap (a closed loop with few workers would not).
      assertThat(server.maxConcurrency()).isGreaterThanOrEqualTo(20);
      assertThat(s.latency().percentileMillis(50)).isGreaterThanOrEqualTo(100.0);
    }
  }

  @Test
  @DisplayName("No answer within the request timeout counts as TIMEOUT; the 500 route as expected")
  void timeouts() throws IOException {
    try (FakeShopServer server = new FakeShopServer(2000, 0)) {
      LoadConfig config =
          LoadConfig.from(
              Map.of(),
              "--target-url",
              server.baseUri().toString(),
              "--rate",
              "20",
              "--mix",
              "bench",
              "--duration",
              "1s",
              "--request-timeout",
              "300ms");
      FixedRateGenerator.Result[] result = new FixedRateGenerator.Result[1];
      LoadStats.Snapshot s = run(config, result).snapshot();

      // One 20-slot cycle: orders 8 + page 4 + pay 4 + items 2 time out; slow (no sleep here)
      // and error-500 answer at once.
      assertThat(s.routes().stream().mapToLong(LoadStats.RouteSnapshot::timeouts).sum())
          .isEqualTo(18);
      assertThat(s.expected()).isEqualTo(2);
      assertThat(result[0].inFlightAtEnd()).isZero();
    }
  }

  @Test
  @DisplayName("Nothing listening: CONNECT_FAIL for every request")
  void connectionRefused() throws IOException {
    int port;
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      port = socket.getLocalPort();
    }
    LoadConfig config =
        LoadConfig.from(
            Map.of(),
            "--target-url",
            "http://127.0.0.1:" + port,
            "--rate",
            "20",
            "--duration",
            "1s",
            "--connect-timeout",
            "2s",
            "--request-timeout",
            "5s");
    FixedRateGenerator.Result[] result = new FixedRateGenerator.Result[1];
    LoadStats.Snapshot s = run(config, result).snapshot();

    assertThat(s.routes().stream().mapToLong(LoadStats.RouteSnapshot::connectFails).sum())
        .isEqualTo(20);
    assertThat(s.completed()).isZero();
  }

  @Test
  @DisplayName("DemoLoadMain.run: warm-up and progress lines, final line, JSON summary, exit 0")
  void mainWritesTheSummary(@TempDir Path dir) throws IOException {
    Path summary = dir.resolve("out/summary.json");
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    int code;
    try (FakeShopServer server = new FakeShopServer(5, 50);
        PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
      LoadConfig config =
          LoadConfig.from(
              Map.of("TARGET_URL", server.baseUri().toString(), "MIX", "bench", "RATE", "100"),
              "--warmup",
              "1s",
              "--duration",
              "2s",
              "--report-interval",
              "1s",
              "--summary",
              summary.toString(),
              "--max-unexpected-rate",
              "0");
      code = DemoLoadMain.run(config, out);
    }
    String stdout = bytes.toString(StandardCharsets.UTF_8);
    assertThat(code).as(stdout).isZero();
    assertThat(stdout)
        .contains("[demo-load] argos-demo-load/")
        .contains("rate=100.0/s mix=bench duration=PT2S warmup=PT1S")
        .contains("[demo-load] warm-up t=1s rate=")
        .contains("[demo-load] t=2s rate=")
        .contains("[demo-load] done: window=2.000s sent=200 achieved=100.0/s unexpectedRate=0.000");
    String json = Files.readString(summary, StandardCharsets.UTF_8);
    assertThat(json)
        .contains("\"tool\": \"argos-demo-load\"")
        .contains("\"mix\": \"bench\"")
        .contains("\"warmupSeconds\": 1,")
        .contains("\"sent\": 200,")
        .contains("\"unexpected\": 0,")
        .contains("\"dropped\": 0,")
        .contains("\"warmupExcluded\": {")
        .contains("\"systemCpuLoadPercent\"");
  }

  @Test
  @DisplayName("MAX_UNEXPECTED_RATE: exit code 1 when more requests fail than allowed")
  void gateFails(@TempDir Path dir) throws IOException {
    int port;
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      port = socket.getLocalPort();
    }
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    int code;
    try (PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
      LoadConfig config =
          LoadConfig.from(
              Map.of(),
              "--target-url",
              "http://127.0.0.1:" + port,
              "--rate",
              "10",
              "--duration",
              "1s",
              "--connect-timeout",
              "2s",
              "--summary",
              dir.resolve("summary.json").toString(),
              "--max-unexpected-rate",
              "0.01");
      code = DemoLoadMain.run(config, out);
    }
    assertThat(code).isEqualTo(1);
    assertThat(bytes.toString(StandardCharsets.UTF_8))
        .contains("FAILED: unexpected rate 1.0 > 0.01");
    assertThat(dir.resolve("summary.json")).exists();
  }
}
