/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.argos.proto.v1.AckStatus;
import io.argos.proto.v1.ExtStat;
import io.argos.proto.v1.FailureKind;
import io.argos.proto.v1.MetricKind;
import io.argos.proto.v1.MetricSample;
import io.argos.proto.v1.MetricSeries;
import io.argos.proto.v1.ProfileStep;
import io.argos.proto.v1.SqlStat;
import io.argos.proto.v1.TransactionKind;
import io.argos.proto.v1.TransactionProfile;
import io.argos.proto.v1.TransactionRecord;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.fakeagent.AgentClock;
import io.argos.server.testing.fakeagent.AgentTokens;
import io.argos.server.testing.fakeagent.CallResult;
import io.argos.server.testing.fakeagent.FakeAgent;
import io.argos.server.testing.fakeagent.HelloResult;
import io.argos.server.testing.fakeagent.Records;
import io.argos.storage.clickhouse.MemoryBudget;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The ingest delay under the 03 §3.2 reference load (N-15, WO-21): 10 fake agent instances send
 * 1,000 transactions per second in total for 5 minutes to the whole server (boot jar, child JVM),
 * with profiles at a 0.12 capture rate, 10 s statistics windows and 5 s metrics. The delay is the
 * server's own {@code argos_ingest_lag_seconds} (receive → ClickHouse insert acknowledged), read
 * from {@code /actuator/prometheus} on the management port (8081 in production; a free port here)
 * with the metrics bearer token: p50 and p99 over the measured 5 minutes from the histogram
 * buckets, and the server's own decaying percentiles at the end.
 *
 * <p>Runs only with {@code -Pserver.load=true}; asserts p99 ≤ 5 s only with {@code
 * -Pserver.load.enforce=true} (a quiet machine or CI). The report {@code
 * build/reports/ingest-load/report.md} is labelled "reference, shared laptop" unless enforced.
 */
@Tag("integration")
@Tag("slow")
@Tag("req:N-15")
class IngestLagLoadIT {

  private static final int INSTANCES = 10;
  private static final int TX_PER_INSTANCE_PER_SECOND = 100;
  private static final double CAPTURE_RATE = 0.12;
  private static final Duration WARM_UP = Duration.ofSeconds(30);
  private static final Duration MEASURED = Duration.ofMinutes(5);
  private static final String LAG = "argos_ingest_lag_seconds";

  @TempDir Path work;

  @Test
  @DisplayName(
      "T-SV-020c (server part, 03 §3.2 load) 1,000 TPS for 5 minutes from 10 instances (profiles"
          + " 0.12, statistics, metrics): argos_ingest_lag_seconds p50/p99 from"
          + " /actuator/prometheus, p99 <= 5 s when enforced")
  void ingestLagUnderLoad() throws Exception {
    assumeTrue(
        Boolean.getBoolean("argos.test.serverLoad"),
        "1,000 TPS for 5 minutes runs only with -Pserver.load=true");
    boolean enforce = Boolean.getBoolean("argos.test.serverLoadEnforce");
    Path reportDir =
        Path.of(System.getProperty("argos.test.ingestLoadReportDir", "build/reports/ingest-load"));
    Files.createDirectories(reportDir);
    byte[] secret = new byte[32];
    new SecureRandom().nextBytes(secret);
    String metricsToken = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
    Path tokenFile = work.resolve("metrics.token");
    Files.writeString(tokenFile, metricsToken, StandardCharsets.UTF_8);

    try (ServerTestEnvironment env =
        ServerTestEnvironment.builder().clickHouseMemoryLimit(4 * MemoryBudget.GIB).start()) {
      String app = "e2e-load";
      Map<String, String> extra =
          Map.of(
              "argos.metrics.token-file", tokenFile.toString(),
              "argos.metrics.allow-cidrs[0]", "127.0.0.1/32",
              "argos.metrics.allow-cidrs[1]", "::1/128");
      try (ServerProcess server = ServerProcess.start(env, work, "load", extra, "2g", true)) {
        // The server's start migrated PostgreSQL; the token row comes after.
        AgentTokens.IssuedToken token = E2e.issue(env, app);
        Scraper scraper = new Scraper(server.managementPort(), metricsToken);
        List<Instance> instances = new ArrayList<>();
        for (int i = 0; i < INSTANCES; i++) {
          FakeAgent agent =
              E2e.agent(
                  env,
                  token.token(),
                  app,
                  "load-" + i,
                  new Records(AgentClock.system(), 100 + i, 1_000L + i),
                  1_700_000_100_000L + i);
          HelloResult hello = agent.connect();
          assertThat(hello.accepted()).as(hello.toString()).isTrue();
          instances.add(new Instance(agent));
        }
        ScheduledExecutorService pool = Executors.newScheduledThreadPool(INSTANCES);
        List<ScheduledFuture<?>> ticks = new ArrayList<>();
        long started = System.nanoTime();
        for (Instance instance : instances) {
          ticks.add(pool.scheduleAtFixedRate(instance::tick, 0, 1, TimeUnit.SECONDS));
        }
        TimeUnit.MILLISECONDS.sleep(WARM_UP.toMillis());
        Histogram atStart = scraper.lag();
        long sentAtStart = instances.stream().mapToLong(i -> i.transactions.get()).sum();
        TimeUnit.MILLISECONDS.sleep(MEASURED.toMillis());
        Histogram atEnd = scraper.lag();
        long sentAtEnd = instances.stream().mapToLong(i -> i.transactions.get()).sum();
        ticks.forEach(t -> t.cancel(false));
        pool.shutdown();
        assertThat(pool.awaitTermination(1, TimeUnit.MINUTES)).isTrue();
        long elapsedS = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started);
        long sent = instances.stream().mapToLong(i -> i.transactions.get()).sum();
        long profiles = instances.stream().mapToLong(i -> i.profiles.get()).sum();
        long steps = instances.stream().mapToLong(i -> i.steps.get()).sum();
        long statRows = instances.stream().mapToLong(i -> i.statRows.get()).sum();
        long samples = instances.stream().mapToLong(i -> i.samples.get()).sum();
        long notOk = instances.stream().mapToLong(i -> i.notOk.get()).sum();
        List<String> failures = new ArrayList<>();
        instances.forEach(i -> failures.addAll(i.failures));
        Map<String, Long> totals = E2e.awaitTotals(env, token.appId(), sent, Duration.ofMinutes(5));
        // The profile_step writer flushes on its own 2 s window: wait for its last block too.
        String stepCount = "SELECT count() FROM argos.profile_step WHERE app_id = " + token.appId();
        long stepRows = E2e.count(env, stepCount);
        long stepDeadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(2);
        while (stepRows != steps && System.nanoTime() < stepDeadline) {
          TimeUnit.MILLISECONDS.sleep(500);
          stepRows = E2e.count(env, stepCount);
        }
        Histogram drained = scraper.lag();
        List<Long> acks = new ArrayList<>();
        instances.forEach(i -> acks.addAll(i.ackMillis));
        acks.sort(null);
        for (Instance i : instances) {
          i.agent.close();
        }

        Histogram window = atEnd.minus(atStart);
        double p50 = window.quantile(0.50);
        double p99 = window.quantile(0.99);
        String report =
            report(
                enforce,
                elapsedS,
                sentAtEnd - sentAtStart,
                sent,
                profiles,
                steps,
                stepRows,
                statRows,
                samples,
                notOk,
                failures,
                totals,
                window,
                p50,
                p99,
                atEnd,
                drained,
                acks);
        Files.writeString(reportDir.resolve("report.md"), report, StandardCharsets.UTF_8);
        System.out.println(report);

        assertThat(failures).as("calls that were not acknowledged OK").isEmpty();
        assertThat(totals).as("every transaction loaded once").isEqualTo(E2e.expected(sent));
        assertThat(stepRows).as("every profile step loaded").isEqualTo(steps);
        if (enforce) {
          assertThat(p99)
              .as("argos_ingest_lag_seconds p99 (s) under 1,000 TPS")
              .isLessThanOrEqualTo(5.0);
        }
      }
    }
  }

  /** One fake agent instance: per second 100 transactions, 12 profiles; statistics; metrics. */
  private static final class Instance {
    final FakeAgent agent;
    final Records records;
    final AtomicLong transactions = new AtomicLong();
    final AtomicLong profiles = new AtomicLong();
    final AtomicLong steps = new AtomicLong();
    final AtomicLong statRows = new AtomicLong();
    final AtomicLong samples = new AtomicLong();
    final AtomicLong notOk = new AtomicLong();
    final ConcurrentLinkedQueue<String> failures = new ConcurrentLinkedQueue<>();
    final ConcurrentLinkedQueue<Long> ackMillis = new ConcurrentLinkedQueue<>();
    long ticks;
    long lastStatWindow = -1;
    long lastMetricGrid = -1;

    Instance(FakeAgent agent) {
      this.agent = agent;
      this.records = agent.records();
    }

    void tick() {
      try {
        ticks++;
        int captured = (int) Math.round(TX_PER_INSTANCE_PER_SECOND * CAPTURE_RATE);
        List<TransactionRecord> txs = new ArrayList<>(TX_PER_INSTANCE_PER_SECOND);
        List<TransactionProfile> profileList = new ArrayList<>(captured);
        long now = records.clock().nowMs();
        for (int i = 0; i < TX_PER_INSTANCE_PER_SECOND; i++) {
          String service = "GET /api/load/" + (i % 20) + "/{id}";
          TransactionRecord.Builder b =
              records.http(service, now - 40 - i % 30, 40_000L + i * 100L);
          if (i < captured) {
            b.setProfileCaptured(true);
          }
          TransactionRecord tx = b.build();
          txs.add(tx);
          if (i < captured) {
            List<ProfileStep> s =
                List.of(
                    records.dbcStep(1, 100, 300, "HikariPool-1", "jdbc:postgresql://db:5432/shop"),
                    records.sqlStep(
                        2,
                        0,
                        500,
                        4_000,
                        800,
                        "SELECT o.id, o.total FROM orders o WHERE o.id = ?",
                        "jdbc:postgresql://db:5432/shop",
                        1),
                    records.sqlStep(
                        3,
                        0,
                        5_000,
                        2_000,
                        200,
                        "SELECT i.sku FROM items i WHERE i.order_id = ?",
                        "jdbc:postgresql://db:5432/shop",
                        4),
                    records.httpCallStep(
                        4, 0, 8_000, 9_000, "http://stock:8080", 200, FailureKind.FAILURE_NONE),
                    records.methodStep(5, 0, 20_000, 1_500, "OrderService#price"));
            profileList.add(records.profile(tx, s).build());
          }
        }
        call(agent.sendTransactions(agent.batches().transactions(txs)));
        transactions.addAndGet(txs.size());
        call(agent.sendProfiles(agent.batches().profiles(profileList)));
        profiles.addAndGet(profileList.size());
        steps.addAndGet(5L * profileList.size());

        long window = Records.window10s(now) - 10_000;
        if (window != lastStatWindow && now - Records.window10s(now) >= 500) {
          lastStatWindow = window;
          List<SqlStat> sql = new ArrayList<>();
          for (int q = 0; q < 20; q++) {
            sql.add(
                records
                    .sqlStat(
                        "SELECT * FROM t" + q + " WHERE id = ?",
                        "jdbc:postgresql://db:5432/shop",
                        TransactionKind.HTTP,
                        50,
                        50L * 2_000,
                        9_000)
                    .build());
          }
          List<ExtStat> ext = new ArrayList<>();
          for (int h = 0; h < 5; h++) {
            ext.add(
                records
                    .extStat(
                        "http://svc-" + h + ":8080", TransactionKind.HTTP, 30, 30L * 5_000, 20_000)
                    .build());
          }
          call(
              agent.sendStats(
                  agent
                      .batches()
                      .stats(
                          window,
                          sql,
                          ext,
                          List.of(
                              Records.counter("transactions_dropped_total", 0),
                              Records.counter("steps_truncated_total", 0)))));
          statRows.addAndGet(sql.size() + ext.size());
        }
        long grid = Records.grid5s(now) - 5_000;
        if (grid != lastMetricGrid) {
          lastMetricGrid = grid;
          List<MetricSeries> series = new ArrayList<>();
          List<MetricSample> sampleList = new ArrayList<>();
          for (int m = 0; m < 20; m++) {
            series.add(Records.series(m, "jvm_load_metric_" + m, MetricKind.GAUGE));
            sampleList.add(Records.sample(m, grid, m * 10.0 + ticks, 0));
          }
          call(agent.sendMetrics(agent.batches().metrics(series, sampleList)));
          samples.addAndGet(sampleList.size());
        }
      } catch (RuntimeException e) {
        failures.add(agent + ": " + e);
      }
    }

    private void call(CallResult r) {
      ackMillis.add(TimeUnit.NANOSECONDS.toMillis(r.latencyNanos()));
      if (!r.ok() || r.ackStatus() != AckStatus.ACK_OK) {
        notOk.incrementAndGet();
        if (failures.size() < 20) {
          failures.add(r.toString());
        }
      }
    }
  }

  /** Reads the lag histogram from /actuator/prometheus with the bearer token. */
  private static final class Scraper {
    private final HttpClient http =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final URI uri;
    private final String bearer;

    Scraper(int port, String token) {
      this.uri = URI.create("http://127.0.0.1:" + port + "/actuator/prometheus");
      this.bearer = "Bearer " + token;
    }

    Histogram lag() throws IOException, InterruptedException {
      HttpResponse<String> r =
          http.send(
              HttpRequest.newBuilder(uri)
                  .header("Authorization", bearer)
                  .timeout(Duration.ofSeconds(30))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(r.statusCode()).as("GET /actuator/prometheus").isEqualTo(200);
      return Histogram.parse(r.body());
    }
  }

  /**
   * The cumulative buckets of {@code argos_ingest_lag_seconds} (seconds → count) and the server's
   * exported percentiles (the {@code quantile="..."} series), when present.
   */
  record Histogram(TreeMap<Double, Double> buckets, Map<String, Double> quantiles, double max) {

    private static final Pattern BUCKET =
        Pattern.compile("^" + LAG + "_bucket\\{(.*)\\}\\s+(\\S+)\\s*$");
    private static final Pattern QUANTILE =
        Pattern.compile("^" + LAG + "[a-z_]*\\{(.*quantile=\"([0-9.]+)\".*)\\}\\s+(\\S+)\\s*$");
    private static final Pattern MAX = Pattern.compile("^" + LAG + "_max\\{.*\\}\\s+(\\S+)\\s*$");
    private static final Pattern LE = Pattern.compile("le=\"([^\"]+)\"");

    static Histogram parse(String text) {
      TreeMap<Double, Double> buckets = new TreeMap<>();
      Map<String, Double> quantiles = new TreeMap<>();
      double max = Double.NaN;
      for (String line : text.lines().toList()) {
        Matcher b = BUCKET.matcher(line);
        if (b.matches()) {
          Matcher le = LE.matcher(b.group(1));
          if (le.find()) {
            double bound =
                le.group(1).equals("+Inf")
                    ? Double.POSITIVE_INFINITY
                    : Double.parseDouble(le.group(1));
            buckets.merge(bound, Double.parseDouble(b.group(2)), Double::sum);
          }
          continue;
        }
        Matcher q = QUANTILE.matcher(line);
        if (q.matches()) {
          quantiles.put(q.group(2), Double.parseDouble(q.group(3)));
          continue;
        }
        Matcher m = MAX.matcher(line);
        if (m.matches()) {
          max = Double.parseDouble(m.group(1));
        }
      }
      return new Histogram(buckets, quantiles, max);
    }

    /** Observations between {@code earlier} and this scrape. */
    Histogram minus(Histogram earlier) {
      TreeMap<Double, Double> delta = new TreeMap<>();
      buckets.forEach((le, c) -> delta.put(le, c - earlier.buckets.getOrDefault(le, 0.0)));
      return new Histogram(delta, quantiles, max);
    }

    /** Observations in total. */
    double count() {
      return buckets.isEmpty() ? 0 : buckets.lastEntry().getValue();
    }

    /**
     * The {@code q} quantile in seconds, linearly interpolated inside its bucket (the upper bound
     * of the last finite bucket for the +Inf bucket); NaN without observations.
     */
    double quantile(double q) {
      double total = count();
      if (total <= 0) {
        return Double.NaN;
      }
      double rank = q * total;
      double prevBound = 0;
      double prevCount = 0;
      for (Map.Entry<Double, Double> e : buckets.entrySet()) {
        if (e.getValue() >= rank) {
          if (e.getKey().isInfinite()) {
            return prevBound;
          }
          double inBucket = e.getValue() - prevCount;
          double fraction = inBucket <= 0 ? 1 : (rank - prevCount) / inBucket;
          return prevBound + (e.getKey() - prevBound) * fraction;
        }
        prevBound = e.getKey();
        prevCount = e.getValue();
      }
      return prevBound;
    }
  }

  private static String report(
      boolean enforce,
      long elapsedS,
      long measuredTx,
      long sent,
      long profiles,
      long steps,
      long stepRows,
      long statRows,
      long samples,
      long notOk,
      List<String> failures,
      Map<String, Long> totals,
      Histogram window,
      double p50,
      double p99,
      Histogram atEnd,
      Histogram drained,
      List<Long> acks) {
    StringBuilder sb = new StringBuilder();
    sb.append("# Ingest lag under 1,000 TPS (WO-21, 03 §3.2, N-15)\n\n");
    sb.append(
        enforce
            ? "Enforced run (-Pserver.load.enforce=true): p99 <= 5 s is asserted.\n\n"
            : "**Reference, shared laptop (INVALID as a measurement)**: p99 <= 5 s is not asserted"
                + " (-Pserver.load.enforce=true asserts it on a quiet machine).\n\n");
    sb.append("| Item | Value |\n|---|---|\n");
    row(sb, "Date (UTC)", Instant.now().toString());
    row(
        sb,
        "Machine",
        System.getProperty("os.name")
            + " "
            + System.getProperty("os.arch")
            + ", "
            + Runtime.getRuntime().availableProcessors()
            + " logical CPUs (test JVM view); server: boot jar, child JVM, -Xmx2g");
    row(
        sb,
        "Load",
        INSTANCES
            + " instances x "
            + TX_PER_INSTANCE_PER_SECOND
            + " tx/s, profiles "
            + CAPTURE_RATE
            + " (5 steps each), 25 statistics rows per 10 s window, 20 metric samples per 5 s, per instance");
    row(
        sb,
        "Duration",
        "warm-up "
            + WARM_UP.toSeconds()
            + " s + measured "
            + MEASURED.toSeconds()
            + " s ("
            + elapsedS
            + " s in total)");
    row(
        sb,
        "Transactions in the measured window",
        String.format(
            Locale.ROOT,
            "%,d (%.0f tx/s)",
            measuredTx,
            measuredTx / (double) MEASURED.toSeconds()));
    row(
        sb,
        "Sent in total",
        String.format(
            Locale.ROOT,
            "%,d transactions, %,d profiles (%,d steps), %,d statistics rows, %,d metric samples",
            sent,
            profiles,
            steps,
            statRows,
            samples));
    row(sb, "Calls not ACK_OK", Long.toString(notOk));
    row(sb, "Loaded (argos_reader)", totals.toString() + "; profile_step " + stepRows);
    row(sb, "argos_ingest_lag_seconds p50 (measured window, histogram)", ms(p50));
    row(sb, "argos_ingest_lag_seconds p99 (measured window, histogram)", ms(p99));
    row(sb, "Observations in the window", String.format(Locale.ROOT, "%,.0f", window.count()));
    row(
        sb,
        "Server percentiles at the end (decaying window)",
        quantiles(atEnd) + "; max " + atEnd.max() + " s");
    row(sb, "Server percentiles after the drain", quantiles(drained));
    if (!acks.isEmpty()) {
      row(
          sb,
          "Ack latency (client, all calls)",
          "p50 "
              + acks.get((int) Math.ceil(0.5 * acks.size()) - 1)
              + " ms, p99 "
              + acks.get((int) Math.ceil(0.99 * acks.size()) - 1)
              + " ms, max "
              + acks.get(acks.size() - 1)
              + " ms");
    }
    if (!failures.isEmpty()) {
      sb.append("\nFirst failures:\n\n");
      failures.stream()
          .limit(20)
          .forEach(f -> sb.append("- ").append(f.replace('\n', ' ')).append('\n'));
    }
    sb.append(
        "\nMethod: the lag is the server's Timer argos.ingest.lag (receive -> ClickHouse insert"
            + " acknowledged, one observation per WAL record and table) scraped from"
            + " /actuator/prometheus with the metrics bearer"
            + " token at the start and the end of the measured window; p50/p99 are interpolated in"
            + " the histogram buckets of the difference.\n");
    return sb.toString();
  }

  private static String quantiles(Histogram h) {
    return h.quantiles().isEmpty()
        ? "no quantile series exported (histogram buckets only)"
        : h.quantiles() + " s";
  }

  private static void row(StringBuilder sb, String item, String value) {
    sb.append("| ").append(item).append(" | ").append(value.replace("|", "\\|")).append(" |\n");
  }

  private static String ms(double seconds) {
    return Double.isNaN(seconds) ? "n/a" : String.format(Locale.ROOT, "%.0f ms", seconds * 1000);
  }
}
