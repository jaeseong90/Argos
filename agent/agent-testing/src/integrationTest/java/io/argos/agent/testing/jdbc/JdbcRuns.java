/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Shared runs of the WO-13 JDBC suite (package {@code jdbc}): the shop jar apps of {@code
 * -Pagent.e2e} with the release agent jar over gRPC to a TLS {@link FakeCollector}, against one
 * database at a time ({@link JdbcDatabase}), in a few agent and app configurations ({@link
 * Profile}). A run is started on first use and reused by every test class of the package (one
 * factory per class, test/README.md §5); the database container lives until a test asks for another
 * database, then it and every run on it stop (containers one at a time, each at most 5 GiB). At
 * most {@value #MAX_ALIVE} runs are alive. Tests count the transactions their requests add, so the
 * class order does not matter.
 *
 * <p>Every run keeps the harness defaults (self-diagnostics file, transform list) and adds {@code
 * sampling.profile.normal-rate=1.0}, so that every transaction carries its steps. The run's agent
 * log and console are kept in {@code argos.test.reportDir} as {@code wo13-<name>-*} when it stops.
 */
final class JdbcRuns implements BeforeAllCallback {
  /** Wait for transactions a test expects. */
  static final Duration WAIT = Duration.ofSeconds(25);

  /** Runs alive at the same time. */
  static final int MAX_ALIVE = 4;

  /** Jar apps that implement the shop catalog (18 §4.1). */
  static final List<String> SHOP_JAR_APPS = List.of("shop-boot2", "shop-boot3", "shop-boot4");

  private static final ExtensionContext.Namespace NAMESPACE =
      ExtensionContext.Namespace.create(JdbcRuns.class);

  private static volatile Cache cache;

  /** Agent and app configurations of the suite. */
  enum Profile {
    /** Agent defaults (bind parameters {@code masked}), the app's default pool (HikariCP). */
    DEFAULT,
    /** {@code capture.sql-parameters=partial}, whitelisted column {@code email} (16 §6.7). */
    PARTIAL,
    /** {@code capture.sql-parameters=partial} without a whitelisted column of the seeds. */
    PARTIAL_OTHER_COLUMN,
    /** {@code capture.sql-parameters=full} (16 §6.7; the server drops it until WO-71, C-148). */
    FULL,
    /** SAMPLE_ROUTING_DS=true and SAMPLE_DRIVER_WRAPPER=true: five JDBC levels (C-40, D-24). */
    NESTED,
    /** SAMPLE_POOL=tomcat (Tomcat JDBC). */
    POOL_TOMCAT,
    /** SAMPLE_POOL=dbcp2 (Commons DBCP 2). */
    POOL_DBCP2,
    /** SAMPLE_SECOND_POOL=true: HikariCP pools shop-primary and a restarted shop-secondary. */
    SECOND_POOL,
    /** SAMPLE_SECOND_POOL=true with SAMPLE_POOL=tomcat (Tomcat JDBC pools of the same names). */
    SECOND_POOL_TOMCAT;

    Map<String, String> agentSettings() {
      Map<String, String> settings = new LinkedHashMap<>();
      settings.put("sampling.profile.normal-rate", "1.0");
      switch (this) {
        case PARTIAL -> {
          settings.put("capture.sql-parameters", "partial");
          settings.put("capture.sql-parameters.whitelist-columns", "email");
        }
        case PARTIAL_OTHER_COLUMN -> {
          settings.put("capture.sql-parameters", "partial");
          settings.put("capture.sql-parameters.whitelist-columns", "name");
        }
        case FULL -> settings.put("capture.sql-parameters", "full");
        default -> {}
      }
      return settings;
    }

    Map<String, String> appEnv() {
      return switch (this) {
        case NESTED -> Map.of("SAMPLE_ROUTING_DS", "true", "SAMPLE_DRIVER_WRAPPER", "true");
        case POOL_TOMCAT -> Map.of("SAMPLE_POOL", "tomcat");
        case POOL_DBCP2 -> Map.of("SAMPLE_POOL", "dbcp2");
        case SECOND_POOL -> Map.of("SAMPLE_SECOND_POOL", "true");
        case SECOND_POOL_TOMCAT -> Map.of("SAMPLE_SECOND_POOL", "true", "SAMPLE_POOL", "tomcat");
        default -> Map.of();
      };
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

    /**
     * Whether the app has the driver of {@code db}: shop-boot3 every one of the suite (WO-13),
     * shop-boot2 and shop-boot4 the drivers of WO-01 (H2, PostgreSQL, MySQL).
     */
    boolean supports(JdbcDatabase db) {
      if (app.equals("shop-boot3")) {
        return true;
      }
      return db == JdbcDatabase.H2 || db == JdbcDatabase.POSTGRES || db == JdbcDatabase.MYSQL;
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
      if (!item.isBlank()) {
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

  /** The combinations of {@code apps} among {@link #jarCombos()} (skips when there is none). */
  static List<Combo> combosOf(String... apps) {
    List<String> wanted = List.of(apps);
    List<Combo> combos = jarCombos().stream().filter(c -> wanted.contains(c.app())).toList();
    Assumptions.assumeFalse(
        combos.isEmpty(), "none of " + wanted + " in -Pagent.e2e, e.g. shop-boot3@21");
    return combos;
  }

  @Override
  public void beforeAll(ExtensionContext context) {
    cache = context.getRoot().getStore(NAMESPACE).getOrComputeIfAbsent(Cache.class);
  }

  /** The run of {@code combo} on {@code db} with {@code profile}, started on first use. */
  static Run run(Combo combo, JdbcDatabase db, Profile profile) throws Exception {
    Cache current = cache;
    assertThat(current).as("@ExtendWith(JdbcRuns.class) on the test class").isNotNull();
    return current.get(combo, db, profile);
  }

  /** The database server of {@code db} (started, and the previous one stopped, when needed). */
  static JdbcDatabase.Server server(JdbcDatabase db) throws Exception {
    Cache current = cache;
    assertThat(current).as("@ExtendWith(JdbcRuns.class) on the test class").isNotNull();
    return current.server(db);
  }

  static String required(String name) {
    String value = System.getProperty(name);
    assertThat(value)
        .as("system property %s (set by agent-testing integrationTest)", name)
        .isNotBlank();
    return value;
  }

  /** The runs and the one database of the engine execution (closed with the root store). */
  static final class Cache implements AutoCloseable {
    private final Map<String, Run> runs = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<String, Integer> starts = new HashMap<>();
    private JdbcDatabase.Server server;
    private ExtStub extStub;
    private Path workRoot;

    synchronized JdbcDatabase.Server server(JdbcDatabase db) throws Exception {
      if (server != null && server.db == db) {
        return server;
      }
      stopServer();
      long begin = System.nanoTime();
      server = db.start();
      System.out.println(
          "[wo13-db] "
              + server
              + " started in "
              + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin)
              + " ms (memory "
              + server.memoryUsage()
              + ")");
      return server;
    }

    private void stopServer() {
      if (server == null) {
        return;
      }
      // Every run on the old database stops with it.
      List<String> keys = new ArrayList<>(runs.keySet());
      for (String key : keys) {
        if (runs.get(key).db == server.db) {
          runs.remove(key).close();
        }
      }
      String usage = server.memoryUsage();
      server.close();
      System.out.println(
          "[wo13-db] " + server.db.id + " stopped (memory before stop " + usage + ")");
      server = null;
    }

    synchronized Run get(Combo combo, JdbcDatabase db, Profile profile) throws Exception {
      String key = combo + "/" + db.id + "/" + profile;
      Run run = runs.get(key);
      if (run != null && run.app.isAlive()) {
        return run;
      }
      if (run != null) {
        runs.remove(key).close();
      }
      JdbcDatabase.Server current = server(db);
      while (runs.size() >= MAX_ALIVE) {
        String eldest = runs.keySet().iterator().next();
        runs.remove(eldest).close();
      }
      run = start(combo, current, profile);
      runs.put(key, run);
      return run;
    }

    private Run start(Combo combo, JdbcDatabase.Server db, Profile profile) throws Exception {
      if (extStub == null) {
        extStub = ExtStub.start(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 16);
        workRoot = Files.createTempDirectory("wo13-jdbc-");
      }
      int start = starts.merge(combo + "/" + db.db.id + "/" + profile, 1, Integer::sum);
      String name =
          combo.app()
              + "-jdk"
              + combo.jdk()
              + "-"
              + db.db.id
              + "-"
              + profile.name().toLowerCase(Locale.ROOT)
              + (start == 1 ? "" : "-" + start);
      AppLauncher launcher =
          AppLauncher.of(SampleApp.named(combo.app()), Jdk.of(combo.jdk()))
              .withEnv("SAMPLE_EXT_URL", extStub.baseUri().toString())
              .withEnv("SAMPLE_DB", db.db.id)
              .workDir(workRoot.resolve(name))
              .readyTimeout(Duration.ofMinutes(4))
              .withAgent(
                  Path.of(required("argos.test.releaseAgentJar")),
                  new LinkedHashMap<>(profile.agentSettings()));
      if (db.url != null) {
        launcher
            .withEnv("SAMPLE_DB_URL", db.url)
            .withEnv("SAMPLE_DB_USER", db.user)
            .withEnv("SAMPLE_DB_PASSWORD", db.password);
      }
      profile.appEnv().forEach(launcher::withEnv);
      FakeCollector collector = FakeCollector.builder().tls(true).start();
      launcher.collector(collector);
      try {
        long begin = System.nanoTime();
        RunningApp running = launcher.start(Duration.ofMinutes(4));
        System.out.println(
            "[wo13-run] "
                + name
                + ": port "
                + running.port()
                + ", ready in "
                + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin)
                + " ms");
        return new Run(name, combo, db.db, db, profile, running, collector);
      } catch (Exception | Error e) {
        collector.close();
        throw e;
      }
    }

    @Override
    public synchronized void close() {
      for (Run run : runs.values()) {
        run.close();
      }
      runs.clear();
      stopServer();
      if (extStub != null) {
        extStub.close();
      }
      deleteTree(workRoot);
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

  /** One started app on one database with its agent configuration. */
  static final class Run implements AutoCloseable {
    final String name;
    final Combo combo;
    final JdbcDatabase db;
    final JdbcDatabase.Server server;
    final Profile profile;
    final RunningApp app;
    final FakeCollector collector;

    Run(
        String name,
        Combo combo,
        JdbcDatabase db,
        JdbcDatabase.Server server,
        Profile profile,
        RunningApp app,
        FakeCollector collector) {
      this.name = name;
      this.combo = combo;
      this.db = db;
      this.server = server;
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

    /** Waits until the transaction count has been stable for 1.5 s (at most {@link #WAIT}). */
    int settle() throws InterruptedException {
      int last = collected().transactions().size();
      long stableSince = System.nanoTime();
      long deadline = System.nanoTime() + WAIT.toNanos();
      while (System.nanoTime() < deadline) {
        Thread.sleep(250);
        int now = collected().transactions().size();
        if (now != last) {
          last = now;
          stableSince = System.nanoTime();
        } else if (System.nanoTime() - stableSince >= TimeUnit.MILLISECONDS.toNanos(1500)) {
          break;
        }
      }
      return last;
    }

    HttpResult call(String method, String path) throws IOException, InterruptedException {
      return app.call(method, path, Map.of());
    }

    /**
     * Sends {@code method path}, checks the status, and returns the one new transaction of that
     * path with every step and dictionary text it names (the profile and texts arrive apart).
     */
    TransactionRecord request(String method, String path, int status) throws Exception {
      String bare = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
      Predicate<TransactionRecord> filter = tx -> bare.equals(bareOf(tx.path()));
      int before = transactions(filter).size();
      HttpResult result = call(method, path);
      assertThat(result.status())
          .as("%s %s on %s: %s", method, path, name, result)
          .isEqualTo(status);
      collected().awaitTransactions(filter, before + 1, WAIT);
      Thread.sleep(500);
      List<TransactionRecord> matching =
          collected().awaitTransactions(filter, before + 1, Duration.ofSeconds(2));
      assertThat(matching.size() - before)
          .as("new transactions of %s on %s (D-04: one per request)", path, name)
          .isEqualTo(1);
      return matching.get(matching.size() - 1);
    }

    /** A self-diagnostics snapshot written after now. */
    SelfDiagnostics freshSelfDiagnostics() {
      SelfDiagnostics diagnostics = app.awaitSelfDiagnostics(Instant.now(), Duration.ofSeconds(10));
      assertThat(diagnostics.available()).as(diagnostics.reason()).isTrue();
      return diagnostics;
    }

    /** Every byte the collector received: batch bodies, dictionary texts, session messages. */
    String receivedText() {
      StringBuilder out = new StringBuilder();
      for (ReceivedBatch batch : collector.batches()) {
        if (batch.body() != null) {
          out.append(batch.body().toByteString().toString(StandardCharsets.UTF_8)).append('\n');
        }
      }
      collector.dictionaryEntries().forEach(entry -> out.append(entry.text()).append('\n'));
      collector.sessionMessages().forEach(message -> out.append(message).append('\n'));
      return out.toString();
    }

    String describe() {
      return name + "\n" + app.describe();
    }

    /**
     * Stops the app and the collector; keeps the agent log and console as {@code wo13-<name>-*}.
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
            dir.resolve("wo13-" + name + "-agent.log"), app.agentLog(), StandardCharsets.UTF_8);
        Files.write(
            dir.resolve("wo13-" + name + "-stdout.log"), app.stdoutLines(), StandardCharsets.UTF_8);
      } catch (IOException e) {
        System.out.println("[wo13-run] cannot keep the evidence of " + name + ": " + e);
      }
    }
  }

  /** {@code path} without its query string (null stays null). */
  static String bareOf(String path) {
    if (path == null) {
      return null;
    }
    int query = path.indexOf('?');
    return query < 0 ? path : path.substring(0, query);
  }

  /** The steps of {@code type} ({@code SQL}, {@code DBC}, {@code COMMIT}, {@code ROLLBACK}). */
  static List<TransactionRecord.StepRecord> steps(TransactionRecord tx, String type) {
    return tx.steps(type);
  }

  /** A long field of a step (the collector view writes numbers as {@code Long}). */
  static long number(TransactionRecord.StepRecord step, String field) {
    Object value = step.field(field);
    assertThat(value).as("%s of %s", field, step).isInstanceOf(Number.class);
    return ((Number) value).longValue();
  }

  /** A long field of a transaction. */
  static long number(TransactionRecord tx, String field) {
    Object value = tx.field(field);
    assertThat(value).as("%s of %s", field, tx).isInstanceOf(Number.class);
    return ((Number) value).longValue();
  }

  /** Instantiated by JUnit ({@code @ExtendWith(JdbcRuns.class)}). */
  JdbcRuns() {}
}
