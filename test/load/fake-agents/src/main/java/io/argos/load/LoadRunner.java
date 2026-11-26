/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.load;

import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.server.testing.fakeagent.AgentTokens;
import io.argos.server.testing.load.ClickHouseProbe;
import io.argos.server.testing.load.DockerContainers;
import io.argos.server.testing.load.Jcmd;
import io.argos.server.testing.load.JfrPinning;
import io.argos.server.testing.load.LoadServerJvm;
import io.argos.server.testing.load.ResidentSetProbe;
import io.argos.storage.clickhouse.Account;
import io.argos.storage.clickhouse.ClickHouseHttp;
import io.argos.storage.clickhouse.MemoryBudget;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * One server load run (18 §8, WO-29): starts PostgreSQL and ClickHouse (Testcontainers, the
 * profile's memory limits) and the server boot jar as a child JVM ({@link LoadServerJvm}), issues
 * one agent token per load app, connects the fake agents, starts the samplers and the virtual
 * dashboard users, runs warm-up and measured window, the plugged phases ({@link LoadPhase}), stops
 * the load and waits for the rows (D-13), reads the end-of-run facts (class histogram, native
 * memory, JFR, OOM evidence), judges ({@link Verdicts}) and writes the report even when the run
 * aborted. Returns the process exit code: 0 when every invariant passed, 1 otherwise.
 */
final class LoadRunner {

  private static final String LOG = "[server-load] ";

  private final LoadOptions o;
  private final RunReport r = new RunReport();
  private final LoadCounters counters;
  private final LatencyRecorder recorder = new LatencyRecorder();
  private final List<LoadPhase> phases;
  private final List<String> oomFindings = new ArrayList<>();
  private final List<Criterion> phaseFailures = new ArrayList<>();
  private boolean oomChecked;
  private List<String> settingsViolations;

  LoadRunner(LoadOptions options) {
    this.o = options;
    this.counters = new LoadCounters(options.apps());
    this.phases = phases();
    r.options = options;
  }

  /**
   * The phases of step A2, in order: the attacks under load (with the dictionary flood's cost), the
   * idle sessions after the load (C-157 (1)), and the unauthenticated Hello flood last (it blocks
   * the loopback address every fake agent shares).
   */
  static List<LoadPhase> phases() {
    return List.of(new AttackPhase(), new SessionPhase(), new UnauthFloodPhase());
  }

  /**
   * Server properties of the run: the per-token session limits (100 concurrent, 60 new per minute)
   * are raised so that the instance flood stops at the per-app hourly cap (SEC-14) and the
   * idle-session phase can open its sessions; the server-wide 5,000 cap stays.
   */
  static Map<String, String> serverProperties() {
    return Map.of(
        "argos.collector.session.new-per-minute", "100000",
        "argos.collector.session.max-per-token", "10000");
  }

  int run() throws IOException {
    Path out = o.out();
    Files.createDirectories(out);
    Path work = out.resolve("work");
    deleteRecursively(work);
    Files.createDirectories(work);
    r.started = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    r.commit = commit();
    r.machine = machine();
    r.java = System.getProperty("java.vendor") + " " + System.getProperty("java.version");
    boolean aborted = false;
    try {
      runStack(work);
    } catch (Exception e) {
      aborted = true;
      r.notes.add("The run aborted: " + e.toString().replace('\n', ' '));
      log("ABORTED: " + e);
    }
    r.finished = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    long heapPerSession =
        phases.stream()
            .filter(SessionPhase.class::isInstance)
            .map(SessionPhase.class::cast)
            .mapToLong(SessionPhase::heapBytesPerSession)
            .findFirst()
            .orElse(-1);
    r.sections.addAll(Proposals.sections(r, heapPerSession));
    r.criteria = new ArrayList<>(Verdicts.evaluate(measurements()));
    for (LoadPhase p : phases) {
      r.criteria.addAll(p.criteria());
    }
    r.criteria.addAll(phaseFailures);
    r.generatorFailures = counters.failures();
    r.generatorFailureCount = counters.failureCount();
    files(out);
    String report = ReportWriter.write(r);
    Path file = out.resolve("server-load.md");
    Files.writeString(file, report, StandardCharsets.UTF_8);
    log("report " + file.toAbsolutePath());
    if (o.publish() != null) {
      Files.createDirectories(o.publish().toAbsolutePath().getParent());
      Files.copy(file, o.publish(), StandardCopyOption.REPLACE_EXISTING);
      log("published " + o.publish().toAbsolutePath());
    }
    for (Criterion c : r.criteria) {
      log(String.format(Locale.ROOT, "%-8s %s: %s", c.verdict(), c.title(), c.measured()));
    }
    boolean fails = aborted || Verdicts.taskFails(r.criteria);
    if (fails) {
      log("FAILED: " + (aborted ? "the run aborted" : "an invariant failed") + ", see " + file);
    }
    return fails ? 1 : 0;
  }

  private void runStack(Path work) throws Exception {
    Path bootJar = requireBootJar();
    log(
        "starting PostgreSQL ("
            + Format.bytes(o.postgresMemoryBytes())
            + ") and ClickHouse ("
            + Format.bytes(o.clickHouseMemoryBytes())
            + ")");
    try (ServerTestEnvironment env =
        ServerTestEnvironment.builder()
            .clickHouseMemoryLimit(o.clickHouseMemoryBytes())
            .postgresMemoryLimit(o.postgresMemoryBytes())
            .startupTimeout(Duration.ofMinutes(5))
            .start()) {
      String chId =
          DockerContainers.publishing(URI.create(env.clickHouseUrl()).getPort()).orElse("");
      String pgId = env.postgresContainer().getContainerId();
      r.stack =
          "PostgreSQL "
              + env.postgresContainer().getDockerImageName()
              + " (container limit "
              + Format.bytes(o.postgresMemoryBytes())
              + "), ClickHouse "
              + env.clickHouseServer().image().version()
              + " (container limit "
              + Format.bytes(o.clickHouseMemoryBytes())
              + ", max_server_memory_usage "
              + Format.bytes(
                  MemoryBudget.forContainerLimit(o.clickHouseMemoryBytes()).serverBytes())
              + ")";
      log("starting the server (" + bootJar.getFileName() + ", -Xmx" + o.serverHeap() + ")");
      try (LoadServerJvm server =
          LoadServerJvm.start(
              env,
              work.resolve("server"),
              new LoadServerJvm.Options(
                  bootJar,
                  o.serverHeap(),
                  o.maxDirectMemory(),
                  true,
                  serverProperties(),
                  Duration.ofMinutes(5)))) {
        r.serverJvmOptions = server.jvmOptions();
        try {
          runLoad(env, server, chId, pgId, work);
        } finally {
          oomChecks(server, chId, pgId);
        }
      }
    }
  }

  private void runLoad(
      ServerTestEnvironment env, LoadServerJvm server, String chId, String pgId, Path work)
      throws Exception {
    List<LoadApp> apps = issueApps(env);
    r.apps = apps;
    ClickHouseProbe ch = new ClickHouseProbe(env);
    try {
      r.clickHouseSettings = ch.settings();
      settingsViolations =
          ClickHouseSettingsRule.violations(o.clickHouseMemoryBytes(), r.clickHouseSettings);
    } catch (RuntimeException e) {
      r.notes.add("ClickHouse settings could not be read: " + e);
    }
    try (ResidentSetProbe rss = ResidentSetProbe.start(server.pid(), Duration.ofSeconds(1));
        ResourceSampler sampler =
            new ResourceSampler(
                server.scraper(),
                ch,
                rss,
                chId,
                pgId,
                o.sampleInterval(),
                o.out().resolve("samples.tsv"));
        Generator generator =
            new Generator(o, apps, env.collectorPort(), env.collectorCaCertificate(), counters)) {
      r.rssSource = rss.source().description();
      r.skewsMs = generator.skewsMs();
      r.channels = generator.channelCount();
      log("connecting " + o.instances() + " fake agents");
      r.instancesConnected = generator.connectAll();
      if (r.instancesConnected != o.instances()) {
        throw new IllegalStateException(
            "Only "
                + r.instancesConnected
                + " of "
                + o.instances()
                + " sessions were accepted: "
                + counters.failures());
      }
      log("signing in " + o.users() + " dashboard users");
      try (VirtualUsers users =
          new VirtualUsers(env, server.baseUrl(), apps, o.users(), o.userInterval(), recorder)) {
        sampler.start();
        generator.start();
        users.start();
        log("warm-up " + Format.duration(o.warmUp()));
        sleep(o.warmUp(), server);
        r.atWindowStart = counters.snapshot();
        sampler.markWindowStart();
        recorder.measuring(true);
        r.windowStart = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        log("measured window " + Format.duration(o.duration()));
        sleep(o.duration(), server);
        r.atWindowEnd = counters.snapshot();
        recorder.measuring(false);
        sampler.markWindowEnd();
        r.windowEnd = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        LoadContext context =
            new LoadContext(o, env, server, rss, ch, generator, counters, apps, work);
        for (LoadPhase p : phases) {
          log("phase " + p.name() + " under load");
          runPhase(p, () -> p.underLoad(context));
        }
      }
      r.userCalls = recorder.count();
      r.userErrors = recorder.errors();
      r.userP95Millis = recorder.quantile(0.95);
      r.userEndpoints = recorder.summary();
      log("stopping the load");
      generator.stop();
      r.loadEnd = Instant.now().truncatedTo(ChronoUnit.SECONDS);
      long drainStart = System.nanoTime();
      r.stored = awaitStored(env, apps);
      r.drainSeconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - drainStart);
      sampler.sampleNow();
      LoadContext context =
          new LoadContext(o, env, server, rss, ch, generator, counters, apps, work);
      for (LoadPhase p : phases) {
        log("phase " + p.name() + " after the load");
        runPhase(p, () -> p.afterLoad(context));
        r.sections.addAll(p.sections());
      }
      r.atEnd = counters.snapshot();
      endOfRunFacts(server, work);
      r.rssPeak = rss.peakBytes().orElse(-1);
      r.rssSampledMax = rss.sampledMaxBytes().orElse(-1);
      r.sampler = sampler.summary();
      try {
        r.clickHouseMemoryLimitErrors = ch.memoryLimitErrorsInLog();
      } catch (RuntimeException e) {
        r.notes.add("The ClickHouse error log could not be read: " + e);
      }
    }
  }

  /** A step of a phase. */
  private interface PhaseStep {
    void run() throws Exception;
  }

  /**
   * Runs one step of a phase; a failure is a FAIL row (and a note) instead of aborting the run, so
   * that the end check, the other phases and the end-of-run facts still happen.
   */
  private void runPhase(LoadPhase p, PhaseStep step) throws InterruptedException {
    try {
      step.run();
    } catch (InterruptedException e) {
      throw e;
    } catch (Exception e) {
      log("phase " + p.name() + " failed: " + e);
      r.notes.add("Phase " + p.name() + " failed: " + e.toString().replace('\n', ' '));
      phaseFailures.add(
          PhaseSupport.invariant(
              "T-NFR-06",
              "WO-29",
              "phase " + p.name() + " completed",
              "no error",
              e.getClass().getSimpleName(),
              true,
              false,
              "see the notes"));
    }
  }

  /** One app per index, env alternating prod/stg, a fresh token each. */
  private List<LoadApp> issueApps(ServerTestEnvironment env) throws SQLException {
    List<LoadApp> apps = new ArrayList<>();
    try (Connection c = env.connect(PgAccount.APP)) {
      for (int i = 0; i < o.apps(); i++) {
        String name = LoadApp.nameOf(i);
        String appEnv = LoadOptions.envOf(i);
        int appId = AgentTokens.ensureApp(c, name, appEnv);
        apps.add(new LoadApp(i, name, appEnv, AgentTokens.issue(c, appId)));
      }
    }
    return apps;
  }

  /**
   * Waits until the load apps' {@code count(xlog)} and {@code sum(service_stat_1m.count)} equal the
   * acknowledged transactions (D-13), at most the drain timeout, then reads every count.
   */
  private RunReport.Stored awaitStored(ServerTestEnvironment env, List<LoadApp> apps)
      throws InterruptedException {
    ClickHouseHttp migrator = env.clickHouse(Account.MIGRATOR);
    String ids =
        apps.stream().map(a -> Integer.toString(a.appId())).collect(Collectors.joining(","));
    String in = " WHERE app_id IN (" + ids + ")";
    long sent = counters.get(LoadCounters.Counter.TX_ACCEPTED);
    long deadline = System.nanoTime() + o.drainTimeout().toNanos();
    long xlog = -1;
    long stat = -1;
    while (System.nanoTime() < deadline) {
      xlog = count(migrator, "SELECT count() FROM argos.xlog" + in);
      stat = count(migrator, "SELECT sum(count) FROM argos.service_stat_1m" + in);
      if (xlog == sent && stat == sent) {
        break;
      }
      TimeUnit.SECONDS.sleep(2);
    }
    log("drained: sent " + sent + ", xlog " + xlog + ", service_stat_1m " + stat);
    Map<Integer, Long> byApp = new LinkedHashMap<>();
    try {
      for (List<String> row :
          migrator.rows("SELECT app_id, count() FROM argos.xlog" + in + " GROUP BY app_id")) {
        byApp.put(Integer.parseInt(row.get(0)), Long.parseLong(row.get(1)));
      }
    } catch (RuntimeException e) {
      r.notes.add("xlog by app could not be read: " + e);
    }
    return new RunReport.Stored(
        xlog,
        count(migrator, "SELECT uniqExact(tx_id) FROM argos.xlog" + in),
        stat,
        count(migrator, "SELECT count() FROM argos.profile_step" + in),
        count(migrator, "SELECT count() FROM argos.sql_stat_raw" + in),
        count(migrator, "SELECT count() FROM argos.ext_stat_raw" + in),
        count(migrator, "SELECT count() FROM argos.metric" + in),
        byApp);
  }

  private long count(ClickHouseHttp client, String sql) {
    try {
      String v = client.scalar(sql).strip();
      return v.isEmpty() || v.equals("\\N") ? 0 : Long.parseLong(v);
    } catch (RuntimeException e) {
      r.notes.add("Count failed (" + sql + "): " + e);
      return -1;
    }
  }

  /** Class histogram (full GC), native memory summary and the JFR dump, saved as files too. */
  private void endOfRunFacts(LoadServerJvm server, Path work) {
    if (!server.isAlive()) {
      return;
    }
    try {
      String histogram = Jcmd.run(server.pid(), 300, "GC.class_histogram");
      Files.writeString(o.out().resolve("class-histogram.txt"), histogram, StandardCharsets.UTF_8);
      r.classHistogram = Jcmd.parseClassHistogram(histogram);
    } catch (RuntimeException | IOException e) {
      r.notes.add("jcmd GC.class_histogram failed: " + e);
    }
    try {
      String nmt = Jcmd.run(server.pid(), 120, "VM.native_memory", "summary", "scale=KB");
      Files.writeString(o.out().resolve("native-memory.txt"), nmt, StandardCharsets.UTF_8);
      r.nativeMemory = Jcmd.parseNativeMemory(nmt);
    } catch (RuntimeException | IOException e) {
      r.notes.add("jcmd VM.native_memory failed: " + e);
    }
    Path jfr = work.resolve("server-pinned.jfr");
    if (server.dumpJfr(jfr)) {
      try {
        r.pinning = JfrPinning.summarize(jfr);
      } catch (IOException | RuntimeException e) {
        r.notes.add("The JFR recording could not be read: " + e);
      }
    } else {
      r.notes.add("No JFR recording could be dumped.");
    }
  }

  private void oomChecks(LoadServerJvm server, String chId, String pgId) {
    try {
      if (!server.isAlive()) {
        oomFindings.add("server exited with " + server.exitCode().orElse(-1));
      }
      for (Path f : server.crashFiles()) {
        oomFindings.add("server wrote " + f.getFileName());
      }
      long oomLines = server.consoleLines("OutOfMemoryError");
      if (oomLines > 0) {
        oomFindings.add(oomLines + " OutOfMemoryError line(s) in the server log");
      }
      if (r.clickHouseMemoryLimitErrors > 0) {
        oomFindings.add(
            r.clickHouseMemoryLimitErrors + " MEMORY_LIMIT_EXCEEDED line(s) in the ClickHouse log");
      }
      if (!chId.isEmpty() && DockerContainers.oomKilled(chId)) {
        oomFindings.add("ClickHouse container OOMKilled");
      }
      if (DockerContainers.oomKilled(pgId)) {
        oomFindings.add("PostgreSQL container OOMKilled");
      }
      oomChecked = true;
    } catch (RuntimeException e) {
      r.notes.add("OOM checks failed: " + e);
    }
  }

  private Verdicts.Measurements measurements() {
    ResourceSampler.Summary s = r.sampler;
    LoadCounters.Snapshot a = r.atWindowStart;
    LoadCounters.Snapshot b = r.atWindowEnd;
    boolean window = a != null && b != null;
    RunReport.Stored st = r.stored;
    long heapBytes = LoadOptions.size(o.serverHeap(), -1);
    return new Verdicts.Measurements(
        o.fullScale(),
        o.profile().id(),
        r.windowStart == null || r.windowEnd == null
            ? null
            : Duration.between(r.windowStart, r.windowEnd),
        window ? b.ratePerSecond(LoadCounters.Counter.TX_ACCEPTED, a) : Double.NaN,
        window ? b.ratePerSecond(LoadCounters.Counter.STEPS, a) : Double.NaN,
        window
            ? b.ratePerSecond(LoadCounters.Counter.STAT_SQL, a)
                + b.ratePerSecond(LoadCounters.Counter.STAT_EXT, a)
            : Double.NaN,
        s == null ? Double.NaN : s.lagWindow().quantile(0.99),
        r.userCalls,
        r.userErrors,
        r.userP95Millis,
        o.users(),
        s == null ? -1 : s.peaks().chActiveParts,
        s == null ? -1 : s.delayedInsertsDelta(),
        settingsViolations,
        oomChecked ? oomFindings : null,
        r.atEnd == null ? -1 : r.atEnd.get(LoadCounters.Counter.TX_ACCEPTED),
        st == null ? -1 : st.xlog(),
        st == null ? -1 : st.xlogDistinct(),
        st == null ? -1 : st.serviceStat1m(),
        r.rssPeak,
        heapBytes < 0 ? -1 : heapBytes + MemoryBudget.GIB,
        s == null ? -1 : s.peaks().chMemoryTracking,
        r.clickHouseSettings == null ? -1 : r.clickHouseSettings.maxServerMemoryUsage());
  }

  private Path requireBootJar() {
    Path jar = o.bootJar();
    if (jar == null || !Files.isRegularFile(jar)) {
      throw new IllegalStateException(
          "The server boot jar " + jar + " is missing: run ./gradlew :server:bootJar");
    }
    return jar;
  }

  /** Sleeps {@code d}, logging progress every minute; fails when the server died meanwhile. */
  private void sleep(Duration d, LoadServerJvm server) throws InterruptedException {
    long end = System.nanoTime() + d.toNanos();
    long nextLog = System.nanoTime() + TimeUnit.MINUTES.toNanos(1);
    while (System.nanoTime() < end) {
      if (!server.isAlive()) {
        throw new IllegalStateException(
            "The server exited with " + server.exitCode().orElse(-1) + ":\n" + server.tail(20));
      }
      TimeUnit.MILLISECONDS.sleep(Math.min(1_000, (end - System.nanoTime()) / 1_000_000 + 1));
      if (System.nanoTime() >= nextLog) {
        nextLog += TimeUnit.MINUTES.toNanos(1);
        log(
            String.format(
                Locale.ROOT,
                "%,d transactions acknowledged, %d failures so far",
                counters.get(LoadCounters.Counter.TX_ACCEPTED),
                counters.failureCount()));
      }
    }
  }

  private void files(Path out) {
    for (String name :
        List.of(
            "samples.tsv",
            "class-histogram.txt",
            "native-memory.txt",
            "work/server/server-stdout.log",
            "work/server/gc.log",
            "work/server-pinned.jfr")) {
      if (Files.exists(out.resolve(name))) {
        r.files.add("build/reports/server-load/" + name);
      }
    }
  }

  private String commit() {
    Path root = Path.of(System.getProperty("argos.repositoryRoot", "."));
    String head = git(root, "rev-parse", "--short", "HEAD");
    if (head.isEmpty()) {
      return "unknown";
    }
    String status = git(root, "status", "--porcelain");
    return "`" + head + "`" + (status.isEmpty() ? "" : " plus uncommitted changes");
  }

  private static String git(Path root, String... args) {
    List<String> command = new ArrayList<>();
    command.add("git");
    command.add("-C");
    command.add(root.toAbsolutePath().toString());
    command.addAll(List.of(args));
    try {
      Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
      String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
      return p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0 ? out : "";
    } catch (IOException e) {
      return "";
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return "";
    }
  }

  private static String machine() {
    long memory = -1;
    if (ManagementFactory.getOperatingSystemMXBean()
        instanceof com.sun.management.OperatingSystemMXBean os) {
      memory = os.getTotalMemorySize();
    }
    return System.getProperty("os.name")
        + " "
        + System.getProperty("os.version")
        + " ("
        + System.getProperty("os.arch")
        + "), "
        + Runtime.getRuntime().availableProcessors()
        + " logical CPUs, "
        + Format.bytes(memory)
        + " RAM (as the generator JVM sees them; Docker may see less)";
  }

  private static void deleteRecursively(Path dir) throws IOException {
    if (!Files.exists(dir)) {
      return;
    }
    try (Stream<Path> paths = Files.walk(dir)) {
      for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(p);
      }
    }
  }

  static void log(String message) {
    System.out.println(LOG + Instant.now().truncatedTo(ChronoUnit.SECONDS) + " " + message);
  }
}
