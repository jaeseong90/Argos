/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.batch;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.collector.BatchKind;
import io.argos.agent.testing.collector.Collected;
import io.argos.agent.testing.collector.FakeCollector;
import io.argos.agent.testing.collector.ReceivedBatch;
import io.argos.agent.testing.export.TransactionRecord;
import io.argos.agent.testing.launcher.AppLauncher;
import io.argos.agent.testing.launcher.HttpResult;
import io.argos.agent.testing.launcher.Jdk;
import io.argos.agent.testing.launcher.RunningApp;
import io.argos.agent.testing.launcher.SampleApp;
import io.argos.agent.testing.launcher.SelfDiagnostics;
import io.argos.proto.v1.ProfileBatch;
import io.argos.proto.v1.TransactionProfile;
import io.argos.sample.extstub.ExtStub;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Shared runs of the WO-18 batch suite (package {@code batch}): the {@code shop-batch} combinations
 * of {@code -Pagent.e2e} (for example {@code shop-batch@17,shop-batch@21}) with the release agent
 * jar over gRPC to a TLS {@link FakeCollector}, with an in-process ext-stub for the Quartz report
 * job's outgoing call. A run is started on first use and reused by every test class of the package
 * (test/README.md §5); at most {@value #MAX_ALIVE} are alive (the laptop's memory). Tests count the
 * transactions their own actions add, so the class order does not matter.
 *
 * <p>Every run keeps the harness defaults (self-diagnostics file, transform list) and adds {@code
 * sampling.profile.normal-rate=1.0}, so that every transaction carries its profile. The app runs
 * with {@code SAMPLE_BATCH_PER_ITEM_SQL=true} (settleStep prices every item with its own query:
 * 20,000 statements per run, the N+1 shape of the summary mode) and a 3-second long-running
 * {@code @Scheduled} method. The agent log and console are kept in {@code argos.test.reportDir} as
 * {@code wo18-<name>-*} when the run stops.
 */
final class BatchRuns implements BeforeAllCallback {
  /** The app of the suite. */
  static final String APP = "shop-batch";

  /** Wait for transactions a test expects. */
  static final Duration WAIT = Duration.ofSeconds(40);

  /** Runs alive at the same time. */
  static final int MAX_ALIVE = 2;

  /** Items of settlementJob's chunk step (shop-batch README). */
  static final int CHUNK_ITEMS = 20_000;

  /** The per-item query of {@code SAMPLE_BATCH_PER_ITEM_SQL=true} (shop-batch ProductPrices). */
  static final String PRICE_QUERY = "SELECT price FROM products WHERE id = ?";

  /** {@code sampling.profile.max-steps} default (docs/agent-config.md). */
  static final int MAX_STEPS = 1_000;

  /** Service names of 10 §6.8 for shop-batch's entry points (WO-18 lane A1). */
  static final String SCHEDULED_CLASS = "io.argos.sample.batch.scheduled.ScheduledJobs";

  static final List<String> SCHEDULED_SERVICES =
      List.of(
          "BATCH " + SCHEDULED_CLASS + "#refreshOrderStats",
          "BATCH " + SCHEDULED_CLASS + "#rollupTopCustomers",
          "BATCH " + SCHEDULED_CLASS + "#settlementScan");

  /** The Quartz group of shop-batch (BatchCatalog.QUARTZ_GROUP). */
  static final String QUARTZ_GROUP = "shop-batch";

  static final List<String> QUARTZ_JOBS = List.of("quartzReportJob", "quartzCleanupJob");

  static final String PREPARE_STEP = "BATCH settlementJob.prepareStep";

  static final String SETTLE_STEP = "BATCH settlementJob.settleStep";

  /** Normalized name of the app's detached job threads ({@code shop-batch-detached-<runId>}). */
  static final String DETACHED_THREAD = "shop-batch-detached-";

  private static final Pattern RUN_ID = Pattern.compile("\"runId\"\\s*:\\s*(\\d+)");

  private static final Pattern STATE = Pattern.compile("\"state\"\\s*:\\s*\"([A-Z]+)\"");

  private static final ExtensionContext.Namespace NAMESPACE =
      ExtensionContext.Namespace.create(BatchRuns.class);

  private static volatile Cache cache;

  /** App and agent configurations of the suite. */
  enum Profile {
    /** Per-item SQL, short long-running method, profiles of every transaction. */
    MAIN,
    /**
     * {@link #MAIN} with {@code SAMPLE_VT=true} (Tomcat, {@code @Scheduled} on virtual threads).
     */
    VT;

    Map<String, String> agentSettings() {
      Map<String, String> settings = new LinkedHashMap<>();
      settings.put("sampling.profile.normal-rate", "1.0");
      return settings;
    }

    Map<String, String> appEnv() {
      Map<String, String> env = new LinkedHashMap<>();
      env.put("SAMPLE_BATCH_PER_ITEM_SQL", "true");
      env.put("SAMPLE_BATCH_LONG_DURATION_MS", "3000");
      if (this == VT) {
        env.put("SAMPLE_VT", "true");
      }
      return env;
    }
  }

  /** One app on one JDK. */
  record Combo(String app, int jdk) {
    static Combo parse(String text) {
      String[] parts = text.strip().split("@", -1);
      if (parts.length != 2) {
        throw new IllegalArgumentException("expected <app>@<jdk>, got " + text);
      }
      return new Combo(parts[0], Integer.parseInt(parts[1]));
    }

    @Override
    public String toString() {
      return app + "@" + jdk;
    }
  }

  /** The shop-batch combinations of {@code -Pagent.e2e} (skips the factory without one). */
  static List<Combo> combos() {
    List<Combo> combos = new ArrayList<>();
    for (String item : System.getProperty("argos.test.e2e", "shop-boot3@21").split(",", -1)) {
      if (!item.isBlank() && !item.strip().equals("none")) {
        Combo combo = Combo.parse(item);
        if (combo.app().equals(APP)) {
          combos.add(combo);
        }
      }
    }
    Assumptions.assumeFalse(
        combos.isEmpty(),
        "no "
            + APP
            + " combination in -Pagent.e2e (the default matrix has none), e.g."
            + " -Pagent.e2e=shop-batch@17,shop-batch@21");
    return combos;
  }

  /** The first combination on JDK 21 or later (virtual threads), or none. */
  static List<Combo> virtualThreadCombo() {
    return combos().stream().filter(c -> c.jdk() >= 21).limit(1).toList();
  }

  @Override
  public void beforeAll(ExtensionContext context) {
    cache = context.getRoot().getStore(NAMESPACE).getOrComputeIfAbsent(Cache.class);
  }

  /** The run of {@code combo} with {@code profile}, started on first use. */
  static Run run(Combo combo, Profile profile) throws Exception {
    Cache current = cache;
    assertThat(current).as("@ExtendWith(BatchRuns.class) on the test class").isNotNull();
    return current.get(combo, profile);
  }

  static String required(String name) {
    String value = System.getProperty(name);
    assertThat(value)
        .as("system property %s (set by agent-testing integrationTest)", name)
        .isNotBlank();
    return value;
  }

  /** Polls {@code condition} every {@code everyMillis} until it holds or {@code timeout} passes. */
  static boolean await(BooleanSupplier condition, Duration timeout, long everyMillis)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (true) {
      if (condition.getAsBoolean()) {
        return true;
      }
      if (System.nanoTime() > deadline) {
        return false;
      }
      Thread.sleep(everyMillis);
    }
  }

  /**
   * True for a statement text that is the per-item price query (any spelling of the normalizer).
   */
  static boolean isPriceQuery(String sql) {
    return sql != null
        && sql.toUpperCase(Locale.ROOT).replaceAll("\\s+", "").contains("FROMPRODUCTSWHEREID=?");
  }

  /** {@code tx.field(name)} as a long (0 when absent). */
  static long number(TransactionRecord tx, String name) {
    Object value = tx.field(name);
    return value instanceof Number n ? n.longValue() : 0L;
  }

  /** Executions a recorded step stands for (11 {@code repeat_count}, at least 1). */
  static long executions(TransactionRecord.StepRecord step) {
    Integer repeat = step.repeatCount();
    return repeat == null || repeat < 1 ? 1 : repeat;
  }

  /**
   * Executions of the price query in {@code tx}: recorded SQL steps (with their repeat counts) and
   * the SQL summary entries of that statement.
   */
  static long priceQueryExecutions(TransactionRecord tx) {
    return recordedPriceQueries(tx) + summarizedPriceQueries(tx);
  }

  static long recordedPriceQueries(TransactionRecord tx) {
    return tx.steps("SQL").stream()
        .filter(s -> isPriceQuery(s.name()))
        .mapToLong(BatchRuns::executions)
        .sum();
  }

  static long summarizedPriceQueries(TransactionRecord tx) {
    return tx.summary().stream()
        .filter(e -> "SQL".equals(e.type()) && isPriceQuery(e.name()))
        .mapToLong(e -> e.count() == null ? 0 : e.count())
        .sum();
  }

  static void deleteTree(Path root) {
    if (root == null || !Files.exists(root)) {
      return;
    }
    try (Stream<Path> files = Files.walk(root)) {
      for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
        try {
          Files.deleteIfExists(file);
        } catch (IOException e) {
          // left behind; the next clean of the temp directory removes it
        }
      }
    } catch (IOException e) {
      // nothing more to do
    }
  }

  /** The runs of the engine execution (closed with the root store). */
  static final class Cache implements AutoCloseable {
    private final Map<String, Run> runs = new LinkedHashMap<>(4, 0.75f, true);
    private ExtStub extStub;
    private Path workRoot;
    private int starts;

    synchronized Run get(Combo combo, Profile profile) throws Exception {
      String key = combo + "/" + profile;
      Run run = runs.get(key);
      if (run != null && run.app.isAlive()) {
        return run;
      }
      if (run != null) {
        runs.remove(key).close();
      }
      while (runs.size() >= MAX_ALIVE) {
        String eldest = runs.keySet().iterator().next();
        runs.remove(eldest).close();
      }
      run = start(combo, profile);
      runs.put(key, run);
      return run;
    }

    private Run start(Combo combo, Profile profile) throws Exception {
      if (extStub == null) {
        extStub = ExtStub.start(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 16);
        workRoot = Files.createTempDirectory("wo18-batch-");
      }
      starts++;
      String name =
          combo.app()
              + "-jdk"
              + combo.jdk()
              + "-"
              + profile.name().toLowerCase(Locale.ROOT)
              + "-"
              + starts;
      AppLauncher launcher =
          AppLauncher.of(SampleApp.named(combo.app()), Jdk.of(combo.jdk()))
              .withEnv("SAMPLE_EXT_URL", extStub.baseUri().toString())
              .workDir(workRoot.resolve(name))
              .readyTimeout(Duration.ofMinutes(4))
              .withAgent(
                  Path.of(required("argos.test.releaseAgentJar")),
                  new LinkedHashMap<>(profile.agentSettings()));
      profile.appEnv().forEach(launcher::withEnv);
      FakeCollector collector = FakeCollector.builder().tls(true).start();
      launcher.collector(collector);
      Run run;
      try {
        long begin = System.nanoTime();
        RunningApp running = launcher.start(Duration.ofMinutes(4));
        System.out.println(
            "[wo18-run] "
                + name
                + ": port "
                + running.port()
                + ", ready in "
                + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin)
                + " ms");
        run = new Run(name, combo, profile, running, collector);
      } catch (Exception | Error e) {
        collector.close();
        throw e;
      }
      try {
        run.awaitEstablished();
      } catch (InterruptedException | RuntimeException | Error e) {
        run.close();
        throw e;
      }
      return run;
    }

    @Override
    public synchronized void close() {
      for (Run run : runs.values()) {
        run.close();
      }
      runs.clear();
      if (extStub != null) {
        extStub.close();
      }
      deleteTree(workRoot);
    }
  }

  /** One started shop-batch with its agent configuration. */
  static final class Run implements AutoCloseable {
    final String name;
    final Combo combo;
    final Profile profile;
    final RunningApp app;
    final FakeCollector collector;

    Run(String name, Combo combo, Profile profile, RunningApp app, FakeCollector collector) {
      this.name = name;
      this.combo = combo;
      this.profile = profile;
      this.app = app;
      this.collector = collector;
    }

    Collected collected() {
      return app.collected();
    }

    List<TransactionRecord> transactions(Predicate<TransactionRecord> filter) {
      return collected().transactions().stream().filter(filter).toList();
    }

    /** Waits (at most 60 s) for the agent's {@code collector session established} log line. */
    void awaitEstablished() throws InterruptedException {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
      while (collector.batches().isEmpty()
          && !app.agentLog().contains("collector session established")) {
        assertThat(System.nanoTime() < deadline && app.isAlive())
            .as("collector session of %s within 60 s\n%s", name, app.describe())
            .isTrue();
        Thread.sleep(100);
      }
    }

    /**
     * Waits until at least {@code count} transactions match {@code filter} and every one of them is
     * complete (profile and dictionary texts arrived); returns them.
     */
    List<TransactionRecord> await(Predicate<TransactionRecord> filter, int count, Duration timeout)
        throws InterruptedException {
      long deadline = System.nanoTime() + timeout.toNanos();
      List<TransactionRecord> matching = transactions(filter);
      while ((matching.size() < count || !matching.stream().allMatch(Run::complete))
          && System.nanoTime() < deadline) {
        Thread.sleep(250);
        matching = transactions(filter);
      }
      return matching;
    }

    /** The profile of a sampled transaction arrived and its texts are resolved. */
    static boolean complete(TransactionRecord tx) {
      if (Boolean.TRUE.equals(tx.profileCaptured()) && tx.totalSteps() == null) {
        return false;
      }
      if (tx.service() != null && tx.service().startsWith("#")) {
        return false;
      }
      for (TransactionRecord.StepRecord step : tx.steps()) {
        if (step.name() != null && step.name().startsWith("#")) {
          return false;
        }
      }
      for (TransactionRecord.SummaryRecord entry : tx.summary()) {
        if (entry.name() != null && entry.name().startsWith("#")) {
          return false;
        }
      }
      return true;
    }

    /** {@code method path}, asserting {@code status}. */
    HttpResult call(String method, String path, int status)
        throws IOException, InterruptedException {
      HttpResult result = app.call(method, path, Map.of());
      assertThat(result.status())
          .as("%s %s on %s: %s", method, path, name, result.body())
          .isEqualTo(status);
      return result;
    }

    /**
     * Starts {@code job} detached ({@code POST /batch/run/{job}?detached=true}) and waits until the
     * app reports it DONE; returns the run id.
     */
    long runDetached(String job) throws Exception {
      HttpResult started = call("POST", "/batch/run/" + job + "?detached=true", 202);
      Matcher id = RUN_ID.matcher(started.body());
      assertThat(id.find()).as("runId in %s", started.body()).isTrue();
      long runId = Long.parseLong(id.group(1));
      long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
      String state = "RUNNING";
      String body = "";
      // awaitMs stays below the harness's 30 s request timeout.
      while ("RUNNING".equals(state) && System.nanoTime() < deadline) {
        body = call("GET", "/batch/executions/" + runId + "?awaitMs=20000", 200).body();
        Matcher m = STATE.matcher(body);
        state = m.find() ? m.group(1) : "UNKNOWN";
      }
      assertThat(state)
          .as("detached run %d of %s on %s: %s", runId, job, name, body)
          .isEqualTo("DONE");
      assertThat(body).as("detached run %d answer", runId).contains("\"COMPLETED\"");
      return runId;
    }

    /** A self-diagnostics snapshot written after now. */
    SelfDiagnostics freshSelfDiagnostics() {
      SelfDiagnostics diagnostics = app.awaitSelfDiagnostics(Instant.now(), Duration.ofSeconds(15));
      assertThat(diagnostics.available()).as(diagnostics.reason()).isTrue();
      return diagnostics;
    }

    /** Every statistics window received so far. */
    List<Collected.StatWindow> statWindows() {
      return collected().statWindows().orElseThrow();
    }

    /** Serialized bytes of the profile of {@code tx} as the collector received it, or -1. */
    long profileBytes(TransactionRecord tx) {
      long txId = Long.parseUnsignedLong(tx.txId());
      for (ReceivedBatch batch : collector.batches(BatchKind.PROFILES)) {
        if (batch.body() instanceof ProfileBatch profiles) {
          for (TransactionProfile p : profiles.getProfilesList()) {
            if (p.getTxId() == txId) {
              return p.getSerializedSize();
            }
          }
        }
      }
      return -1;
    }

    String describe() {
      return name + "\n" + app.describe();
    }

    /**
     * Stops the app and the collector; keeps the agent log and console as {@code wo18-<name>-*}.
     */
    @Override
    public void close() {
      try {
        app.close();
        keepEvidence();
      } finally {
        collector.close();
      }
    }

    private void keepEvidence() {
      String reportDir = System.getProperty("argos.test.reportDir");
      if (reportDir == null || reportDir.isBlank()) {
        return;
      }
      try {
        Path dir = Files.createDirectories(Path.of(reportDir));
        Files.writeString(
            dir.resolve("wo18-" + name + "-agent.log"), app.agentLog(), StandardCharsets.UTF_8);
        Files.write(
            dir.resolve("wo18-" + name + "-stdout.log"), app.stdoutLines(), StandardCharsets.UTF_8);
      } catch (IOException e) {
        System.out.println("[wo18-run] cannot keep the evidence of " + name + ": " + e);
      }
    }
  }

  /** Instantiated by JUnit ({@code @ExtendWith(BatchRuns.class)}). */
  BatchRuns() {}
}
