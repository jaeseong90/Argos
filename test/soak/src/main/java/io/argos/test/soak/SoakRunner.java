/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.soak;

import io.argos.agent.testing.launcher.AppLauncher;
import io.argos.agent.testing.launcher.HttpResult;
import io.argos.agent.testing.launcher.Jdk;
import io.argos.agent.testing.launcher.RunningApp;
import io.argos.agent.testing.launcher.SampleApp;
import io.argos.agent.testing.launcher.SelfDiagnostics;
import io.argos.load.ClickHouseSettingsRule;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.server.testing.fakeagent.AgentTokens;
import io.argos.server.testing.load.ClickHouseProbe;
import io.argos.server.testing.load.DockerContainers;
import io.argos.server.testing.load.LoadServerJvm;
import io.argos.server.testing.load.ResidentSetProbe;
import io.argos.storage.clickhouse.Account;
import io.argos.storage.clickhouse.ClickHouseHttp;
import io.argos.storage.clickhouse.MemoryBudget;
import io.argos.test.soak.SoakVerdicts.ConsistencyRun;
import io.argos.test.soak.SoakVerdicts.Criterion;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * One soak run (18 §11.2, WO-52): starts PostgreSQL and ClickHouse (Testcontainers, {@link
 * ServerTestEnvironment}), the server boot jar as a child JVM ({@link LoadServerJvm}), ext-stub,
 * shop-boot3 and shop-batch with the agent (gRPC over TLS to the collector, a token per app issued
 * like {@code argos-admin token create}), applies the recommended alert templates, runs the load
 * ({@link LoadSegments}), the hourly agent and 10-minute stack samples ({@link StackProbe}) and the
 * batch runs, checks the consistency of every UTC day, reads the alert events, collects the OOM
 * evidence, judges ({@link SoakVerdicts}) and writes the report even when the run aborted. Returns
 * the exit code: 0 without a FAIL, 1 otherwise.
 */
final class SoakRunner {

  private static final String LOG = "[soak] ";

  /** Application names and environment of the run (one token each). */
  static final String SHOP_APP = "soak-shop";

  static final String BATCH_APP = "soak-batch";
  static final String APP_ENV = "soak";

  /** The Spring Batch job of shop-batch (sample-apps/shop-batch README). */
  static final String BATCH_JOB = "settlementJob";

  private static final Pattern AGENT_ERROR = Pattern.compile("\\bERROR\\b");
  private static final Pattern RETRANSFORM = Pattern.compile("(?i)\\b(WARN|ERROR)\\b.*retransform");

  private final SoakOptions o;
  private final SoakSchedule schedule;
  private final RunFacts f = new RunFacts();
  private SoakSamples samples;
  private final Set<LocalDate> checkedDays = new LinkedHashSet<>();
  private final List<ConsistencyRun> consistency = new ArrayList<>();
  private final OomEvidence oom = new OomEvidence();
  private boolean oomChecked;

  SoakRunner(SoakOptions options) {
    this.o = options;
    this.schedule = SoakSchedule.of(options.duration());
    f.options = options;
    f.schedule = schedule;
  }

  int run() throws IOException {
    Path out = o.out();
    Files.createDirectories(out);
    Path work = out.resolve("work");
    deleteRecursively(work);
    Files.createDirectories(work);
    samples = new SoakSamples(out.resolve("samples.tsv"));
    f.started = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    f.commit = commit();
    f.machine = machine();
    f.java = System.getProperty("java.vendor") + " " + System.getProperty("java.version");
    log(
        "soak run "
            + SoakOptions.format(o.duration())
            + " at "
            + o.tps()
            + " TPS on machine profile "
            + o.machineProfile()
            + "; "
            + schedule.describe());
    o.invalidReason().ifPresent(r -> log("time and resource verdicts will be INVALID: " + r));
    try {
      requireFiles();
      runStack(work);
    } catch (Exception e) {
      f.aborted = true;
      f.note("The run aborted: " + e);
      log("ABORTED: " + e);
    }
    f.finished = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    f.consistency = List.copyOf(consistency);
    f.oomFindings = oomChecked ? oom.findings() : null;
    f.oomUnavailable = oom.unavailable();
    List<Criterion> criteria =
        SoakVerdicts.evaluate(
            new SoakVerdicts.Inputs(
                samples.agent(),
                samples.stack(),
                samples.segments(),
                f.consistency.isEmpty() ? null : f.consistency,
                f.firing,
                f.oomFindings,
                f.settingsViolations,
                SoakOptions.size(o.serverHeap()),
                SoakVerdicts.DEFAULT_WAL_SEGMENT_BYTES,
                o.invalidReason().orElse(null)));
    boolean fails = f.aborted || SoakVerdicts.fails(criteria);
    int exit = fails ? 1 : 0;
    files(out);
    Path report = out.resolve("report.md");
    Files.writeString(report, SoakReport.markdown(f, samples, criteria), StandardCharsets.UTF_8);
    Files.writeString(
        out.resolve("results.json"), SoakReport.json(f, criteria, exit), StandardCharsets.UTF_8);
    log("report " + report.toAbsolutePath());
    if (o.publishDir() != null) {
      Files.createDirectories(o.publishDir());
      Path target = o.publishDir().resolve(SoakReport.publishedName(f.started));
      Files.copy(report, target, StandardCopyOption.REPLACE_EXISTING);
      log("published " + target.toAbsolutePath());
    }
    for (Criterion c : criteria) {
      log(
          String.format(
              Locale.ROOT, "%-7s %s %s: %s", c.verdict(), c.id(), c.title(), c.measured()));
    }
    log(SoakReport.counts(criteria));
    if (fails) {
      log("FAILED: " + (f.aborted ? "the run aborted" : "a criterion failed") + ", see " + report);
    }
    return exit;
  }

  private void runStack(Path work) throws Exception {
    log(
        "starting PostgreSQL ("
            + mib(o.postgresMemoryBytes())
            + ") and ClickHouse ("
            + mib(o.clickHouseMemoryBytes())
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
      f.stack =
          "PostgreSQL "
              + env.postgresContainer().getDockerImageName()
              + " (limit "
              + mib(o.postgresMemoryBytes())
              + "), ClickHouse "
              + env.clickHouseServer().image().version()
              + " (limit "
              + mib(o.clickHouseMemoryBytes())
              + ", max_server_memory_usage "
              + mib(MemoryBudget.forContainerLimit(o.clickHouseMemoryBytes()).serverBytes())
              + "), server -Xmx"
              + o.serverHeap()
              + ", shop-boot3 and shop-batch -Xmx"
              + o.appHeap()
              + " on JDK 21";
      ClickHouseProbe probe = new ClickHouseProbe(env);
      try {
        f.settingsViolations =
            ClickHouseSettingsRule.violations(o.clickHouseMemoryBytes(), probe.settings());
      } catch (RuntimeException e) {
        f.note("ClickHouse settings could not be read: " + e);
      }
      String dmesgBefore = dmesg();
      log("starting the server (" + o.bootJar().getFileName() + ", -Xmx" + o.serverHeap() + ")");
      LoadServerJvm server =
          LoadServerJvm.start(
              env,
              work.resolve("server"),
              new LoadServerJvm.Options(
                  o.bootJar(), o.serverHeap(), "512m", false, Map.of(), Duration.ofMinutes(5)));
      Process extStub = null;
      RunningApp shop = null;
      RunningApp batch = null;
      // The agent tokens live only while the stack runs: the report directory is uploaded as a CI
      // artifact, so the files are deleted when the applications have stopped.
      Path secrets = work.resolve("secrets");
      Path shopToken = secrets.resolve("shop.token");
      Path batchToken = secrets.resolve("batch.token");
      try {
        f.serverJvm = String.join(" ", server.jvmOptions());
        int[] appIds = new int[2];
        Files.createDirectories(secrets);
        try (Connection c = env.connect(PgAccount.APP)) {
          appIds[0] = AgentTokens.ensureApp(c, SHOP_APP, APP_ENV);
          appIds[1] = AgentTokens.ensureApp(c, BATCH_APP, APP_ENV);
          writeOwnerOnly(shopToken, AgentTokens.issue(c, appIds[0]).token());
          writeOwnerOnly(batchToken, AgentTokens.issue(c, appIds[1]).token());
        }
        int extPort = freePort();
        extStub = startExtStub(work.resolve("ext-stub"), extPort);
        String extUrl = "http://127.0.0.1:" + extPort;
        Jdk jdk =
            new Jdk(
                Jdk.J21,
                Paths.get(
                    System.getProperty("java.home"),
                    "bin",
                    FileSystems.getDefault().getSeparator().equals("\\") ? "java.exe" : "java"));
        log("starting shop-boot3 with the agent" + (o.virtualThreads() ? " (SAMPLE_VT=true)" : ""));
        shop =
            launcher(o.shopJar(), jdk, SHOP_APP, shopToken, env, work.resolve("shop-boot3"), extUrl)
                .virtualThreads(o.virtualThreads())
                .start(Duration.ofMinutes(5));
        log("starting shop-batch with the agent");
        batch =
            launcher(
                    o.batchJar(),
                    jdk,
                    BATCH_APP,
                    batchToken,
                    env,
                    work.resolve("shop-batch"),
                    extUrl)
                .start(Duration.ofMinutes(5));
        f.fdSource = FdProbe.of(shop.pid()).source().description();
        awaitIngest(env, shop, appIds[0]);
        AlertWatch alerts = AlertWatch.signIn(env, server.baseUrl());
        String applied = alerts.applyTemplates(appIds[0]);
        f.endState.put("Alert templates of " + SHOP_APP + " at the start", applied);
        log("templates of " + SHOP_APP + ": " + applied);
        measure(env, server, shop, batch, alerts, appIds, work);
      } finally {
        try {
          oomChecks(env, server, shop, batch, chId, pgId, work, dmesgBefore);
        } finally {
          closeQuietly(batch);
          closeQuietly(shop);
          if (extStub != null) {
            extStub.destroy();
            extStub.waitFor(15, TimeUnit.SECONDS);
          }
          server.close();
          deleteQuietly(shopToken);
          deleteQuietly(batchToken);
          deleteQuietly(secrets);
        }
      }
    }
  }

  private static void deleteQuietly(Path file) {
    try {
      Files.deleteIfExists(file);
    } catch (IOException e) {
      log("deleting " + file + " failed: " + e);
    }
  }

  private AppLauncher launcher(
      Path jar,
      Jdk jdk,
      String appName,
      Path token,
      ServerTestEnvironment env,
      Path dir,
      String extUrl) {
    return AppLauncher.of(SampleApp.fromArtifact(jar), jdk)
        .withAgent(o.agentJar())
        .agentConfig("app.name", appName)
        .agentConfig("app.env", APP_ENV)
        // The agent's gRPC exporter to the real collector instead of the harness file exporter.
        .agentConfig("test.exporter", "grpc")
        .agentConfig("server.address", "127.0.0.1:" + env.collectorPort())
        .agentConfig("server.tls", "true")
        .agentConfig("server.tls.ca-file", env.collectorCaCertificate().toAbsolutePath().toString())
        .agentConfig("server.token-file", token.toAbsolutePath().toString())
        .withJvmOptions(
            "-Xms" + o.appHeap(),
            "-Xmx" + o.appHeap(),
            "-XX:NativeMemoryTracking=summary",
            "-XX:+HeapDumpOnOutOfMemoryError",
            "-XX:HeapDumpPath=" + dir.toAbsolutePath(),
            "-XX:ErrorFile=" + dir.resolve("hs_err_pid%p.log").toAbsolutePath())
        .withEnv("SAMPLE_EXT_URL", extUrl)
        .workDir(dir)
        .readyTimeout(Duration.ofMinutes(5));
  }

  private void measure(
      ServerTestEnvironment env,
      LoadServerJvm server,
      RunningApp shop,
      RunningApp batch,
      AlertWatch alerts,
      int[] appIds,
      Path work)
      throws Exception {
    Instant loadStart = Instant.now();
    Instant windowStart = loadStart.plus(schedule.warmUp());
    Instant deadline = windowStart.plus(o.duration());
    samples.windowStart(windowStart);
    f.windowStart = windowStart.truncatedTo(ChronoUnit.SECONDS);
    LoadSegments load =
        new LoadSegments(
            o.loadJar(),
            shop.baseUrl(),
            o,
            schedule.hourly(),
            schedule.warmUp(),
            deadline,
            work.resolve("load"),
            samples,
            f);
    Thread loadThread = new Thread(load, "soak-load");
    loadThread.setDaemon(true);
    ConsistencyCheck check =
        new ConsistencyCheck(o.bootJar(), env.springProperties(), work.resolve("consistency"));
    ScheduledExecutorService ses =
        Executors.newScheduledThreadPool(
            3,
            r -> {
              Thread t = new Thread(r, "soak-sampler");
              t.setDaemon(true);
              return t;
            });
    try (ResidentSetProbe rss = ResidentSetProbe.start(server.pid(), Duration.ofSeconds(5))) {
      f.rssSource = rss.source().description();
      StackProbe probe = new StackProbe(env, server, rss, FdProbe.of(shop.pid()), samples, f);
      BatchDriver batches = new BatchDriver(batch, alerts, env, appIds[1]);
      loadThread.start();
      log("warm-up " + SoakOptions.format(schedule.warmUp()) + " at " + o.tps() + " TPS");
      long untilWindow = Math.max(0, Duration.between(Instant.now(), windowStart).toMillis());
      List<ScheduledFuture<?>> tasks = new ArrayList<>();
      tasks.add(
          ses.scheduleAtFixedRate(
              guarded("agent sample", () -> probe.agent(shop.jcmd(), appIds[0])),
              untilWindow,
              schedule.hourly().toMillis(),
              TimeUnit.MILLISECONDS));
      tasks.add(
          ses.scheduleAtFixedRate(
              guarded("stack sample", probe::stack),
              untilWindow,
              schedule.tenMinute().toMillis(),
              TimeUnit.MILLISECONDS));
      tasks.add(
          ses.scheduleAtFixedRate(
              guarded("batch run", batches::trigger),
              untilWindow + schedule.hourly().toMillis() / 2,
              schedule.batch().toMillis(),
              TimeUnit.MILLISECONDS));
      watch(server, shop, batch, deadline, check);
      f.windowEnd = Instant.now().truncatedTo(ChronoUnit.SECONDS);
      for (ScheduledFuture<?> t : tasks) {
        t.cancel(false);
      }
      ses.shutdown();
      if (!ses.awaitTermination(5, TimeUnit.MINUTES)) {
        f.note("A sampler did not finish within 5 minutes after the end.");
      }
      loadThread.join(Duration.ofMinutes(3).toMillis());
      if (loadThread.isAlive()) {
        load.stop();
        f.note("The load generator was still running 3 minutes after the end and was stopped.");
      }
      f.loadSent = load.sent();
      f.loadSegments = samples.segments().size();
      batches.finish(f);
      log("load stopped: " + f.loadSent + " requests sent; waiting for the rows");
      drain(env, appIds);
      for (LocalDate day = utcDate(f.started); !day.isAfter(utcDate(Instant.now())); ) {
        if (!checkedDays.contains(day)) {
          consistency(check, day);
        }
        day = day.plusDays(1);
      }
      try {
        f.firing = alerts.firing(f.started, Instant.now(), load.errorShare());
      } catch (RuntimeException e) {
        f.note("The alert events could not be read: " + e.getMessage());
      }
      f.endState.put("Instances of " + SHOP_APP, alerts.instances(appIds[0]));
      f.endState.put("Instances of " + BATCH_APP, alerts.instances(appIds[1]));
      endOfRun(env, shop, batch, appIds);
    } finally {
      ses.shutdownNow();
      load.stop();
    }
  }

  /**
   * Sleeps until {@code deadline}: fails when the server or an app exits, runs the nightly
   * consistency check of the previous UTC day after 00:30 UTC, logs progress.
   */
  private void watch(
      LoadServerJvm server,
      RunningApp shop,
      RunningApp batch,
      Instant deadline,
      ConsistencyCheck check)
      throws InterruptedException {
    Duration progress =
        schedule.hourly().compareTo(Duration.ofMinutes(10)) < 0
            ? schedule.hourly()
            : Duration.ofMinutes(10);
    Instant nextLog = Instant.now().plus(progress);
    LocalDate startDay = utcDate(f.started);
    while (Instant.now().isBefore(deadline)) {
      if (!server.isAlive()) {
        throw new IllegalStateException(
            "The server exited with " + server.exitCode().orElse(-1) + ":\n" + server.tail(20));
      }
      if (!shop.isAlive()) {
        throw new IllegalStateException("shop-boot3 exited:\n" + shop.describe());
      }
      if (!batch.isAlive()) {
        throw new IllegalStateException("shop-batch exited:\n" + batch.describe());
      }
      Instant now = Instant.now();
      LocalDate yesterday = utcDate(now).minusDays(1);
      if (!yesterday.isBefore(startDay)
          && !checkedDays.contains(yesterday)
          && now.atZone(ZoneOffset.UTC).toLocalTime().isAfter(java.time.LocalTime.of(0, 30))) {
        consistency(check, yesterday);
      }
      if (!now.isBefore(nextLog)) {
        nextLog = now.plus(progress);
        log(
            String.format(
                Locale.ROOT,
                "%s left; %d agent, %d stack samples, %d load segments",
                SoakOptions.format(Duration.between(now, deadline).truncatedTo(ChronoUnit.SECONDS)),
                samples.agent().size(),
                samples.stack().size(),
                samples.segments().size()));
      }
      TimeUnit.MILLISECONDS.sleep(
          Math.max(1, Math.min(1_000, Duration.between(Instant.now(), deadline).toMillis())));
    }
  }

  private void consistency(ConsistencyCheck check, LocalDate day) {
    log("consistency check " + day);
    ConsistencyRun r = check.run(day);
    checkedDays.add(day);
    consistency.add(r);
    log(
        "consistency "
            + day
            + ": exit "
            + r.exitCode()
            + (r.error() == null
                ? ", rollup mismatch " + r.rollupMismatchMinutes() + " min"
                : ", " + r.error()));
  }

  /** Sends a few requests and waits until shop-boot3's first transactions are stored. */
  private void awaitIngest(ServerTestEnvironment env, RunningApp shop, int appId)
      throws InterruptedException, IOException {
    ClickHouseHttp migrator = env.clickHouse(Account.MIGRATOR);
    long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
    while (System.nanoTime() < deadline) {
      HttpResult r = shop.get("/api/orders/1");
      if (r.status() != 200) {
        throw new IllegalStateException("shop-boot3 answered " + r.status() + " to /api/orders/1");
      }
      if (count(migrator, "SELECT count() FROM argos.xlog WHERE app_id = " + appId) > 0) {
        log("the agent's first transactions are stored");
        return;
      }
      TimeUnit.SECONDS.sleep(2);
    }
    throw new IllegalStateException(
        "No transaction of "
            + SHOP_APP
            + " was stored within 3 minutes; agent log:\n"
            + tail(shop.agentLog(), 30));
  }

  /** Waits until the stored transactions stop growing (at most 3 minutes). */
  private void drain(ServerTestEnvironment env, int[] appIds) throws InterruptedException {
    ClickHouseHttp migrator = env.clickHouse(Account.MIGRATOR);
    String sql =
        "SELECT count() FROM argos.xlog WHERE app_id IN (" + appIds[0] + "," + appIds[1] + ")";
    long last = -1;
    int stable = 0;
    long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
    while (System.nanoTime() < deadline && stable < 3) {
      TimeUnit.SECONDS.sleep(3);
      long now = count(migrator, sql);
      stable = now == last ? stable + 1 : 0;
      last = now;
    }
    f.endState.put("xlog rows of both apps after the drain", Long.toString(last));
  }

  /** Agent log counts, self-diagnostics drops and stored rows. */
  private void endOfRun(
      ServerTestEnvironment env, RunningApp shop, RunningApp batch, int[] appIds) {
    for (RunningApp app : List.of(shop, batch)) {
      String name = app.app().name();
      long errors = countLines(app.agentLogDir(), AGENT_ERROR);
      long retransform = countLines(app.agentLogDir(), RETRANSFORM);
      f.endState.put(
          "Agent log of " + name,
          errors
              + " ERROR line(s), "
              + retransform
              + " retransformation WARN/ERROR line(s) (C-257 (2))");
      SelfDiagnostics d = app.readSelfDiagnostics();
      if (d.available()) {
        long dropped =
            d.counters().entrySet().stream()
                .filter(e -> e.getKey().startsWith("argos_agent_dropped_total"))
                .mapToLong(Map.Entry::getValue)
                .sum();
        f.endState.put("Agent drops of " + name + " (self-diagnostics)", Long.toString(dropped));
      } else {
        f.endState.put(
            "Agent drops of " + name + " (self-diagnostics)", "not available: " + d.reason());
      }
    }
    ClickHouseHttp migrator = env.clickHouse(Account.MIGRATOR);
    for (int i = 0; i < appIds.length; i++) {
      f.endState.put(
          "Stored xlog / profile_step rows of " + (i == 0 ? SHOP_APP : BATCH_APP),
          count(migrator, "SELECT count() FROM argos.xlog WHERE app_id = " + appIds[i])
              + " / "
              + count(
                  migrator, "SELECT count() FROM argos.profile_step WHERE app_id = " + appIds[i]));
    }
  }

  private void oomChecks(
      ServerTestEnvironment env,
      LoadServerJvm server,
      RunningApp shop,
      RunningApp batch,
      String chId,
      String pgId,
      Path work,
      String dmesgBefore) {
    try {
      oom.exited("the server", server.isAlive(), server.exitCode().orElse(-1));
      oom.crashFiles("the server", server.workDirectory());
      long lines = server.consoleLines("OutOfMemoryError");
      if (lines > 0) {
        oom.add(lines + " OutOfMemoryError line(s) in the server console");
      }
      for (RunningApp app : java.util.Arrays.asList(shop, batch)) {
        if (app == null) {
          continue;
        }
        String name = app.app().name();
        oom.exited(name, app.isAlive(), -1);
        oom.crashFiles(name, app.workDir());
        oom.logFile(name, app.workDir().resolve("stdout.log"));
        oom.logFile(name, app.workDir().resolve("stderr.log"));
        try (Stream<Path> logs = Files.list(app.agentLogDir())) {
          for (Path l : logs.filter(Files::isRegularFile).toList()) {
            oom.logFile(name + " agent", l);
          }
        } catch (IOException e) {
          oom.notAvailable(name + " agent log directory: " + e.getMessage());
        }
      }
      oom.logFile("load generator", work.resolve("load").resolve("load-generator.log"));
      try {
        oom.clickHouseMemoryLimit(new ClickHouseProbe(env).memoryLimitErrorsInLog());
      } catch (RuntimeException e) {
        oom.notAvailable("ClickHouse error log: " + e.getMessage());
      }
      if (!chId.isEmpty()) {
        oom.containerOomKilled("ClickHouse", DockerContainers.oomKilled(chId));
      } else {
        oom.notAvailable("ClickHouse container id");
      }
      oom.containerOomKilled("PostgreSQL", DockerContainers.oomKilled(pgId));
      oom.kernelLog(dmesgBefore, dmesg());
      oomChecked = true;
    } catch (RuntimeException e) {
      f.note("OOM checks failed: " + e);
    }
  }

  /** The kernel log on Linux, null elsewhere or when it cannot be read. */
  private static String dmesg() {
    if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux")) {
      return null;
    }
    try {
      Process p = new ProcessBuilder("dmesg").redirectErrorStream(true).start();
      String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      return p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0 ? out : null;
    } catch (IOException e) {
      return null;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    }
  }

  private Process startExtStub(Path dir, int port) throws IOException, InterruptedException {
    Files.createDirectories(dir);
    Process p =
        new ProcessBuilder(
                Paths.get(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx256m",
                "-jar",
                o.extStubJar().toAbsolutePath().toString(),
                "--port",
                Integer.toString(port),
                "--bind",
                "127.0.0.1")
            .directory(dir.toFile())
            .redirectErrorStream(true)
            .redirectOutput(dir.resolve("ext-stub.log").toFile())
            .start();
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
    while (System.nanoTime() < deadline) {
      try (Socket s = new Socket()) {
        s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 1_000);
        return p;
      } catch (IOException e) {
        if (!p.isAlive()) {
          throw new IllegalStateException("ext-stub exited with " + p.exitValue(), e);
        }
        TimeUnit.MILLISECONDS.sleep(200);
      }
    }
    p.destroyForcibly();
    throw new IllegalStateException("ext-stub did not open port " + port + " within 60 s");
  }

  private void requireFiles() {
    for (Path p :
        List.of(
            o.bootJar(), o.agentJar(), o.shopJar(), o.batchJar(), o.loadJar(), o.extStubJar())) {
      if (p == null || !Files.isRegularFile(p)) {
        throw new IllegalStateException(
            p + " is missing: the soakTest task builds it (./gradlew soakTest ...)");
      }
    }
  }

  private Runnable guarded(String what, Runnable r) {
    return () -> {
      try {
        r.run();
      } catch (RuntimeException e) {
        f.note(what + " failed: " + e);
      }
    };
  }

  private long count(ClickHouseHttp client, String sql) {
    try {
      String v = client.scalar(sql).strip();
      return v.isEmpty() || v.equals("\\N") ? 0 : Long.parseLong(v);
    } catch (RuntimeException e) {
      f.note("Count failed (" + sql + "): " + e.getMessage());
      return -1;
    }
  }

  private static long countLines(Path dir, Pattern pattern) {
    if (!Files.isDirectory(dir)) {
      return 0;
    }
    long n = 0;
    try (Stream<Path> files = Files.list(dir)) {
      for (Path file : files.filter(Files::isRegularFile).toList()) {
        n += OomEvidence.countLines(file, pattern);
      }
    } catch (IOException e) {
      return -1;
    }
    return n;
  }

  private static void closeQuietly(RunningApp app) {
    if (app == null) {
      return;
    }
    try {
      app.close();
    } catch (RuntimeException e) {
      log("stopping " + app.app().name() + " failed: " + e);
    }
  }

  private void files(Path out) {
    for (String name :
        List.of(
            "report.md",
            "results.json",
            "samples.tsv",
            "work/server/server-stdout.log",
            "work/server/gc.log",
            "work/shop-boot3/logs/argos-agent.log",
            "work/shop-batch/logs/argos-agent.log",
            "work/load/load-generator.log")) {
      // report.md and results.json are written right after this list.
      if (name.endsWith(".md") || name.endsWith(".json") || Files.exists(out.resolve(name))) {
        f.files.add("test/soak/build/reports/soak/" + name);
      }
    }
  }

  static LocalDate utcDate(Instant t) {
    return LocalDate.ofInstant(t, ZoneOffset.UTC);
  }

  private static String tail(String text, int lines) {
    List<String> all = text.lines().toList();
    return String.join("\n", all.subList(Math.max(0, all.size() - lines), all.size()));
  }

  private static void writeOwnerOnly(Path file, String content) throws IOException {
    Files.deleteIfExists(file);
    if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
      Files.createFile(
          file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    } else {
      Files.createFile(file);
    }
    Files.writeString(file, content + "\n", StandardCharsets.UTF_8);
  }

  private static int freePort() throws IOException {
    try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      return s.getLocalPort();
    }
  }

  private static String mib(long bytes) {
    return String.format(Locale.ROOT, "%.0f MiB", bytes / (1024.0 * 1024));
  }

  private static String commit() {
    Path root = Path.of(System.getProperty("argos.repositoryRoot", "."));
    String head = git(root, "rev-parse", "--short", "HEAD");
    if (head.isEmpty()) {
      return "unknown";
    }
    String status = git(root, "status", "--porcelain");
    return "`" + head + "`" + (status.isEmpty() ? "" : " plus uncommitted changes");
  }

  private static String git(Path root, String... args) {
    List<String> command = new ArrayList<>(List.of("git", "-C", root.toAbsolutePath().toString()));
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
        + mib(memory)
        + " RAM";
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
