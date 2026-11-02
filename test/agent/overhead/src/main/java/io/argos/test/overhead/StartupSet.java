/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.overhead;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.stream.Stream;

/**
 * Startup set (01 §7.3 item 8, N-04, N-27, N-30, ADR-0004 C-49): per app and CDS mode, {@code runs}
 * starts of each variant in alternation (off, on, then on, off, ...), each measured from process
 * creation to the open port (jar apps) and to the first 200 of /actuator/health, plus Spring Boot's
 * own "Started ... in" and the agent's premain time.
 */
final class StartupSet {
  private StartupSet() {}

  static final Set<String> FLAGS = Set.of();

  static int run(Options o) throws Exception {
    Path repo = Path.of(o.string("repo", ".")).toAbsolutePath().normalize();
    Path setDir = Path.of(o.required("out")).toAbsolutePath().normalize();
    if (Files.exists(setDir.resolve("runs"))) {
      throw new UsageException(setDir + " already holds a set (choose a new --out)");
    }
    List<String> apps = o.list("apps", "shop-boot3,shop-heavy");
    int runs = o.integer("runs", 10, 1, 100);
    List<String> cdsModes = o.list("cds", "default,off");
    for (String cds : cdsModes) {
      if (!cds.equals("default") && !cds.equals("off")) {
        throw new UsageException("--cds takes default and/or off");
      }
    }
    List<String> variants = o.list("variants", "off,on");
    Path java =
        o.optional("java")
            .map(Path::of)
            .orElse(
                Path.of(
                    System.getProperty("java.home"),
                    "bin",
                    Platform.isWindows() ? "java.exe" : "java"))
            .toAbsolutePath();
    Path agentJar =
        o.optional("agent").map(Path::of).orElse(LoadSet.defaultAgentJar(repo)).toAbsolutePath();
    Path otelJar = o.optional("otel-agent").map(Path::of).map(Path::toAbsolutePath).orElse(null);
    List<String> jvmOptions = LoadSet.words(o.string("jvm-opts", "-Xms2g -Xmx2g -XX:+UseG1GC"));
    Map<String, String> agentConfig = LoadSet.keyValues(o.string("agent-config", "exporter=noop"));
    Map<String, Map<String, String>> variantConfigs =
        Platform.parseVariantDefs(o.string("variant-defs", ""));
    for (String variant : variants) {
      if (!List.of("off", "on", "otel").contains(variant) && !variantConfigs.containsKey(variant)) {
        throw new UsageException(
            "unknown variant " + variant + " (off, on, otel or --variant-defs)");
      }
    }
    Duration timeout = o.duration("timeout", "5m");
    double maxLoad = o.decimal("max-load", 50);
    Duration maxWait = o.duration("max-wait", "5m");
    Duration settle = o.duration("settle", "3s");
    double maxBackground = o.decimal("max-background", 25);
    int retries = o.integer("retries", 3, 0, 20);
    String label = o.string("label", "");
    o.rejectUnknown();

    Path bin = Files.createDirectories(setDir.resolve("bin"));
    FileTools.copyInto(agentJar, bin);
    if (otelJar != null) {
      FileTools.copyInto(otelJar, bin);
    }
    Map<String, Path> appJars = new LinkedHashMap<>();
    for (String app : apps) {
      appJars.put(
          app,
          FileTools.copyInto(
              repo.resolve("sample-apps/" + app + "/build/sample-app/" + app + ".jar"), bin));
    }
    Map<String, Object> set = new LinkedHashMap<>();
    set.put("kind", "startup");
    set.put("label", label);
    set.put("startedAt", Instant.now().toString());
    Map<String, Object> parameters = new LinkedHashMap<>();
    parameters.put("apps", apps);
    parameters.put("runsPerVariant", runs);
    parameters.put("cds", cdsModes);
    parameters.put("variants", variants);
    parameters.put("order", "per start pair: off,on on odd rounds, on,off on even rounds");
    parameters.put("jvmOptions", jvmOptions);
    parameters.put("agentConfig", agentConfig);
    parameters.put("variantDefs", variantConfigs);
    parameters.put(
        "definition",
        "C-49 jar apps: process creation to the open port; also to /actuator/health 200");
    parameters.put("maxBackgroundCpuPercent", maxBackground);
    parameters.put("retriesPerStart", retries);
    set.put("parameters", parameters);
    Map<String, Object> inputs = new LinkedHashMap<>();
    for (Path file : Stream.concat(Stream.of(agentJar), appJars.values().stream()).toList()) {
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
    set.put("environment", Environment.host(java));
    FileTools.writeJson(setDir.resolve("set.json"), set);

    Platform.Setup setup =
        new Platform.Setup(
            repo,
            setDir,
            bin,
            java,
            "",
            "",
            "",
            appJars.values().iterator().next(),
            agentJar,
            agentJar,
            otelJar,
            jvmOptions,
            Map.of(),
            agentConfig,
            variantConfigs,
            "",
            null,
            "",
            repo,
            0,
            "",
            "127.0.0.1");
    int index = 0;
    for (String app : apps) {
      for (String cds : cdsModes) {
        for (int round = 1; round <= runs; round++) {
          List<String> order = new ArrayList<>(variants);
          if (round % 2 == 0) {
            Collections.reverse(order);
          }
          for (String variant : order) {
            index++;
            Thread.sleep(settle.toMillis());
            String name = String.format(Locale.ROOT, "%03d-%s-%s-%s", index, app, cds, variant);
            Path runDir = setDir.resolve("runs").resolve(name);
            Map<String, Object> before;
            Map<String, Object> run;
            List<Object> discarded = new ArrayList<>();
            for (int attempt = 1; ; attempt++) {
              before = LoadSet.waitQuiet(maxLoad, maxWait);
              Files.createDirectories(runDir);
              run = startOnce(setup, appJars.get(app), app, cds, variant, runDir, timeout);
              run.put("index", index);
              run.put("round", round);
              run.put("before", before);
              run.put("attempt", attempt);
              double background = Json.number(run.get("backgroundCpuPercentMean"));
              if (attempt > retries || !(background > maxBackground)) {
                break;
              }
              // Other work on the machine during this start: keep it for evidence, start again.
              String reason =
                  String.format(
                      Locale.ROOT, "background CPU %.0f%% > %.0f%%", background, maxBackground);
              FileTools.writeJson(runDir.resolve("attempt.json"), run);
              Path moved = setDir.resolve("runs").resolve(name + "-discarded-" + attempt);
              FileTools.moveDirectory(runDir, moved);
              discarded.add(Map.of("dir", moved.getFileName().toString(), "reason", reason));
              LoadSet.log(
                  "start " + index + ": attempt " + attempt + " discarded (" + reason + ")");
              Thread.sleep(settle.toMillis());
            }
            run.put("discardedAttempts", discarded);
            FileTools.writeJson(runDir.resolve("run.json"), run);
            LoadSet.log(
                String.format(
                    Locale.ROOT,
                    "start %d %s cds=%s %s: port %s ms, ready %s ms, premain %s ms (CPU before %.0f%%)",
                    index,
                    app,
                    cds,
                    variant,
                    run.get("spawnToPortMs"),
                    run.get("spawnToReadyMs"),
                    run.get("premainMs"),
                    Json.number(before.get("cpuPercent5s"))));
          }
        }
      }
    }
    set.put("finishedAt", Instant.now().toString());
    FileTools.writeJson(setDir.resolve("set.json"), set);
    return StartupReport.write(setDir);
  }

  static Map<String, Object> startOnce(
      Platform.Setup setup,
      Path jar,
      String app,
      String cds,
      String variant,
      Path runDir,
      Duration timeout)
      throws IOException, InterruptedException {
    int port = Net.freePort();
    Path tmp = Files.createDirectories(runDir.resolve("tmp"));
    List<String> command = new ArrayList<>();
    command.add(setup.java().toString());
    command.addAll(setup.jvmOptions());
    if (cds.equals("off")) {
      command.add("-Xshare:off");
    }
    command.add("-Djava.io.tmpdir=" + tmp);
    command.addAll(
        Platform.variantOptions(
            variant,
            setup,
            setup.binDir().resolve(setup.agentJar().getFileName()).toString(),
            setup.otelJar() == null
                ? null
                : setup.binDir().resolve(setup.otelJar().getFileName()).toString(),
            runDir.resolve("agent-logs").toString()));
    command.addAll(
        List.of("-jar", jar.toString(), "--server.address=127.0.0.1", "--server.port=" + port));
    Files.writeString(
        runDir.resolve("command.txt"), String.join("\n", command) + "\n", StandardCharsets.UTF_8);
    ProcessBuilder builder = new ProcessBuilder(command).directory(runDir.toFile());
    Map<String, String> env = builder.environment();
    env.keySet().removeIf(k -> k.toUpperCase(Locale.ROOT).startsWith("ARGOS_"));
    env.remove("JAVA_TOOL_OPTIONS");
    env.remove("JDK_JAVA_OPTIONS");
    env.remove("_JAVA_OPTIONS");
    for (String name : List.of("TMP", "TEMP", "TMPDIR")) {
      env.put(name, tmp.toString());
    }
    builder.redirectOutput(runDir.resolve("stdout.log").toFile());
    builder.redirectError(runDir.resolve("stderr.log").toFile());
    Map<String, Object> run = new LinkedHashMap<>();
    run.put("app", app);
    run.put("cds", cds);
    run.put("variant", variant);
    run.put("startedAt", Instant.now().toString());
    AtomicReference<Process> processRef = new AtomicReference<>();
    Map<String, LongSupplier> counters = new LinkedHashMap<>();
    counters.put("app", () -> cpuNanos(processRef.get()));
    MachineMonitor monitor = MachineMonitor.start(Duration.ofMillis(500), counters);
    long spawn = System.nanoTime();
    Process process = builder.start();
    processRef.set(process);
    try {
      long deadline = spawn + timeout.toNanos();
      while (!Net.portOpen(port)) {
        alive(process, deadline, runDir);
        Thread.sleep(10);
      }
      long portAt = System.nanoTime();
      while (!Net.answers200("http://127.0.0.1:" + port + "/actuator/health")) {
        alive(process, deadline, runDir);
        Thread.sleep(20);
      }
      long readyAt = System.nanoTime();
      double appCpu = cpuNanos(process) / 1e9;
      List<MachineMonitor.Sample> samples = monitor.stop();
      double host =
          Json.number(MachineMonitor.toJson(samples, List.of()).get("systemCpuPercentMean"));
      double wall = (readyAt - spawn) / 1e9;
      run.put("spawnToPortMs", TimeUnit.NANOSECONDS.toMillis(portAt - spawn));
      run.put("spawnToReadyMs", TimeUnit.NANOSECONDS.toMillis(readyAt - spawn));
      run.put("appCpuSecondsToReady", appCpu);
      run.put("hostCpuPercentMean", host);
      // The machine's load minus the app's own share, in % of all logical CPUs.
      run.put(
          "backgroundCpuPercentMean",
          host - 100 * appCpu / wall / Runtime.getRuntime().availableProcessors());
      // Spring Boot logs "Started ..." right after the port opens; give it a moment.
      double[] started = new double[0];
      for (int i = 0; i < 40 && started.length == 0; i++) {
        started = Parsers.springStarted(FileTools.readLenient(runDir.resolve("stdout.log")));
        if (started.length == 0) {
          Thread.sleep(50);
        }
      }
      run.put("springStartedSeconds", started.length > 0 ? started[0] : null);
      run.put(
          "springProcessRunningSeconds",
          started.length > 1 && !Double.isNaN(started[1]) ? started[1] : null);
      OptionalDouble premain =
          Parsers.premainMillis(
              FileTools.readLenient(runDir.resolve("agent-logs").resolve("argos-agent.log")));
      run.put("premainMs", premain.isPresent() ? premain.getAsDouble() : null);
    } finally {
      monitor.close();
      process.destroy();
      if (!process.waitFor(15, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        process.waitFor(15, TimeUnit.SECONDS);
      }
    }
    String stderr = FileTools.readLenient(runDir.resolve("stderr.log")).strip();
    run.put("stderrLines", stderr.isEmpty() ? 0 : stderr.lines().count());
    run.put("stderrFirstLine", stderr.isEmpty() ? null : stderr.lines().findFirst().orElse(null));
    return run;
  }

  private static long cpuNanos(Process process) {
    return process == null
        ? -1
        : process.info().totalCpuDuration().map(Duration::toNanos).orElse(-1L);
  }

  private static void alive(Process process, long deadline, Path runDir) {
    if (!process.isAlive()) {
      throw new IllegalStateException("app exited with " + process.exitValue() + ", see " + runDir);
    }
    if (System.nanoTime() > deadline) {
      process.destroyForcibly();
      throw new IllegalStateException("app not ready in time, see " + runDir);
    }
  }
}
