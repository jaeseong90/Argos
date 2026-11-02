/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.overhead;

import java.io.IOException;
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
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * One load set of 01 §7.3 items 2-7 (and 9, 10 by option): rounds of off/on runs (A-B-A-B-A-B),
 * each a fresh app start, a warm-up k6 run (excluded) and a measured k6 run with CPU, latency, k6
 * gate (C-46), agent thread CPU and memory (jcmd) recorded per run. The analysis is {@link
 * LoadReport}.
 */
final class LoadSet {
  private LoadSet() {}

  static final Set<String> FLAGS = Set.of("vt", "keep-app-log");

  /** The app of the run in progress, for the shutdown hook. */
  private static final AtomicReference<Platform.App> CURRENT_APP = new AtomicReference<>();

  /** eclipse-temurin:21-jdk (21.0.12.1+1), multi-arch index digest of 2026-10-17. */
  static final String DEFAULT_IMAGE =
      "eclipse-temurin:21-jdk@sha256:23cc5734ff46e776f51ce66eb9ee08444fd47caabec0a95b7eb6e94be2d3696d";

  /** alpine:3 of 2026-10-21 (cgroup sampler side container). */
  static final String SIDECAR_IMAGE =
      "alpine:3@sha256:be2656061b536c1883c4034790e21d0a09c5aaaf51968b89368a3c0371ea2c9a";

  private static final Pattern K6_IMAGE_LINE =
      Pattern.compile("(?m)^readonly K6_IMAGE=\"([^\"]+)\"");
  private static final Pattern SPACES = Pattern.compile("\\s+");

  static int run(Options o) throws Exception {
    Path repo = Path.of(o.string("repo", ".")).toAbsolutePath().normalize();
    Path benchJs = repo.resolve("sample-apps/load/bench.js");
    if (!Files.isRegularFile(benchJs)) {
      throw new UsageException("no sample-apps/load/bench.js below --repo " + repo);
    }
    Path setDir = Path.of(o.required("out")).toAbsolutePath().normalize();
    if (Files.exists(setDir.resolve("runs"))) {
      throw new UsageException(setDir + " already holds a set (choose a new --out)");
    }
    String mode = o.string("mode", "process");
    if (!mode.equals("process") && !mode.equals("container")) {
      throw new UsageException("--mode must be process or container");
    }
    String app = o.string("app", "shop-boot3");
    Path appJar =
        path(
            o, "app-jar", repo.resolve("sample-apps/" + app + "/build/sample-app/" + app + ".jar"));
    Path extJar =
        path(o, "ext-jar", repo.resolve("sample-apps/ext-stub/build/sample-app/ext-stub.jar"));
    Path agentJar = path(o, "agent", defaultAgentJar(repo));
    Path otelJar = o.optional("otel-agent").map(Path::of).map(Path::toAbsolutePath).orElse(null);
    Path java =
        path(
            o,
            "java",
            Path.of(
                System.getProperty("java.home"),
                "bin",
                Platform.isWindows() ? "java.exe" : "java"));
    int rounds = o.integer("rounds", 3, 1, 20);
    Duration warmup = o.duration("warmup", "3m");
    Duration duration = o.duration("duration", "10m");
    int rate = o.integer("rate", 1000, 1, 100_000);
    int preVus = o.integer("pre-vus", 300, 1, 100_000);
    int maxVus = o.integer("max-vus", 600, 1, 100_000);
    String heap = o.string("heap", mode.equals("container") ? "1g" : "2g");
    List<String> jvmOptions =
        new ArrayList<>(
            List.of("-Xms" + heap, "-Xmx" + heap, "-XX:+UseG1GC", "-XX:+AlwaysPreTouch"));
    jvmOptions.addAll(words(o.string("jvm-opts", "")));
    Map<String, String> appEnv = new LinkedHashMap<>();
    if (o.flag("vt")) {
      appEnv.put("SAMPLE_VT", "true");
    }
    List<String> variants = o.list("variants", "off,on");
    if (!variants.contains("off") || !variants.contains("on")) {
      throw new UsageException("--variants needs off and on (otel optional)");
    }
    Map<String, String> agentConfig = keyValues(o.string("agent-config", "exporter=noop"));
    Map<String, Map<String, String>> variantConfigs =
        Platform.parseVariantDefs(o.string("variant-defs", ""));
    for (String variant : variants) {
      if (!List.of("off", "on", "otel").contains(variant) && !variantConfigs.containsKey(variant)) {
        throw new UsageException(
            "unknown variant " + variant + " (off, on, otel or --variant-defs)");
      }
    }
    String k6Ssh = o.optional("k6-ssh").orElse(null);
    String k6 = k6Ssh == null ? resolveK6(o.string("k6", "auto"), repo) : o.string("k6", "k6");
    String appAddress = o.string("app-address", "127.0.0.1");
    String k6Image = o.string("k6-image", pinnedK6Image(repo));
    String image = o.string("image", DEFAULT_IMAGE);
    int cpus = o.integer("cpus", 2, 1, 256);
    String memory = o.string("memory", "2g");
    double maxLoad = o.decimal("max-load", 50);
    Duration maxWait = o.duration("max-wait", "10m");
    Duration settle = o.duration("settle", "20s");
    double maxBackground = o.decimal("max-background", 25);
    int retries = o.integer("retries", 3, 0, 50);
    Duration quiet = o.duration("quiet", "15s");
    int quietChecks = (int) Math.max(1, quiet.toSeconds() / QUIET_CHECK_SECONDS);
    boolean keepLog = o.flag("keep-app-log");
    String label = o.string("label", "");
    o.rejectUnknown();
    if (k6Ssh != null && (mode.equals("container") || appAddress.startsWith("127."))) {
      throw new UsageException(
          "--k6-ssh needs --mode process and an --app-address the load machine reaches");
    }
    if (variants.contains("otel") && otelJar == null) {
      throw new UsageException("variant otel needs --otel-agent <opentelemetry-javaagent.jar>");
    }

    Path bin = Files.createDirectories(setDir.resolve("bin"));
    // Copies: other work on the machine may rebuild the jars while the set runs.
    FileTools.copyInto(appJar, bin);
    FileTools.copyInto(extJar, bin);
    FileTools.copyInto(agentJar, bin);
    if (otelJar != null) {
      FileTools.copyInto(otelJar, bin);
    }
    Platform.Setup setup =
        new Platform.Setup(
            repo,
            setDir,
            bin,
            java,
            image,
            SIDECAR_IMAGE,
            app,
            appJar,
            extJar,
            agentJar,
            otelJar,
            jvmOptions,
            appEnv,
            agentConfig,
            variantConfigs,
            k6,
            k6Ssh,
            k6Image,
            benchJs,
            cpus,
            memory,
            appAddress);
    Platform platform =
        mode.equals("container")
            ? new ContainerPlatform(setup)
            : new ProcessPlatform(setup, keepLog);

    Map<String, Object> set = new LinkedHashMap<>();
    set.put("kind", "load");
    set.put("label", label);
    set.put("startedAt", Instant.now().toString());
    Map<String, Object> parameters = new LinkedHashMap<>();
    parameters.put("mode", mode);
    parameters.put("app", app);
    parameters.put("rounds", rounds);
    parameters.put("variants", variants);
    parameters.put("order", "per round: " + String.join(", ", variants) + " (A-B alternation)");
    parameters.put("warmupSeconds", warmup.toSeconds());
    parameters.put("durationSeconds", duration.toSeconds());
    parameters.put("rate", rate);
    parameters.put("preAllocatedVUs", preVus);
    parameters.put("maxVUs", maxVus);
    parameters.put("jvmOptions", jvmOptions);
    parameters.put(
        "jvmOptionsAddedByHarness",
        List.of("-Xlog:gc:file=<run>/gc.log", "-XX:NativeMemoryTracking=summary"));
    parameters.put("appEnv", appEnv);
    parameters.put("agentConfig", agentConfig);
    parameters.put("agentConfigSha256", FileTools.sha256(agentConfig.toString()));
    parameters.put("variantDefs", variantConfigs);
    parameters.put("k6", k6);
    parameters.put("k6Image", k6Image);
    parameters.put("k6Ssh", k6Ssh);
    parameters.put("appAddress", appAddress);
    if (mode.equals("container")) {
      parameters.put("cpus", cpus);
      parameters.put("memory", memory);
    }
    parameters.put("maxLoadPercent", maxLoad);
    parameters.put("maxWaitSeconds", maxWait.toSeconds());
    parameters.put("settleSeconds", settle.toSeconds());
    parameters.put("maxBackgroundCpuPercent", maxBackground);
    parameters.put("retriesPerRun", retries);
    parameters.put("quietSeconds", quietChecks * QUIET_CHECK_SECONDS);
    set.put("parameters", parameters);
    Map<String, Object> inputs = new LinkedHashMap<>();
    for (Path file :
        Stream.of(appJar, extJar, agentJar, otelJar, benchJs).filter(Objects::nonNull).toList()) {
      inputs.put(
          file.getFileName().toString(),
          Map.of(
              "path",
              file.toString(),
              "sha256",
              FileTools.sha256(file),
              "bytes",
              Files.size(file)));
    }
    set.put("inputs", inputs);
    set.put("git", Environment.git(repo));
    set.put("environment", platform.environment());
    FileTools.writeJson(setDir.resolve("set.json"), set);
    log(
        "set "
            + setDir
            + " ("
            + mode
            + ", "
            + rounds
            + " rounds of "
            + variants
            + ", warm-up "
            + warmup.toSeconds()
            + " s, window "
            + duration.toSeconds()
            + " s, "
            + rate
            + "/s)");

    // Ctrl+C or SIGTERM: stop the app, ext-stub and containers this set started.
    Thread hook =
        new Thread(
            () -> {
              Platform.App running = CURRENT_APP.getAndSet(null);
              try {
                if (running != null) {
                  running.stop(setDir, false);
                }
              } catch (InterruptedException | RuntimeException e) {
                System.err.println("overhead-bench: stopping the app: " + e);
              }
              platform.close();
            },
            "overhead-shutdown");
    Runtime.getRuntime().addShutdownHook(hook);
    try (platform) {
      platform.startDependencies();
      int index = 0;
      for (int round = 1; round <= rounds; round++) {
        for (String variant : variants) {
          index++;
          if (index > 1) {
            Thread.sleep(settle.toMillis());
          }
          String name = String.format(Locale.ROOT, "%02d-%s", index, variant);
          Path runDir = setDir.resolve("runs").resolve(name);
          List<Object> discarded = new ArrayList<>();
          for (int attempt = 1; ; attempt++) {
            Files.createDirectories(runDir);
            Noise noise = new Noise(maxBackground, attempt <= retries, quietChecks);
            Map<String, Object> result =
                runOne(
                    platform, runDir, index, round, variant, warmup, duration, rate, preVus, maxVus,
                    maxLoad, maxWait, keepLog, noise);
            result.put("attempt", attempt);
            Object reason = result.get("discarded");
            if (reason == null) {
              result.put("discardedAttempts", discarded);
              FileTools.writeJson(runDir.resolve("run.json"), result);
              break;
            }
            // A noisy attempt (other work on the machine) is kept for evidence and repeated.
            FileTools.writeJson(runDir.resolve("attempt.json"), result);
            Path moved = setDir.resolve("runs").resolve(name + "-discarded-" + attempt);
            FileTools.moveDirectory(runDir, moved);
            discarded.add(Map.of("dir", moved.getFileName().toString(), "reason", reason));
            log("run " + index + ": attempt " + attempt + " discarded (" + reason + "), repeating");
            Thread.sleep(settle.toMillis());
          }
        }
      }
    }
    set.put("finishedAt", Instant.now().toString());
    FileTools.writeJson(setDir.resolve("set.json"), set);
    return LoadReport.write(setDir);
  }

  private static Map<String, Object> runOne(
      Platform platform,
      Path runDir,
      int index,
      int round,
      String variant,
      Duration warmup,
      Duration duration,
      int rate,
      int preVus,
      int maxVus,
      double maxLoad,
      Duration maxWait,
      boolean keepLog,
      Noise noise)
      throws Exception {
    Map<String, Object> run = new LinkedHashMap<>();
    run.put("index", index);
    run.put("round", round);
    run.put("variant", variant);
    run.put("mode", platform.mode());
    Map<String, Object> before = waitQuiet(maxLoad, maxWait, noise.quietChecks());
    // Again after the wait: on the shared machine a clean-up of empty directories removed the run
    // directory while the harness waited for a quiet period.
    Files.createDirectories(runDir);
    String processes = Environment.topProcesses();
    Files.writeString(runDir.resolve("processes-before.txt"), processes, StandardCharsets.UTF_8);
    run.put("before", before);
    run.put("startedAt", Instant.now().toString());
    log(
        String.format(
            Locale.ROOT, "run %d (round %d, %s): starting the app", index, round, variant));
    Platform.App app = platform.startApp(variant, runDir);
    CURRENT_APP.set(app);
    try {
      Map<String, Object> startup = new LinkedHashMap<>();
      startup.put("spawnToPortMs", app.spawnToPortMillis());
      startup.put("spawnToReadyMs", app.spawnToReadyMillis());
      run.put("startup", startup);

      log("run " + index + ": warm-up " + warmup.toSeconds() + " s");
      long warmCpu0 = app.cpuNanos();
      MachineMonitor warmMonitor = MachineMonitor.start(Duration.ofSeconds(1), Map.of());
      Platform.K6Run warm;
      List<MachineMonitor.Sample> warmSamples;
      try {
        warm =
            platform.runK6(
                app,
                runDir,
                "k6-warmup",
                k6Env(rate, warmup, preVus, maxVus, false),
                warmup.plusMinutes(5));
      } finally {
        warmSamples = warmMonitor.stop();
      }
      long warmCpu1 = app.cpuNanos();
      Map<String, Object> warmResult = k6Summary(runDir.resolve("k6-warmup.json"), warm);
      double warmHost =
          Json.number(MachineMonitor.toJson(warmSamples, List.of()).get("systemCpuPercentMean"));
      double warmBackground =
          noise.background(
              warmHost, (warmCpu1 - warmCpu0) / 1e9, warm.k6CpuSeconds(), warm.wallSeconds());
      warmResult.put("hostCpuPercentMean", warmHost);
      warmResult.put("backgroundCpuPercentMean", warmBackground);
      run.put("warmup", warmResult);
      if (noise.tooBusy(warmBackground)) {
        run.put(
            "discarded",
            String.format(
                Locale.ROOT,
                "background CPU %.0f%% > %.0f%% during the warm-up",
                warmBackground,
                noise.maxBackground()));
        return run;
      }

      log("run " + index + ": measured window " + duration.toSeconds() + " s");
      Parsers.ThreadDump threadsStart =
          Parsers.threadDump(save(runDir, "threads-start.txt", app.jcmd("Thread.print")));
      long cpu0 = app.cpuNanos();
      Map<String, LongSupplier> counters = new LinkedHashMap<>();
      if (platform.mode().equals("process")) {
        counters.put("app", app::cpuNanos);
      }
      MachineMonitor monitor = MachineMonitor.start(Duration.ofSeconds(1), counters);
      Platform.K6Run measured;
      List<MachineMonitor.Sample> samples;
      try {
        measured =
            platform.runK6(
                app,
                runDir,
                "k6",
                k6Env(rate, duration, preVus, maxVus, true),
                duration.plusMinutes(5));
      } finally {
        samples = monitor.stop();
      }
      long cpu1 = app.cpuNanos();
      Parsers.ThreadDump threadsEnd =
          Parsers.threadDump(save(runDir, "threads-end.txt", app.jcmd("Thread.print")));
      Map<String, Object> machine = MachineMonitor.toJson(samples, monitor.counterNames());
      FileTools.writeJson(runDir.resolve("machine.json"), machine);

      Map<String, Object> m = k6Summary(runDir.resolve("k6.json"), measured);
      double wall = measured.wallSeconds();
      double cpuSeconds = cpu0 < 0 || cpu1 < 0 ? Double.NaN : (cpu1 - cpu0) / 1e9;
      m.put("wallSeconds", wall);
      m.put("appCpuSeconds", cpuSeconds);
      m.put("appCores", cpuSeconds / wall);
      double requests = Json.number(m.get("requests"));
      m.put("appCpuMsPerRequest", requests > 0 ? cpuSeconds * 1000 / requests : Double.NaN);
      m.put("k6CpuSeconds", measured.k6CpuSeconds());
      m.put("hostCpuPercentMean", machine.get("systemCpuPercentMean"));
      m.put("hostCpuPercentMax", machine.get("systemCpuPercentMax"));
      m.put("availableMemoryMbMin", machine.get("availableMemoryMbMin"));
      List<List<Double>> timeline = appTimeline(platform.mode(), samples, measured);
      m.put("appCpuTimeline", timeline);
      double loaderCpu = machineMean(machine, measured);
      m.put("loaderMachineCpuPercentMean", loaderCpu);
      if (!measured.loaderTimeline().isEmpty()) {
        double[] vm =
            measured.loaderTimeline().stream()
                .mapToDouble(r -> r.get(1))
                .filter(v -> !Double.isNaN(v))
                .toArray();
        m.put("loaderTimelineCpuPercentMean", Stats.mean(vm));
        m.put("loaderTimelineCpuPercentMax", Stats.max(vm));
      }
      double agentCpuMs = agentThreadCpuMs(threadsStart, threadsEnd);
      m.put("agentThreadCpuSeconds", agentCpuMs / 1000);
      m.put("agentThreads", new ArrayList<>(threadsEnd.agentThreadCpuMs().keySet()));
      m.put("javaThreadsAtEnd", threadsEnd.javaThreads());
      m.put("gate", gate(m, rate, duration, loaderCpu));
      double background =
          noise.background(
              Json.number(machine.get("systemCpuPercentMean")),
              cpuSeconds,
              measured.k6CpuSeconds(),
              wall);
      m.put("backgroundCpuPercentMean", background);
      run.put("measure", m);
      if (noise.tooBusy(background)
          || (noise.mayRetry() && "INVALID".equals(Json.at(m, "gate", "verdict")))) {
        run.put(
            "discarded",
            String.format(
                Locale.ROOT,
                "background CPU %.0f%% (limit %.0f%%), gate %s in the window",
                background,
                noise.maxBackground(),
                Json.at(m, "gate", "verdict")));
        return run;
      }

      log("run " + index + ": memory (jcmd GC.run x2, class histogram, NMT)");
      save(runDir, "gc-run-1.txt", app.jcmd("GC.run"));
      save(runDir, "gc-run-2.txt", app.jcmd("GC.run"));
      Parsers.AgentHeap heap =
          Parsers.classHistogram(
              save(runDir, "class-histogram.txt", app.jcmd("GC.class_histogram")));
      Parsers.NativeMemory nmt =
          Parsers.nativeMemory(
              save(runDir, "nmt-summary.txt", app.jcmd("VM.native_memory", "summary")));
      save(runDir, "metaspace.txt", app.jcmd("VM.metaspace"));
      Map<String, Object> memory = new LinkedHashMap<>();
      memory.put("agentHeapBytes", heap.bytes());
      memory.put("agentHeapInstances", heap.instances());
      memory.put("histogramTotalBytes", heap.totalBytes());
      memory.put("agentTopClasses", heap.topClasses());
      memory.put("nmtTotalCommittedKb", nmt.totalCommittedKb());
      memory.put("nmtCommittedKb", nmt.committedKb());
      memory.put("nmtThreads", nmt.threads());
      memory.put("classes", nmt.classes());
      memory.put("metaspaceUsedKb", nmt.metaspaceUsedKb());
      memory.put("metaspaceCommittedKb", nmt.metaspaceCommittedKb());
      memory.put("javaThreads", threadsEnd.javaThreads());
      run.put("memory", memory);
      OptionalDouble premain = Parsers.premainMillis(app.agentLog());
      run.put("premainMs", premain.isPresent() ? premain.getAsDouble() : null);
    } finally {
      CURRENT_APP.set(null);
      app.stop(runDir, keepLog);
    }
    Files.writeString(
        runDir.resolve("processes-after.txt"), Environment.topProcesses(), StandardCharsets.UTF_8);
    run.put("finishedAt", Instant.now().toString());
    Map<String, Object> measure = Json.object(run.get("measure"));
    log(
        String.format(
            Locale.ROOT,
            "run %d (%s): app CPU %.1f s, p99 %s ms, dropped %s, unexpected %s, host CPU %.0f%%, gate %s",
            index,
            variant,
            Json.number(measure.get("appCpuSeconds")),
            Json.number(measure, "latencyMs", "p99"),
            measure.get("dropped"),
            measure.get("unexpectedRate"),
            Json.number(measure.get("hostCpuPercentMean")),
            Json.at(measure, "gate", "verdict")));
    return run;
  }

  /** bench.js environment of one k6 invocation (01 §7.3 item 3, ADR-0004 C-46). */
  static Map<String, String> k6Env(
      int rate, Duration duration, int preVus, int maxVus, boolean measured) {
    Map<String, String> env = new LinkedHashMap<>();
    env.put("RATE", Integer.toString(rate));
    env.put("DURATION", Options.k6Duration(duration));
    env.put("WARMUP", "0s"); // the warm-up is its own k6 invocation, so drops are per window
    env.put("PRE_ALLOCATED_VUS", Integer.toString(preVus));
    env.put("MAX_VUS", Integer.toString(maxVus));
    env.put("REQUEST_TIMEOUT", "10s");
    env.put("GATE", measured ? "on" : "off");
    env.put("MAX_UNEXPECTED_RATE", "0"); // C-46: unexpected outcomes 0%
    env.put("MAX_DROPPED_RATE", "0.01"); // C-46: dropped <= 1% of the planned iterations
    return env;
  }

  /** C-46: dropped <= 1% of planned, unexpected 0%, loader not saturated (mean CPU < 80%). */
  static Map<String, Object> gate(
      Map<String, Object> measure, int rate, Duration duration, double loaderCpu) {
    Map<String, Object> gate = new LinkedHashMap<>();
    double planned = (double) rate * duration.toSeconds();
    double dropped = Json.number(measure.get("dropped"));
    double unexpected = Json.number(measure.get("unexpectedRate"));
    boolean droppedOk = dropped <= 0.01 * planned;
    boolean unexpectedOk = unexpected == 0;
    boolean saturated = loaderCpu >= 80;
    gate.put("plannedIterations", (long) planned);
    gate.put("dropped", dropped);
    gate.put("droppedLimit", (long) Math.floor(0.01 * planned));
    gate.put("droppedOk", droppedOk);
    gate.put("unexpectedRate", unexpected);
    gate.put("unexpectedOk", unexpectedOk);
    gate.put("loaderCpuPercentMean", loaderCpu);
    gate.put("loaderSaturated", saturated);
    gate.put("verdict", saturated ? "INVALID" : droppedOk && unexpectedOk ? "PASS" : "FAIL");
    return gate;
  }

  /** The subset of bench.js's summary that the report uses (the full file stays next to it). */
  static Map<String, Object> k6Summary(Path file, Platform.K6Run k6) throws IOException {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("k6ExitCode", k6.exitCode());
    if (!Files.isRegularFile(file)) {
      out.put("error", "k6 wrote no summary (" + file.getFileName() + ")");
      return out;
    }
    Object results = Json.at(FileTools.readJson(file), "results");
    for (String key :
        List.of(
            "requests",
            "achievedRate",
            "unexpectedRate",
            "checksRate",
            "http5xxRate",
            "iterationsWholeRun")) {
      out.put(key, Json.at(results, key));
    }
    out.put("dropped", Json.at(results, "droppedIterationsWholeRun"));
    out.put("droppedRate", Json.at(results, "droppedRate"));
    out.put("latencyMs", Json.at(results, "latencyMs"));
    List<Object> routes = new ArrayList<>();
    for (Object route : Json.array(Json.at(results, "routes"))) {
      Map<String, Object> r = new LinkedHashMap<>();
      r.put("route", Json.at(route, "route"));
      r.put("requests", Json.at(route, "requests"));
      r.put("latencyMs", Json.at(route, "latencyMs"));
      routes.add(r);
    }
    out.put("routes", routes);
    out.put("thresholdsPassed", Json.at(results, "thresholdsPassed"));
    return out;
  }

  private static List<List<Double>> appTimeline(
      String mode, List<MachineMonitor.Sample> samples, Platform.K6Run k6) {
    if (!mode.equals("process")) {
      return k6.appTimeline();
    }
    List<List<Double>> rows = new ArrayList<>();
    double first = Double.NaN;
    for (MachineMonitor.Sample s : samples) {
      double cpu = s.counters().isEmpty() ? Double.NaN : s.counters().get(0);
      if (Double.isNaN(cpu)) {
        continue;
      }
      if (Double.isNaN(first)) {
        first = cpu;
      }
      rows.add(List.of(MachineMonitor.round3(s.seconds()), MachineMonitor.round3(cpu - first)));
    }
    return rows;
  }

  /** CPU load of the machine that runs k6: the Docker machine for containers, else this host. */
  private static double machineMean(Map<String, Object> host, Platform.K6Run k6) {
    if (!k6.loaderTimeline().isEmpty()) {
      return Stats.mean(
          k6.loaderTimeline().stream()
              .mapToDouble(r -> r.get(1))
              .filter(v -> !Double.isNaN(v))
              .toArray());
    }
    return Json.number(host.get("systemCpuPercentMean"));
  }

  static double agentThreadCpuMs(Parsers.ThreadDump start, Parsers.ThreadDump end) {
    double total = 0;
    for (Map.Entry<String, Double> e : end.agentThreadCpuMs().entrySet()) {
      double before = start.agentThreadCpuMs().getOrDefault(e.getKey(), 0.0);
      total += e.getValue() - (Double.isNaN(before) ? 0 : before);
    }
    return total;
  }

  /**
   * Interference policy of one attempt: the machine's CPU load minus what the app and k6 used
   * ("background", in % of all logical CPUs) above {@code maxBackground} makes the attempt noisy; a
   * noisy attempt is repeated while {@code mayRetry}. A run starts after {@code quietChecks}
   * consecutive quiet 5-second checks ({@code --quiet}).
   */
  record Noise(double maxBackground, boolean mayRetry, int quietChecks) {
    double background(double hostPercent, double appCpuSeconds, double k6CpuSeconds, double wall) {
      double cpus = Runtime.getRuntime().availableProcessors();
      double own = (appCpuSeconds + (Double.isNaN(k6CpuSeconds) ? 0 : k6CpuSeconds)) / wall;
      return hostPercent - 100 * own / cpus;
    }

    boolean tooBusy(double background) {
      return mayRetry && background > maxBackground;
    }
  }

  /** Length of one quiet check of {@link #waitQuiet}. */
  static final int QUIET_CHECK_SECONDS = 5;

  /**
   * Waits (up to {@code maxWait}) until the 5-second machine CPU load is at most {@code maxLoad}.
   */
  static Map<String, Object> waitQuiet(double maxLoad, Duration maxWait)
      throws InterruptedException {
    return waitQuiet(maxLoad, maxWait, 1);
  }

  /**
   * Waits (up to {@code maxWait}) until {@code checks} consecutive 5-second samples of the machine
   * CPU load are at most {@code maxLoad} (a short gap in other work is not taken for a quiet
   * period).
   */
  static Map<String, Object> waitQuiet(double maxLoad, Duration maxWait, int checks)
      throws InterruptedException {
    long start = System.nanoTime();
    double load;
    int quiet = 0;
    while (true) {
      load = MachineMonitor.cpuLoadOver(Duration.ofSeconds(QUIET_CHECK_SECONDS));
      quiet = load <= maxLoad ? quiet + 1 : 0;
      if (quiet >= checks || System.nanoTime() - start > maxWait.toNanos()) {
        break;
      }
      if (quiet == 0) {
        log(String.format(Locale.ROOT, "machine CPU %.0f%% > %.0f%%: waiting", load, maxLoad));
        Thread.sleep(10_000);
      }
    }
    Map<String, Object> before = new LinkedHashMap<>();
    before.put("cpuPercent5s", load);
    before.put("availableMemoryMb", MachineMonitor.availableMemoryMb());
    before.put("waitedSeconds", (System.nanoTime() - start) / 1e9);
    before.put("quietChecks", quiet);
    before.put("quiet", quiet >= checks);
    return before;
  }

  private static String save(Path dir, String name, Cmd.Result result) throws IOException {
    Files.writeString(dir.resolve(name), result.output(), StandardCharsets.UTF_8);
    if (!result.ok()) {
      throw new IllegalStateException(
          "jcmd failed (" + result.exitCode() + "), see " + dir.resolve(name));
    }
    return result.output();
  }

  private static Path path(Options o, String name, Path fallback) {
    return o.optional(name).map(Path::of).orElse(fallback).toAbsolutePath().normalize();
  }

  static Path defaultAgentJar(Path repo) throws IOException {
    Path libs = repo.resolve("agent/agent-dist/build/libs");
    if (!Files.isDirectory(libs)) {
      return libs.resolve("argos-agent-0.0.0-SNAPSHOT.jar");
    }
    try (Stream<Path> files = Files.list(libs)) {
      return files
          .filter(p -> p.getFileName().toString().matches("argos-agent-.*\\.jar"))
          .max(Comparator.comparingLong(p -> p.toFile().lastModified()))
          .orElse(libs.resolve("argos-agent-0.0.0-SNAPSHOT.jar"));
    }
  }

  /** {@code auto}: build/k6/k6[.exe] from fetch-k6.sh when present, else the pinned image. */
  static String resolveK6(String k6, Path repo) {
    if (!k6.equals("auto")) {
      return k6.equals("docker") ? k6 : Path.of(k6).toAbsolutePath().toString();
    }
    Path local =
        repo.resolve("test/agent/overhead/build/k6")
            .resolve(Platform.isWindows() ? "k6.exe" : "k6");
    return Files.isRegularFile(local) ? local.toString() : "docker";
  }

  /** The grafana/k6 image pinned by sample-apps/load/run-bench.sh (single source of the digest). */
  static String pinnedK6Image(Path repo) throws IOException {
    String script =
        Files.readString(repo.resolve("sample-apps/load/run-bench.sh"), StandardCharsets.UTF_8);
    Matcher m = K6_IMAGE_LINE.matcher(script);
    if (!m.find()) {
      throw new UsageException("no K6_IMAGE in sample-apps/load/run-bench.sh; pass --k6-image");
    }
    return m.group(1);
  }

  static Map<String, String> keyValues(String text) {
    Map<String, String> map = new LinkedHashMap<>();
    for (String pair : text.split(",", -1)) {
      String p = pair.trim();
      if (p.isEmpty()) {
        continue;
      }
      int eq = p.indexOf('=');
      if (eq <= 0) {
        throw new UsageException("expected key=value: " + p);
      }
      map.put(p.substring(0, eq).trim(), p.substring(eq + 1).trim());
    }
    return map;
  }

  static List<String> words(String text) {
    return text.isBlank() ? List.of() : List.of(SPACES.split(text.trim(), -1));
  }

  static void log(String message) {
    System.out.println("[overhead " + Instant.now().toString().substring(11, 19) + "Z] " + message);
    System.out.flush();
  }
}
