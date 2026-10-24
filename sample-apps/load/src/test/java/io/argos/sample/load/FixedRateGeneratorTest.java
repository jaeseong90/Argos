/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.load;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Open-model scheduling of {@link FixedRateGenerator} on a simulated clock. */
class FixedRateGeneratorTest {

  private static final FixedRateGenerator.Reporter SILENT =
      (elapsed, snapshot, inFlight, warmup) -> {};

  private static LoadConfig config(String... args) {
    return LoadConfig.from(Map.of(), args);
  }

  @Test
  @DisplayName("Request n is sent exactly at start + n / rate; rate x duration requests in all")
  void fixedArrivalRate() {
    SimulatedTime time = new SimulatedTime();
    FakeTransport transport = new FakeTransport(time, FakeTransport.catalogStatuses());
    LoadConfig config = config("--rate", "100", "--mix", "bench", "--duration", "10s");
    LoadStats stats = new LoadStats(config.mix());

    FixedRateGenerator.Result result =
        new FixedRateGenerator(config, transport, time, stats, SILENT).run();

    assertThat(result.dueRequests()).isEqualTo(1000);
    assertThat(result.windowNanos()).isEqualTo(10_000_000_000L);
    assertThat(result.inFlightAtEnd()).isZero();
    List<FakeTransport.Sent> sent = transport.sent();
    assertThat(sent).hasSize(1000);
    for (int n = 0; n < sent.size(); n++) {
      assertThat(sent.get(n).atNanos() - SimulatedTime.START)
          .as("send time of request %d", n)
          .isEqualTo(n * 10_000_000L);
      assertThat(sent.get(n).timeout()).isEqualTo(config.requestTimeout());
    }
    LoadStats.Snapshot s = stats.snapshot();
    assertThat(s.sent()).isEqualTo(1000);
    assertThat(s.expected()).isEqualTo(1000);
    assertThat(s.unexpected()).isZero();
    assertThat(s.dropped()).isZero();
    assertThat(s.lag().maxMillis()).isZero();
  }

  @Test
  @DisplayName("The bench mix is exact over the run: 400/200/200/100/50/50 of 1,000 requests")
  void mixSharesAreExact() {
    SimulatedTime time = new SimulatedTime();
    FakeTransport transport = new FakeTransport(time, FakeTransport.catalogStatuses());
    LoadConfig config = config("--rate", "1000", "--mix", "bench", "--duration", "1s");
    new FixedRateGenerator(config, transport, time, new LoadStats(config.mix()), SILENT).run();

    Map<String, Integer> counts = new LinkedHashMap<>();
    for (String path : transport.paths()) {
      counts.merge(kind(path), 1, Integer::sum);
    }
    assertThat(counts)
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                "orders.get", 400,
                "orders.page", 200,
                "pay", 200,
                "orders.items", 100,
                "slow", 50,
                "error-500", 50));
    // Every order of the sample data is read by orders.get within 1,000 of its requests.
    assertThat(
            transport.paths().stream()
                .filter(p -> kind(p).equals("orders.get"))
                .map(p -> Long.parseLong(p.substring("/api/orders/".length())))
                .distinct()
                .count())
        .isEqualTo(400);
  }

  @Test
  @DisplayName("Open model: arrivals do not wait for answers (requests that never answer)")
  void arrivalsDoNotWaitForAnswers() {
    SimulatedTime time = new SimulatedTime();
    FakeTransport transport = new FakeTransport(time, FakeTransport.neverAnswers());
    LoadConfig config =
        config(
            "--rate",
            "200",
            "--duration",
            "5s",
            "--max-in-flight",
            "100000",
            "--request-timeout",
            "2s");
    LoadStats stats = new LoadStats(config.mix());

    FixedRateGenerator.Result result =
        new FixedRateGenerator(config, transport, time, stats, SILENT).run();

    assertThat(transport.sent()).hasSize(1000);
    assertThat(transport.sent().get(999).atNanos() - SimulatedTime.START)
        .isEqualTo(999 * 5_000_000L);
    assertThat(result.inFlightAtEnd()).isEqualTo(1000);
    // After the last send (4.995 s) the generator waits request timeout + 1 s for the answers,
    // then gives up.
    assertThat(time.elapsed()).isBetween(7_995_000_000L, 8_005_000_000L);
    assertThat(stats.snapshot().completed()).isZero();
  }

  @Test
  @DisplayName("Beyond MAX_IN_FLIGHT a due request is dropped and counted, never queued")
  void dropsBeyondInFlightLimit() {
    SimulatedTime time = new SimulatedTime();
    FakeTransport transport = new FakeTransport(time, FakeTransport.neverAnswers());
    LoadConfig config =
        config(
            "--rate",
            "100",
            "--duration",
            "2s",
            "--max-in-flight",
            "10",
            "--request-timeout",
            "1s");
    LoadStats stats = new LoadStats(config.mix());

    FixedRateGenerator.Result result =
        new FixedRateGenerator(config, transport, time, stats, SILENT).run();

    LoadStats.Snapshot s = stats.snapshot();
    assertThat(transport.sent()).hasSize(10);
    assertThat(s.scheduled()).isEqualTo(200);
    assertThat(s.sent()).isEqualTo(10);
    assertThat(s.dropped()).isEqualTo(190);
    Map<String, Object> summary = Summary.of(config, "test", "now", result, s, Map.of());
    Map<?, ?> requests = (Map<?, ?>) summary.get("requests");
    assertThat((Double) requests.get("droppedRate")).isCloseTo(0.95, within(1e-9));
    // Dropped and never answered requests are both unexpected outcomes.
    assertThat((Double) requests.get("unexpectedRate")).isCloseTo(1.0, within(1e-9));
  }

  @Test
  @DisplayName("A late wake-up sends every request that became due: the average rate stays exact")
  void lateWakeUpCatchesUp() {
    SimulatedTime time = new SimulatedTime(n -> n == 3 ? 1_000_000_000L : 0);
    FakeTransport transport = new FakeTransport(time, FakeTransport.catalogStatuses());
    LoadConfig config = config("--rate", "100", "--duration", "3s");
    LoadStats stats = new LoadStats(config.mix());

    FixedRateGenerator.Result result =
        new FixedRateGenerator(config, transport, time, stats, SILENT).run();

    assertThat(result.dueRequests()).isEqualTo(300);
    assertThat(transport.sent()).hasSize(300);
    long previous = Long.MIN_VALUE;
    for (FakeTransport.Sent s : transport.sent()) {
      assertThat(s.atNanos()).isGreaterThanOrEqualTo(previous);
      previous = s.atNanos();
    }
    // About 100 requests went out in one burst, the oldest about one second late.
    assertThat(stats.snapshot().lag().maxMillis()).isBetween(990.0, 1020.0);
    assertThat(stats.snapshot().lag().percentileMillis(50)).isZero();
  }

  @Test
  @DisplayName("stop() ends scheduling at once; the window ends at the stop")
  void stopEndsTheRun() {
    SimulatedTime time = new SimulatedTime();
    AtomicReference<FixedRateGenerator> generator = new AtomicReference<>();
    List<String> seen = new ArrayList<>();
    FakeTransport transport =
        new FakeTransport(
            time,
            path -> {
              seen.add(path);
              if (seen.size() == 250) {
                generator.get().stop();
              }
              return CompletableFuture.completedFuture(200);
            });
    LoadConfig config = config("--rate", "100", "--duration", "10s");
    generator.set(
        new FixedRateGenerator(config, transport, time, new LoadStats(config.mix()), SILENT));

    FixedRateGenerator.Result result = generator.get().run();

    assertThat(result.dueRequests()).isEqualTo(250);
    assertThat(result.windowNanos()).isEqualTo(249 * 10_000_000L);
  }

  @Test
  @DisplayName("Progress reports come every interval with the counters at that moment")
  void reportsEveryInterval() {
    SimulatedTime time = new SimulatedTime();
    FakeTransport transport = new FakeTransport(time, FakeTransport.catalogStatuses());
    LoadConfig config = config("--rate", "100", "--duration", "10s", "--report-interval", "1s");
    List<long[]> reports = new ArrayList<>();
    FixedRateGenerator.Reporter reporter =
        (elapsed, snapshot, inFlight, warmup) -> reports.add(new long[] {elapsed, snapshot.sent()});

    new FixedRateGenerator(config, transport, time, new LoadStats(config.mix()), reporter).run();

    assertThat(reports).hasSize(9);
    for (int k = 1; k <= 9; k++) {
      assertThat(reports.get(k - 1)[0]).isEqualTo(k * 1_000_000_000L);
      assertThat(reports.get(k - 1)[1]).isEqualTo(100L * k);
    }
  }

  @Test
  @DisplayName("After a long pause the progress line comes once, not once per missed interval")
  void reportsOnceAfterAPause() {
    SimulatedTime time = new SimulatedTime(n -> n == 2 ? 5_000_000_000L : 0);
    FakeTransport transport = new FakeTransport(time, FakeTransport.catalogStatuses());
    LoadConfig config = config("--rate", "10", "--duration", "8s", "--report-interval", "1s");
    List<Long> reports = new ArrayList<>();
    FixedRateGenerator.Reporter reporter =
        (elapsed, snapshot, inFlight, warmup) -> reports.add(elapsed);

    new FixedRateGenerator(config, transport, time, new LoadStats(config.mix()), reporter).run();

    assertThat(reports).doesNotHaveDuplicates().isSorted();
    assertThat(reports.size()).isLessThan(8);
  }

  @Test
  @DisplayName("Outcomes: expected 5xx, unexpected status, timeout, connect failure, I/O error")
  void classifiesOutcomes() {
    SimulatedTime time = new SimulatedTime();
    FakeTransport transport =
        new FakeTransport(
            time,
            path -> {
              if (path.startsWith("/api/slow")) {
                return CompletableFuture.failedFuture(
                    new HttpTimeoutException("request timed out"));
              }
              if (path.startsWith("/api/pay/")) {
                return CompletableFuture.failedFuture(new ConnectException("refused"));
              }
              if (path.endsWith("/items")) {
                return CompletableFuture.failedFuture(new IOException("connection reset"));
              }
              // error-500 answers 200: an unexpected status for that route.
              return CompletableFuture.completedFuture(200);
            });
    LoadConfig config = config("--rate", "100", "--mix", "bench", "--duration", "10s");
    LoadStats stats = new LoadStats(config.mix());

    FixedRateGenerator.Result result =
        new FixedRateGenerator(config, transport, time, stats, SILENT).run();

    Map<String, LoadStats.RouteSnapshot> routes = new LinkedHashMap<>();
    stats.snapshot().routes().forEach(r -> routes.put(r.route().key(), r));
    assertThat(routes.get("slow").timeouts()).isEqualTo(50);
    assertThat(routes.get("pay").connectFails()).isEqualTo(200);
    assertThat(routes.get("orders.items").ioErrors()).isEqualTo(100);
    assertThat(routes.get("error-500").unexpectedStatus()).isEqualTo(50);
    assertThat(routes.get("error-500").statuses()).containsExactly(Map.entry(200, 50L));
    assertThat(routes.get("orders.get").expected()).isEqualTo(400);

    Map<?, ?> requests =
        (Map<?, ?>)
            Summary.of(config, "t", "now", result, stats.snapshot(), Map.of()).get("requests");
    assertThat((Double) requests.get("unexpectedRate")).isCloseTo(0.4, within(1e-9));
    assertThat((Double) requests.get("http5xxRate")).isCloseTo(0.35, within(1e-9));
    assertThat(requests.get("failedWithoutAnswer")).isEqualTo(350L);
  }

  @Test
  @DisplayName("A transport that throws while sending counts as an I/O error and frees its slot")
  void transportThatThrows() {
    SimulatedTime time = new SimulatedTime();
    FakeTransport transport =
        new FakeTransport(
            time,
            path -> {
              throw new IllegalStateException("client closed");
            });
    LoadConfig config = config("--rate", "50", "--duration", "1s", "--max-in-flight", "1");
    LoadStats stats = new LoadStats(config.mix());
    FixedRateGenerator generator = new FixedRateGenerator(config, transport, time, stats, SILENT);

    FixedRateGenerator.Result result = generator.run();

    assertThat(stats.snapshot().failures()).isEqualTo(50);
    assertThat(stats.snapshot().dropped()).isZero();
    assertThat(result.inFlightAtEnd()).isZero();
    assertThat(generator.inFlight()).isZero();
  }

  @Test
  @DisplayName("Latency is measured from the send to the answer")
  void measuresLatency() {
    SimulatedTime time = new SimulatedTime();
    List<CompletableFuture<Integer>> pending = new ArrayList<>();
    // Each request is answered when the next one is sent: 10 ms later at 100/s.
    FakeTransport transport =
        new FakeTransport(
            time,
            path -> {
              if (!pending.isEmpty()) {
                pending.remove(0).complete(200);
              }
              CompletableFuture<Integer> answer = new CompletableFuture<>();
              pending.add(answer);
              return answer;
            });
    LoadConfig config =
        config("--rate", "100", "--mix", "bench", "--duration", "2s", "--request-timeout", "1s");
    LoadStats stats = new LoadStats(config.mix());

    FixedRateGenerator.Result result =
        new FixedRateGenerator(config, transport, time, stats, SILENT).run();

    LatencyHistogram.Snapshot latency = stats.snapshot().latency();
    assertThat(latency.count()).isEqualTo(199);
    assertThat(latency.percentileMillis(50)).isEqualTo(10.0);
    assertThat(latency.percentileMillis(99.9)).isEqualTo(10.0);
    assertThat(latency.meanMillis()).isEqualTo(10.0);
    assertThat(result.inFlightAtEnd()).isEqualTo(1);
  }

  @Test
  @DisplayName("Warm-up: same rate and mix first, counted apart; the window is the measured part")
  void warmupIsCountedApart() {
    SimulatedTime time = new SimulatedTime();
    FakeTransport transport = new FakeTransport(time, FakeTransport.catalogStatuses());
    LoadConfig config =
        config(
            "--rate",
            "100",
            "--mix",
            "bench",
            "--warmup",
            "2s",
            "--duration",
            "3s",
            "--report-interval",
            "1s");
    LoadStats warmupStats = new LoadStats(config.mix());
    LoadStats stats = new LoadStats(config.mix());
    List<Boolean> phases = new ArrayList<>();
    List<Long> sentAtReport = new ArrayList<>();
    FixedRateGenerator.Reporter reporter =
        (elapsed, snapshot, inFlight, warmup) -> {
          phases.add(warmup);
          sentAtReport.add(snapshot.sent());
        };

    FixedRateGenerator.Result result =
        new FixedRateGenerator(config, transport, time, warmupStats, stats, reporter).run();

    // One continuous schedule of 500 requests: 200 in the warm-up, 300 measured.
    assertThat(transport.sent()).hasSize(500);
    assertThat(transport.sent().get(499).atNanos() - SimulatedTime.START)
        .isEqualTo(499 * 10_000_000L);
    assertThat(warmupStats.snapshot().sent()).isEqualTo(200);
    assertThat(stats.snapshot().sent()).isEqualTo(300);
    assertThat(result.dueRequests()).isEqualTo(300);
    assertThat(result.windowNanos()).isEqualTo(3_000_000_000L);
    // The measured mix continues the schedule: request 200 is slot 0 of a 20-slot cycle.
    assertThat(stats.snapshot().routes().get(0).sent()).isEqualTo(120);
    // Reports at 1 s and 2 s (the end of the warm-up closes it), then 3 s and 4 s with the
    // measured counters, which start at 2 s.
    assertThat(phases).containsExactly(true, true, false, false);
    assertThat(sentAtReport).containsExactly(100L, 200L, 100L, 200L);
  }

  @Test
  @DisplayName("Stopped during the warm-up: an empty measured window")
  void stoppedDuringWarmup() {
    SimulatedTime time = new SimulatedTime();
    AtomicReference<FixedRateGenerator> generator = new AtomicReference<>();
    List<String> seen = new ArrayList<>();
    FakeTransport transport =
        new FakeTransport(
            time,
            path -> {
              seen.add(path);
              if (seen.size() == 50) {
                generator.get().stop();
              }
              return CompletableFuture.completedFuture(200);
            });
    LoadConfig config = config("--rate", "100", "--warmup", "5s", "--duration", "5s");
    LoadStats stats = new LoadStats(config.mix());
    generator.set(new FixedRateGenerator(config, transport, time, stats, SILENT));

    FixedRateGenerator.Result result = generator.get().run();

    assertThat(result.dueRequests()).isZero();
    assertThat(result.windowNanos()).isZero();
    assertThat(stats.snapshot().sent()).isZero();
  }

  /** Route key of a bench path. */
  private static String kind(String path) {
    if (path.startsWith("/api/orders?page=")) {
      return "orders.page";
    }
    if (path.endsWith("/items")) {
      return "orders.items";
    }
    if (path.startsWith("/api/orders/")) {
      return "orders.get";
    }
    if (path.startsWith("/api/pay/")) {
      return "pay";
    }
    if (path.startsWith("/api/slow")) {
      return "slow";
    }
    if (path.startsWith("/api/error-500")) {
      return "error-500";
    }
    return path;
  }
}
