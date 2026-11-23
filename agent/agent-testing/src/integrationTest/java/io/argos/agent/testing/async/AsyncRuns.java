/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.async;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.collector.Collected;
import io.argos.agent.testing.collector.FakeCollector;
import io.argos.agent.testing.export.TransactionRecord;
import io.argos.agent.testing.launcher.AppLauncher;
import io.argos.agent.testing.launcher.HttpResult;
import io.argos.agent.testing.launcher.Jdk;
import io.argos.agent.testing.launcher.RunningApp;
import io.argos.agent.testing.launcher.SampleApp;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;

/**
 * Runs of the WO-15 async suite (package {@code async}): the shop jar apps of {@code -Pagent.e2e}
 * with the release agent jar over gRPC to a TLS {@link FakeCollector}, {@code async.step=true}
 * (ASYNC steps, 10 §6.6) and {@code sampling.profile.normal-rate=1.0} (every transaction carries
 * its steps); a run on the platform request threads and, for Boot 3.x/4.x on JDK 21+, one with
 * {@code SAMPLE_VT=true} (virtual request threads and {@code @Async} executor, 10 §8). The agent
 * log and console of a run are kept in {@code argos.test.reportDir} as {@code wo15-<name>-*}.
 *
 * <p>The SQL texts are the sample app contract of {@code
 * sample-apps/common/.../async/AsyncSqlWork.java} (one statement per async branch).
 */
final class AsyncRuns {
  /** Wait for transactions a test expects. */
  static final Duration WAIT = Duration.ofSeconds(30);

  /** Jar apps that implement the shop catalog (18 §4.1). */
  static final List<String> SHOP_JAR_APPS = List.of("shop-boot2", "shop-boot3", "shop-boot4");

  /** Apps with {@code SAMPLE_VT=true} (Boot 3.2+). */
  static final List<String> VT_APPS = List.of("shop-boot3", "shop-boot4");

  static final String EXECUTOR_PATH = "/api/async/executor";
  static final String SQL_WORK = EXECUTOR_PATH + "?work=sql";

  /** {@code AsyncSqlWork.ASYNC_METHOD_SQL}: the {@code @Async} method. */
  static final String ASYNC_METHOD_SQL = "SELECT COUNT(*) FROM products";

  /** {@code AsyncSqlWork.SUPPLY_ASYNC_SQL}: {@code CompletableFuture.supplyAsync}. */
  static final String SUPPLY_ASYNC_SQL = "SELECT COUNT(*) FROM orders";

  /** {@code AsyncSqlWork.SCHEDULED_SQL}: a {@code ScheduledThreadPoolExecutor} task. */
  static final String SCHEDULED_SQL = "SELECT COUNT(*) FROM customers";

  /** {@code AsyncSqlWork.POOL_SQL}: the app's one-thread {@code ThreadPoolExecutor}. */
  static final String POOL_SQL = "SELECT COUNT(*) FROM order_items";

  /** {@code AsyncSqlWork.VIRTUAL_SQL}: {@code newVirtualThreadPerTaskExecutor} (JDK 21+). */
  static final String VIRTUAL_SQL = "SELECT COUNT(*) FROM audit_log";

  /** {@code AsyncSqlWork.DETACHED_SQL}: {@code work=detached}, no transaction. */
  static final String DETACHED_SQL = "SELECT COUNT(*) FROM customers WHERE id < 0";

  /** {@code AsyncSqlWork.POOL_THREAD}. */
  static final String POOL_THREAD = "async-sql-pool-1";

  private AsyncRuns() {}

  /** One app on one JDK. */
  record Combo(String app, int jdk) {
    static Combo parse(String text) {
      String[] parts = text.strip().split("@", -1);
      if (parts.length != 2) {
        throw new IllegalArgumentException("expected <app>@<jdk>, got " + text);
      }
      return new Combo(parts[0], Integer.parseInt(parts[1]));
    }

    /** Whether this combination has the {@code SAMPLE_VT=true} run (Boot 3.x/4.x, JDK 21+). */
    boolean virtualThreads() {
      return VT_APPS.contains(app) && jdk >= 21;
    }

    @Override
    public String toString() {
      return app + "@" + jdk;
    }
  }

  /** The shop jar apps of {@code -Pagent.e2e} (skips the factory without one). */
  static List<Combo> jarCombos() {
    List<Combo> combos = new ArrayList<>();
    for (String item : System.getProperty("argos.test.e2e", "shop-boot3@21").split(",", -1)) {
      if (!item.isBlank() && !item.strip().equals("none")) {
        Combo combo = Combo.parse(item);
        if (SHOP_JAR_APPS.contains(combo.app())) {
          combos.add(combo);
        }
      }
    }
    Assumptions.assumeFalse(
        combos.isEmpty(),
        "no shop jar app (" + SHOP_JAR_APPS + ") in -Pagent.e2e, e.g. shop-boot3@21");
    return combos;
  }

  static String required(String name) {
    String value = System.getProperty(name);
    assertThat(value)
        .as("system property %s (set by agent-testing integrationTest)", name)
        .isNotBlank();
    return value;
  }

  /** Agent settings of every run of the suite. */
  static Map<String, String> agentSettings() {
    Map<String, String> settings = new LinkedHashMap<>();
    settings.put("sampling.profile.normal-rate", "1.0");
    settings.put("async.step", "true");
    return settings;
  }

  /** Starts {@code combo} (with virtual threads when {@code vt}) under {@code workRoot}. */
  static Run start(Combo combo, boolean vt, Path workRoot) throws Exception {
    String name = combo.app() + "-jdk" + combo.jdk() + (vt ? "-vt" : "-platform");
    FakeCollector collector = FakeCollector.builder().tls(true).start();
    long begin = System.nanoTime();
    Run run;
    try {
      AppLauncher launcher =
          AppLauncher.of(SampleApp.named(combo.app()), Jdk.of(combo.jdk()))
              .workDir(workRoot.resolve(name))
              .readyTimeout(Duration.ofMinutes(4))
              .virtualThreads(vt)
              .withAgent(Path.of(required("argos.test.releaseAgentJar")), agentSettings());
      launcher.collector(collector);
      run = new Run(name, combo, vt, launcher.start(Duration.ofMinutes(4)), collector);
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
    System.out.println(
        "[wo15-run] "
            + name
            + ": port "
            + run.app.port()
            + ", session established after "
            + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin)
            + " ms");
    return run;
  }

  /** {@code sql} without case and white space, for comparing SQL step names (normalized SQL). */
  static String canonical(String sql) {
    return sql == null ? "" : sql.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
  }

  /** The canonical SQL texts of the async branches on {@code jdk}. */
  static Set<String> branchStatements(int jdk) {
    Set<String> statements = new HashSet<>();
    for (String sql : List.of(ASYNC_METHOD_SQL, SUPPLY_ASYNC_SQL, SCHEDULED_SQL, POOL_SQL)) {
      statements.add(canonical(sql));
    }
    if (jdk >= 21) {
      statements.add(canonical(VIRTUAL_SQL));
    }
    return statements;
  }

  /** True when the step is an SQL step of the {@code work=detached} statement. */
  static boolean isDetached(TransactionRecord.StepRecord step) {
    return canonical(step.name()).startsWith("selectcount(*)fromcustomerswhereid<");
  }

  /** The ASYNC step that encloses {@code step} (its parent chain), or null. */
  static TransactionRecord.StepRecord asyncAncestor(
      TransactionRecord tx, TransactionRecord.StepRecord step) {
    Map<Integer, TransactionRecord.StepRecord> bySeq = new LinkedHashMap<>();
    for (TransactionRecord.StepRecord s : tx.steps()) {
      bySeq.put(s.seq(), s);
    }
    Integer parent = step.parent();
    for (int depth = 0; parent != null && parent >= 0 && depth < 64; depth++) {
      TransactionRecord.StepRecord p = bySeq.get(parent);
      if (p == null) {
        return null;
      }
      if ("ASYNC".equals(p.type())) {
        return p;
      }
      parent = p.parent();
    }
    return null;
  }

  /**
   * The top-level fields of a flat JSON object answer (strings, numbers, booleans, null, lists of
   * strings): enough for the sample app's answers without a JSON library.
   */
  static final class Answer {
    private static final Pattern FIELD =
        Pattern.compile(
            "\"([A-Za-z0-9_]+)\":(\"(?:[^\"\\\\]|\\\\.)*\"|-?[0-9]+(?:\\.[0-9]+)?|true|false|null"
                + "|\\[[^\\]]*\\])");

    final String body;
    final Map<String, String> raw = new LinkedHashMap<>();

    Answer(String body) {
      this.body = body;
      Matcher m = FIELD.matcher(body);
      while (m.find()) {
        raw.putIfAbsent(m.group(1), m.group(2));
      }
    }

    boolean has(String key) {
      return raw.containsKey(key) && !"null".equals(raw.get(key));
    }

    String string(String key) {
      String value = raw.get(key);
      assertThat(value).as("field %s of %s", key, body).isNotNull();
      assertThat(value).as("string field %s of %s", key, body).startsWith("\"");
      return unquote(value);
    }

    long number(String key) {
      String value = raw.get(key);
      assertThat(value).as("field %s of %s", key, body).isNotNull().matches("-?[0-9]+");
      return Long.parseLong(value);
    }

    boolean bool(String key) {
      String value = raw.get(key);
      assertThat(value).as("field %s of %s", key, body).isIn("true", "false");
      return Boolean.parseBoolean(value);
    }

    List<String> strings(String key) {
      String value = raw.get(key);
      assertThat(value).as("list field %s of %s", key, body).isNotNull().startsWith("[");
      List<String> items = new ArrayList<>();
      Matcher m = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(value);
      while (m.find()) {
        items.add(m.group(1).replace("\\\"", "\"").replace("\\\\", "\\"));
      }
      return items;
    }

    private static String unquote(String quoted) {
      return quoted.substring(1, quoted.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
    }

    @Override
    public String toString() {
      return body;
    }
  }

  /** One started app with its collector. */
  static final class Run implements AutoCloseable {
    final String name;
    final Combo combo;
    final boolean vt;
    final RunningApp app;
    final FakeCollector collector;
    private final Set<String> seenTxIds = new HashSet<>();

    Run(String name, Combo combo, boolean vt, RunningApp app, FakeCollector collector) {
      this.name = name;
      this.combo = combo;
      this.vt = vt;
      this.app = app;
      this.collector = collector;
    }

    Collected collected() {
      return app.collected();
    }

    /** Waits (at most 60 s) for the agent's collector session (see HttpClientRuns). */
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

    /** {@code GET path}, status checked, the answer parsed. */
    Answer get(String path, int status) throws IOException, InterruptedException {
      HttpResult result = app.get(path);
      assertThat(result.status())
          .as("GET %s on %s: %s", path, name, result.body())
          .isEqualTo(status);
      return new Answer(result.body());
    }

    /**
     * The {@code count} transactions of {@code path} (bare path) that no earlier call of this
     * method returned, oldest start first (asserts exactly {@code count} new ones).
     */
    List<TransactionRecord> newTransactions(String path, int count) {
      List<TransactionRecord> matching =
          collected()
              .awaitTransactions(
                  tx -> path.equals(tx.path()) && !seenTxIds.contains(tx.txId()), count, WAIT);
      assertThat(matching)
          .as("new transactions of %s on %s (one per request)", path, name)
          .hasSize(count);
      List<TransactionRecord> sorted = new ArrayList<>(matching);
      sorted.sort(
          Comparator.comparing(
              TransactionRecord::startEpochMs, Comparator.nullsLast(Comparator.naturalOrder())));
      for (TransactionRecord tx : sorted) {
        seenTxIds.add(tx.txId());
      }
      return sorted;
    }

    /** The texts of the THREAD dictionary entries the collector received. */
    List<String> threadTexts() {
      return collector.dictionaryEntries().stream()
          .filter(e -> e.kind().equals("THREAD"))
          .map(Collected.DictionaryItem::text)
          .toList();
    }

    String describe() {
      return name + "\n" + app.describe();
    }

    /** Stops the app and the collector; keeps the agent log and console. */
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
            dir.resolve("wo15-" + name + "-agent.log"), app.agentLog(), StandardCharsets.UTF_8);
        Files.write(
            dir.resolve("wo15-" + name + "-stdout.log"), app.stdoutLines(), StandardCharsets.UTF_8);
      } catch (IOException e) {
        System.out.println("[wo15-run] cannot keep the evidence of " + name + ": " + e);
      }
    }
  }

  /** Deletes a run tree (best effort). */
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
}
