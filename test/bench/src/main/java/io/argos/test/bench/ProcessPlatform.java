/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.bench;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The sample app and ext-stub as java processes on this machine (127.0.0.1), k6 as a native binary
 * ({@code --k6 <path>}, fetch-k6.sh) or as the pinned grafana/k6 container ({@code --k6 docker}; on
 * Linux with the host network). On Windows use the native binary: Docker Desktop's
 * container-to-host proxy loses requests at 1,000/s (sample-apps/load/README.md).
 */
final class ProcessPlatform implements Platform {
  private static final Duration READY_TIMEOUT = Duration.ofMinutes(5);

  /** Lines of the app output kept in the run directory when the full log is not kept. */
  static final int LOG_HEAD_LINES = 300;

  static final int LOG_TAIL_LINES = 200;

  private final Setup setup;
  private final boolean keepAppLog;
  private Process ext;
  private int extPort;
  private int k6Runs;

  /**
   * @param keepAppLog write the app's whole output to {@code app.log}; otherwise the output is read
   *     from a pipe while the app runs and only its head, tail and WARN/ERROR counts are written
   *     (the intended 5% errors log about 250 MB of stack traces per run: writing, virus-scanning
   *     and deleting that file after every run loaded the shared machine during the next run)
   */
  ProcessPlatform(Setup setup, boolean keepAppLog) {
    this.setup = setup;
    this.keepAppLog = keepAppLog;
  }

  @Override
  public String mode() {
    return "process";
  }

  @Override
  public void startDependencies() throws IOException, InterruptedException {
    extPort = Net.freePort();
    Path tmp = Files.createDirectories(setup.setDir().resolve("ext-stub-tmp"));
    List<String> command =
        List.of(
            setup.java().toString(),
            "-Dsun.net.httpserver.nodelay=true",
            "-Djava.io.tmpdir=" + tmp,
            "-jar",
            setup.binDir().resolve(setup.extJar().getFileName()).toString(),
            "--port",
            Integer.toString(extPort),
            "--bind",
            "127.0.0.1",
            "--threads",
            "64");
    ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
    clean(builder.environment(), tmp);
    builder.redirectOutput(setup.setDir().resolve("ext-stub.log").toFile());
    ext = builder.start();
    if (!Net.await200("http://127.0.0.1:" + extPort + "/_stub/health", Duration.ofMinutes(1))) {
      throw new IllegalStateException(
          "ext-stub not ready, see " + setup.setDir().resolve("ext-stub.log"));
    }
  }

  private static void clean(Map<String, String> env, Path tmp) {
    env.keySet().removeIf(k -> k.toUpperCase(Locale.ROOT).startsWith("ARGOS_"));
    env.remove("JAVA_TOOL_OPTIONS");
    env.remove("JDK_JAVA_OPTIONS");
    env.remove("_JAVA_OPTIONS");
    for (String name : List.of("TMP", "TEMP", "TMPDIR")) {
      env.put(name, tmp.toString());
    }
  }

  /** The java command line of the app (also written to the run's run.json). */
  List<String> appCommand(String variant, Path runDir, int port) {
    List<String> command = new ArrayList<>();
    command.add(setup.java().toString());
    command.addAll(setup.jvmOptions());
    command.add(
        "-Xlog:gc:file=gc.log"); // relative: run directory (a drive letter's ':' breaks -Xlog)
    command.add("-XX:NativeMemoryTracking=summary");
    command.add("-Djava.io.tmpdir=" + runDir.resolve("tmp"));
    command.addAll(
        Platform.variantOptions(
            variant,
            setup,
            setup.binDir().resolve(setup.agentJar().getFileName()).toString(),
            setup.otelJar() == null
                ? null
                : setup.binDir().resolve(setup.otelJar().getFileName()).toString(),
            runDir.resolve("agent-logs").toString()));
    command.add("-jar");
    command.add(setup.binDir().resolve(setup.appJar().getFileName()).toString());
    command.add("--server.address=" + setup.appAddress());
    command.add("--server.port=" + port);
    return command;
  }

  @Override
  public App startApp(String variant, Path runDir) throws IOException, InterruptedException {
    int port = Net.freePort();
    Path tmp = Files.createDirectories(runDir.resolve("tmp"));
    List<String> command = appCommand(variant, runDir, port);
    Files.writeString(runDir.resolve("command.txt"), String.join("\n", command) + "\n");
    ProcessBuilder builder =
        new ProcessBuilder(command).directory(runDir.toFile()).redirectErrorStream(true);
    clean(builder.environment(), tmp);
    builder.environment().put("SAMPLE_EXT_URL", "http://127.0.0.1:" + extPort);
    builder.environment().putAll(setup.appEnv());
    if (keepAppLog) {
      builder.redirectOutput(runDir.resolve("app.log").toFile());
    }
    long spawn = System.nanoTime();
    Process process = builder.start();
    LocalApp app = new LocalApp(process, setup.appAddress(), port, runDir, setup.java());
    if (!keepAppLog) {
      app.tapOutput();
    }
    long deadline = spawn + READY_TIMEOUT.toNanos();
    while (!Net.portOpen(setup.appAddress(), port)) {
      app.checkAlive(deadline);
      Thread.sleep(10);
    }
    long portAt = System.nanoTime();
    while (!Net.answers200(app.url("/actuator/health"))) {
      app.checkAlive(deadline);
      Thread.sleep(50);
    }
    long readyAt = System.nanoTime();
    app.toPort = TimeUnit.NANOSECONDS.toMillis(portAt - spawn);
    app.toReady = TimeUnit.NANOSECONDS.toMillis(readyAt - spawn);
    return app;
  }

  @Override
  public K6Run runK6(
      App app, Path runDir, String label, Map<String, String> env, Duration timeout, boolean raw)
      throws IOException, InterruptedException {
    LocalApp local = (LocalApp) app;
    Map<String, String> k6Env = new LinkedHashMap<>(env);
    List<String> command = new ArrayList<>();
    boolean docker = setup.k6().equals("docker");
    k6Runs++;
    String containerName = "argos-bench-k6-" + ProcessHandle.current().pid() + "-" + k6Runs;
    String remoteSummary = null;
    String remoteRaw = null;
    if (setup.k6Ssh() != null) {
      // Load generator on another machine (01 §7.3 item 1): k6 there over ssh, script on stdin.
      String remoteBase = "/tmp/argos-bench-" + ProcessHandle.current().pid() + "-" + k6Runs;
      remoteSummary = remoteBase + ".json";
      remoteRaw = raw ? remoteBase + "-raw.json.gz" : null;
      k6Env.put("TARGET_URL", "http://" + setup.appAddress() + ":" + local.port);
      k6Env.put("SUMMARY_PATH", remoteSummary);
      command.addAll(
          List.of("ssh", "-o", "BatchMode=yes", setup.k6Ssh(), "K6_NO_USAGE_REPORT=true"));
      command.addAll(List.of(setup.k6(), "run", "--quiet", "--no-color", "--no-usage-report"));
      if (raw) {
        command.addAll(List.of("--out", "json=" + remoteRaw));
      }
      k6Env.forEach((k, v) -> command.addAll(List.of("-e", k + "=" + v)));
      command.add("-");
    } else if (docker) {
      String host = Platform.isLinux() ? "127.0.0.1" : "host.docker.internal";
      k6Env.put("TARGET_URL", "http://" + host + ":" + local.port);
      k6Env.put("SUMMARY_PATH", "/out/" + label + ".json");
      command.addAll(List.of("docker", "run", "--rm", "--name", containerName));
      if (Platform.isLinux()) {
        command.addAll(List.of("--network", "host"));
        command.addAll(ContainerPlatform.linuxUser());
      } else {
        command.addAll(List.of("--add-host", "host.docker.internal:host-gateway"));
      }
      command.addAll(
          List.of(
              "-v",
              setup.benchJs().getParent() + ":/scripts:ro",
              "-v",
              runDir + ":/out",
              "-e",
              "K6_NO_USAGE_REPORT=true"));
      k6Env.forEach((k, v) -> command.addAll(List.of("-e", k + "=" + v)));
      command.addAll(List.of(setup.k6Image(), "run", "--quiet", "--no-color", "--no-usage-report"));
      if (raw) {
        command.addAll(List.of("--out", "json=/out/" + K6Raw.FILE_NAME));
      }
      command.add("/scripts/" + setup.benchJs().getFileName());
    } else {
      k6Env.put("TARGET_URL", "http://127.0.0.1:" + local.port);
      k6Env.put("SUMMARY_PATH", runDir.resolve(label + ".json").toString());
      command.addAll(List.of(setup.k6(), "run", "--quiet", "--no-color", "--no-usage-report"));
      if (raw) {
        // Relative to the working directory (the run directory): no drive letter in the option.
        command.addAll(List.of("--out", "json=" + K6Raw.FILE_NAME));
      }
      k6Env.forEach((k, v) -> command.addAll(List.of("-e", k + "=" + v)));
      command.add(setup.benchJs().toString());
    }
    ProcessBuilder builder =
        new ProcessBuilder(command).redirectErrorStream(true).directory(runDir.toFile());
    builder.environment().put("K6_NO_USAGE_REPORT", "true");
    builder.redirectOutput(runDir.resolve(label + ".log").toFile());
    if (remoteSummary != null) {
      builder.redirectInput(setup.benchJs().toFile());
    }
    long[] loaderBefore = remoteSummary == null ? null : remoteCpuTicks();
    long start = System.nanoTime();
    Process k6 = builder.start();
    double cpu = Double.NaN;
    long deadline = start + timeout.toNanos();
    while (!k6.waitFor(1, TimeUnit.SECONDS)) {
      if (!docker) {
        cpu = k6.info().totalCpuDuration().map(d -> d.toNanos() / 1e9).orElse(cpu);
      }
      if (System.nanoTime() > deadline) {
        k6.destroyForcibly();
        if (docker) {
          Cmd.run(List.of("docker", "rm", "-f", containerName), Duration.ofSeconds(30));
        }
        throw new IllegalStateException("k6 did not finish within " + timeout);
      }
    }
    double wall = (System.nanoTime() - start) / 1e9;
    List<List<Double>> loader = List.of();
    if (remoteSummary != null) {
      double busy = busyPercent(loaderBefore, remoteCpuTicks());
      loader = List.of(List.of(wall, busy));
      copyRemote(remoteSummary, runDir.resolve(label + ".json"), Duration.ofMinutes(2));
      if (remoteRaw != null) {
        copyRemote(remoteRaw, runDir.resolve(K6Raw.FILE_NAME), Duration.ofMinutes(30));
      }
      List<String> rm =
          new ArrayList<>(List.of("ssh", "-o", "BatchMode=yes", setup.k6Ssh(), "rm", "-f"));
      rm.add(remoteSummary);
      if (remoteRaw != null) {
        rm.add(remoteRaw);
      }
      Cmd.run(rm, Duration.ofMinutes(1));
    }
    return new K6Run(k6.exitValue(), wall, cpu, List.of(), loader);
  }

  /**
   * Copies a file of the load machine byte for byte ({@code ssh cat}; stderr is kept apart so that
   * an ssh warning cannot corrupt the copy).
   */
  private void copyRemote(String remote, Path local, Duration timeout)
      throws IOException, InterruptedException {
    Path errors = local.resolveSibling(local.getFileName() + ".ssh-stderr.txt");
    Process p =
        new ProcessBuilder("ssh", "-o", "BatchMode=yes", setup.k6Ssh(), "cat", remote)
            .redirectOutput(local.toFile())
            .redirectError(errors.toFile())
            .start();
    p.getOutputStream().close();
    if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
      p.destroyForcibly();
      throw new IllegalStateException(
          "copying " + remote + " from " + setup.k6Ssh() + " timed out");
    }
    if (p.exitValue() != 0) {
      throw new IllegalStateException(
          "copying " + remote + " from " + setup.k6Ssh() + " failed, see " + errors);
    }
    if (Files.size(errors) == 0) {
      Files.delete(errors);
    }
  }

  /** The first line of the load generator's /proc/stat as tick counters (ssh mode). */
  private long[] remoteCpuTicks() throws InterruptedException {
    Cmd.Result r =
        Cmd.run(
            List.of("ssh", "-o", "BatchMode=yes", setup.k6Ssh(), "head", "-n1", "/proc/stat"),
            Duration.ofMinutes(1));
    return r.ok() ? ProcFs.cpuLine(r.output()) : new long[0];
  }

  /** Busy share between two /proc/stat cpu lines (idle + iowait are idle; guest is in user). */
  static double busyPercent(long[] before, long[] after) {
    return ProcFs.busyPercent(before, after);
  }

  @Override
  public Map<String, Object> environment() throws InterruptedException {
    Map<String, Object> env = Environment.host(setup.java());
    if (setup.k6Ssh() != null) {
      List<String> ssh = List.of("ssh", "-o", "BatchMode=yes", setup.k6Ssh());
      List<String> version = new ArrayList<>(ssh);
      version.addAll(List.of(setup.k6(), "version"));
      List<String> uname = new ArrayList<>(ssh);
      uname.addAll(List.of("uname", "-a"));
      env.put(
          "k6",
          Cmd.run(version, Duration.ofMinutes(1)).output().trim() + " (on " + setup.k6Ssh() + ")");
      env.put("loadGenerator", Cmd.run(uname, Duration.ofMinutes(1)).output().trim());
    } else {
      env.put("k6", Environment.k6Version(setup.k6(), setup.k6Image()));
    }
    return env;
  }

  @Override
  public void close() {
    if (ext != null && ext.isAlive()) {
      ext.destroy();
      try {
        if (!ext.waitFor(10, TimeUnit.SECONDS)) {
          ext.destroyForcibly();
        }
      } catch (InterruptedException e) {
        ext.destroyForcibly();
        Thread.currentThread().interrupt();
      }
    }
  }

  /** A sample app started as a local java process. */
  static final class LocalApp implements App {
    private final Process process;
    private final String address;
    private final int port;
    private final Path runDir;
    private final Path jcmd;
    private long toPort = -1;
    private long toReady = -1;
    private FileTools.Excerpt output;
    private Thread outputReader;

    LocalApp(Process process, String address, int port, Path runDir, Path java) {
      this.process = process;
      this.address = address;
      this.port = port;
      this.runDir = runDir;
      Path bin = java.toAbsolutePath().getParent();
      this.jcmd = bin.resolve(Platform.isWindows() ? "jcmd.exe" : "jcmd");
    }

    /** Reads the app's output pipe on a daemon thread into an {@link FileTools.Excerpt}. */
    void tapOutput() {
      FileTools.Excerpt excerpt = new FileTools.Excerpt(LOG_HEAD_LINES, LOG_TAIL_LINES);
      output = excerpt;
      outputReader =
          Thread.ofPlatform()
              .daemon()
              .name("bench-app-output")
              .start(
                  () -> {
                    try (BufferedReader reader = FileTools.utf8Reader(process.getInputStream())) {
                      for (String line = reader.readLine();
                          line != null;
                          line = reader.readLine()) {
                        excerpt.accept(line);
                      }
                    } catch (IOException e) {
                      excerpt.accept("# bench-harness: reading the app output failed: " + e);
                    }
                  });
    }

    String url(String path) {
      return "http://" + address + ":" + port + path;
    }

    void checkAlive(long deadlineNanos) throws InterruptedException {
      if (!process.isAlive()) {
        stop(runDir, true);
        throw new IllegalStateException(
            "app exited with code " + process.exitValue() + ", see " + runDir.resolve("app*"));
      }
      if (System.nanoTime() > deadlineNanos) {
        stop(runDir, true);
        throw new IllegalStateException("app not ready in time, see " + runDir.resolve("app*"));
      }
    }

    @Override
    public long spawnToPortMillis() {
      return toPort;
    }

    @Override
    public long spawnToReadyMillis() {
      return toReady;
    }

    /**
     * 18 §7.4: utime + stime of {@code /proc/<pid>/stat} on Linux; elsewhere the OS process times
     * through {@link ProcessHandle} (Windows {@code GetProcessTimes}).
     */
    @Override
    public long cpuNanos() {
      long proc = ProcFs.processCpuNanos(process.pid());
      if (proc >= 0) {
        return proc;
      }
      return process.info().totalCpuDuration().map(Duration::toNanos).orElse(-1L);
    }

    @Override
    public String cpuSource() {
      return ProcFs.processCpuNanos(process.pid()) >= 0
          ? "/proc/<pid>/stat utime+stime (" + ProcFs.clockTicksPerSecond() + " ticks/s)"
          : "ProcessHandle total CPU (OS process times)";
    }

    @Override
    public long osThreads() {
      return ProcFs.processThreads(process.pid());
    }

    @Override
    public Cmd.Result jcmd(String... args) throws InterruptedException {
      List<String> command = new ArrayList<>();
      command.add(jcmd.toString());
      command.add(Long.toString(process.pid()));
      command.addAll(List.of(args));
      return Cmd.run(command, Duration.ofMinutes(2));
    }

    @Override
    public String agentLog() {
      return FileTools.readLenient(runDir.resolve("agent-logs").resolve("argos-agent.log"));
    }

    @Override
    public void stop(Path dir, boolean keepFullLog) throws InterruptedException {
      if (process.isAlive()) {
        process.destroy();
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
          process.destroyForcibly();
          process.waitFor(15, TimeUnit.SECONDS);
        }
      }
      try {
        if (output == null) {
          FileTools.excerpt(
              dir.resolve("app.log"),
              dir.resolve("app-log-excerpt.txt"),
              LOG_HEAD_LINES,
              LOG_TAIL_LINES,
              keepFullLog);
        } else {
          outputReader.join(TimeUnit.SECONDS.toMillis(10));
          output.write(
              dir.resolve("app-log-excerpt.txt"),
              "app output (stdout and stderr): " + output.lines() + " lines",
              " (read from a pipe, not stored)");
        }
      } catch (IOException e) {
        throw new IllegalStateException("cannot write the app log excerpt in " + dir, e);
      }
    }
  }
}
