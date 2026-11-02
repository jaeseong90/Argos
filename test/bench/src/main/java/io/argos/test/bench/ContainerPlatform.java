/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.bench;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 01 §7.3 item 9 (N-29): the app in a Linux container with {@code --cpus} and {@code --memory}
 * (cgroup v2), ext-stub and k6 in containers on a private Docker network. The jars are mounted
 * read-only into a pinned JDK image (jcmd available). CPU comes from the app's cgroup {@code
 * cpu.stat}: start and end through {@code docker exec}, the per-second timeline (peak, k6 CPU and
 * the Docker machine's CPU load) from a side container that reads the host cgroup tree, so that no
 * sampling process runs inside the app's cgroup during the window.
 */
final class ContainerPlatform implements Platform {
  private static final Pattern WHITESPACE = Pattern.compile("\\s+");
  private static final Pattern LINES = Pattern.compile("\\R");
  private static final Duration READY_TIMEOUT = Duration.ofMinutes(5);

  /**
   * Side container loop: VM uptime, usage_usec of the app and k6 containers, /proc/stat cpu. No
   * double quotes: the script is one argument of a Windows command line.
   */
  private static final String SIDECAR_SCRIPT =
      """
      cg() { for p in /hostcg/docker/$1 /hostcg/system.slice/docker-$1.scope; do \
      if [ -r $p/cpu.stat ]; then sed -n 's/^usage_usec //p' $p/cpu.stat; return; fi; done; echo -; }
      while :; do
        echo S $(cut -d' ' -f1 /proc/uptime) $(cg $APP_ID) $(cg $K6_ID) $(head -n1 /proc/stat)
        sleep 1
      done
      """;

  private final Setup setup;
  private final String prefix = "argos-bench-" + ProcessHandle.current().pid();
  private final String network = prefix + "-net";
  private final String extName = prefix + "-ext";
  private boolean networkCreated;
  private int apps;
  private int k6Runs;

  ContainerPlatform(Setup setup) {
    this.setup = setup;
  }

  @Override
  public String mode() {
    return "container";
  }

  /** {@code --user uid:gid} on Linux hosts (files in bind mounts stay the user's), else empty. */
  static List<String> linuxUser() throws InterruptedException {
    if (!Platform.isLinux()) {
      return List.of();
    }
    String uid = Cmd.run(List.of("id", "-u"), Duration.ofSeconds(10)).output().trim();
    String gid = Cmd.run(List.of("id", "-g"), Duration.ofSeconds(10)).output().trim();
    return List.of("--user", uid + ":" + gid);
  }

  private static Cmd.Result docker(Duration timeout, String... args) throws InterruptedException {
    List<String> command = new ArrayList<>();
    command.add("docker");
    command.addAll(List.of(args));
    return Cmd.run(command, timeout);
  }

  private static String require(Cmd.Result result, String what) {
    if (!result.ok()) {
      throw new IllegalStateException(
          what + " failed (" + result.exitCode() + "): " + result.output());
    }
    return result.output().trim();
  }

  @Override
  public void startDependencies() throws InterruptedException {
    require(docker(Duration.ofMinutes(1), "network", "create", network), "docker network create");
    networkCreated = true;
    List<String> run =
        new ArrayList<>(List.of("run", "-d", "--name", extName, "--network", network));
    run.addAll(List.of("--network-alias", "ext-stub", "--memory", "512m"));
    run.addAll(linuxUser());
    run.addAll(List.of("-v", setup.binDir() + ":/opt/bench:ro", setup.image()));
    run.addAll(
        List.of(
            "java",
            "-Dsun.net.httpserver.nodelay=true",
            "-jar",
            "/opt/bench/" + setup.extJar().getFileName(),
            "--port",
            "18080",
            "--bind",
            "0.0.0.0",
            "--threads",
            "64"));
    require(docker(Duration.ofMinutes(5), run.toArray(String[]::new)), "docker run ext-stub");
    long deadline = System.nanoTime() + Duration.ofMinutes(1).toNanos();
    while (!docker(
            Duration.ofSeconds(20),
            "exec",
            extName,
            "curl",
            "-sf",
            "http://127.0.0.1:18080/_stub/health")
        .ok()) {
      if (System.nanoTime() > deadline) {
        throw new IllegalStateException("ext-stub container not ready");
      }
      Thread.sleep(500);
    }
  }

  @Override
  public App startApp(String variant, Path runDir) throws IOException, InterruptedException {
    apps++;
    String name = prefix + "-app-" + apps;
    int hostPort = Net.freePort();
    Files.createDirectories(runDir.resolve("agent-logs"));
    List<String> run = new ArrayList<>(List.of("run", "-d", "--name", name, "--network", network));
    run.addAll(List.of("--network-alias", "app", "--cpus", Integer.toString(setup.cpus())));
    run.addAll(List.of("--memory", setup.memory(), "--memory-swap", setup.memory()));
    run.addAll(List.of("--log-opt", "max-size=20m", "--log-opt", "max-file=2"));
    run.addAll(List.of("-p", "127.0.0.1:" + hostPort + ":8080"));
    run.addAll(linuxUser());
    run.addAll(List.of("-v", setup.binDir() + ":/opt/bench:ro", "-v", runDir + ":/run-out"));
    run.addAll(List.of("-e", "SERVER_PORT=8080", "-e", "SAMPLE_EXT_URL=http://ext-stub:18080"));
    setup.appEnv().forEach((k, v) -> run.addAll(List.of("-e", k + "=" + v)));
    run.add(setup.image());
    List<String> java = new ArrayList<>();
    java.add("java");
    java.addAll(setup.jvmOptions());
    java.add("-Xlog:gc:file=/run-out/gc.log");
    java.add("-XX:NativeMemoryTracking=summary");
    java.addAll(
        Platform.variantOptions(
            variant,
            setup,
            "/opt/bench/" + setup.agentJar().getFileName(),
            setup.otelJar() == null ? null : "/opt/bench/" + setup.otelJar().getFileName(),
            "/run-out/agent-logs"));
    java.addAll(List.of("-jar", "/opt/bench/" + setup.appJar().getFileName()));
    run.addAll(java);
    Files.writeString(runDir.resolve("command.txt"), "docker " + String.join("\n", run) + "\n");
    long spawn = System.nanoTime();
    require(docker(Duration.ofMinutes(5), run.toArray(String[]::new)), "docker run app");
    ContainerApp app = new ContainerApp(name, runDir);
    long deadline = spawn + READY_TIMEOUT.toNanos();
    // Docker's port proxy accepts connections before the app listens: only readiness is measured.
    while (!Net.answers200("http://127.0.0.1:" + hostPort + "/actuator/health")) {
      if (System.nanoTime() > deadline
          || !docker(Duration.ofSeconds(20), "inspect", "-f", "{{.State.Running}}", name)
              .output()
              .trim()
              .equals("true")) {
        app.stop(runDir, true);
        throw new IllegalStateException(
            "app container not ready, see " + runDir.resolve("app.log"));
      }
      Thread.sleep(100);
    }
    app.toReady = (System.nanoTime() - spawn) / 1_000_000;
    return app;
  }

  @Override
  public K6Run runK6(
      App app, Path runDir, String label, Map<String, String> env, Duration timeout, boolean raw)
      throws InterruptedException {
    ContainerApp target = (ContainerApp) app;
    k6Runs++;
    String k6Name = prefix + "-k6-" + k6Runs;
    String sidecar = k6Name + "-cg";
    List<String> create =
        new ArrayList<>(List.of("create", "--name", k6Name, "--network", network));
    create.addAll(List.of("--memory", "1g"));
    create.addAll(linuxUser());
    create.addAll(
        List.of("-v", setup.benchJs().getParent() + ":/scripts:ro", "-v", runDir + ":/out"));
    create.addAll(List.of("-e", "K6_NO_USAGE_REPORT=true", "-e", "TARGET_URL=http://app:8080"));
    create.addAll(List.of("-e", "SUMMARY_PATH=/out/" + label + ".json"));
    env.forEach((k, v) -> create.addAll(List.of("-e", k + "=" + v)));
    create.addAll(List.of(setup.k6Image(), "run", "--quiet", "--no-color", "--no-usage-report"));
    if (raw) {
      create.addAll(List.of("--out", "json=/out/" + K6Raw.FILE_NAME));
    }
    create.add("/scripts/" + setup.benchJs().getFileName());
    String k6Id =
        require(docker(Duration.ofMinutes(5), create.toArray(String[]::new)), "docker create k6");
    String appId =
        require(
            docker(Duration.ofSeconds(30), "inspect", "-f", "{{.Id}}", target.name),
            "docker inspect app");
    require(
        docker(
            Duration.ofMinutes(2),
            "run",
            "-d",
            "--name",
            sidecar,
            "--cgroupns=host",
            "-v",
            "/sys/fs/cgroup:/hostcg:ro",
            "-e",
            "APP_ID=" + appId,
            "-e",
            "K6_ID=" + k6Id,
            setup.sidecarImage(),
            "sh",
            "-c",
            SIDECAR_SCRIPT),
        "docker run cgroup sampler");
    long start = System.nanoTime();
    Cmd.Result result =
        Cmd.run(
            List.of("docker", "start", "-a", k6Name),
            timeout,
            Map.of(),
            runDir.resolve(label + ".log"));
    double wall = (System.nanoTime() - start) / 1e9;
    String samples = docker(Duration.ofMinutes(1), "logs", sidecar).output();
    docker(Duration.ofMinutes(1), "rm", "-f", sidecar);
    docker(Duration.ofMinutes(1), "rm", "-f", k6Name);
    try {
      Files.writeString(runDir.resolve(label + "-cgroup-samples.txt"), samples);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    Sidecar parsed = parseSidecar(samples);
    if (result.exitCode() == -1) {
      throw new IllegalStateException("k6 did not finish within " + timeout);
    }
    return new K6Run(result.exitCode(), wall, parsed.k6CpuSeconds, parsed.app, parsed.machine);
  }

  /** Side container samples: app timeline, k6 CPU seconds and the Docker machine's CPU %. */
  record Sidecar(List<List<Double>> app, double k6CpuSeconds, List<List<Double>> machine) {}

  static Sidecar parseSidecar(String text) {
    List<List<Double>> app = new ArrayList<>();
    List<List<Double>> machine = new ArrayList<>();
    double t0 = Double.NaN;
    double app0 = Double.NaN;
    double k6First = Double.NaN;
    double k6Last = Double.NaN;
    long[] previous = null;
    for (String line : LINES.split(text, -1)) {
      String[] f = WHITESPACE.split(line.trim(), -1);
      // S <uptime> <app usec|-> <k6 usec|-> cpu <user> <nice> <system> <idle> <iowait> ...
      if (f.length < 9 || !f[0].equals("S") || !f[4].equals("cpu")) {
        continue;
      }
      double uptime = Double.parseDouble(f[1]);
      if (Double.isNaN(t0)) {
        t0 = uptime;
      }
      if (!f[2].equals("-")) {
        double usage = Long.parseLong(f[2]) / 1e6;
        if (Double.isNaN(app0)) {
          app0 = usage;
        }
        app.add(List.of(uptime - t0, usage - app0));
      }
      if (!f[3].equals("-")) {
        double usage = Long.parseLong(f[3]) / 1e6;
        if (Double.isNaN(k6First)) {
          k6First = usage;
        }
        k6Last = usage;
      }
      long[] ticks = new long[f.length - 5];
      for (int i = 5; i < f.length; i++) {
        ticks[i - 5] = Long.parseLong(f[i]);
      }
      if (previous != null) {
        long total = 0;
        long idle = 0;
        for (int i = 0; i < ticks.length; i++) {
          long delta = ticks[i] - previous[i];
          // guest and guest_nice (index 8, 9) are already part of user and nice.
          if (i < 8) {
            total += delta;
          }
          if (i == 3 || i == 4) {
            idle += delta;
          }
        }
        machine.add(List.of(uptime - t0, total <= 0 ? Double.NaN : 100.0 * (total - idle) / total));
      }
      previous = ticks;
    }
    // k6 CPU counted from its first sample: k6 setup before the first sample is a few ms.
    double k6 = Double.isNaN(k6First) ? Double.NaN : k6Last;
    return new Sidecar(app, k6, machine);
  }

  @Override
  public Map<String, Object> environment() throws InterruptedException {
    Map<String, Object> env = Environment.host(null);
    env.put("k6", Environment.k6Version("docker", setup.k6Image()));
    env.put(
        "dockerServer",
        docker(
                Duration.ofSeconds(30),
                "version",
                "--format",
                "{{.Server.Version}} {{.Server.Os}}/{{.Server.Arch}}")
            .output()
            .trim());
    env.put(
        "dockerInfo",
        docker(
                Duration.ofSeconds(30),
                "info",
                "--format",
                "{{.NCPU}} CPUs, {{.MemTotal}} bytes, cgroup v{{.CgroupVersion}}, kernel {{.KernelVersion}}")
            .output()
            .trim());
    env.put(
        "containerJdk",
        docker(Duration.ofMinutes(2), "run", "--rm", setup.image(), "java", "-version")
            .output()
            .trim());
    env.put("image", setup.image());
    env.put("sidecarImage", setup.sidecarImage());
    env.put(
        "appContainer", "--cpus " + setup.cpus() + " --memory " + setup.memory() + " (swap off)");
    return env;
  }

  @Override
  public void close() {
    try {
      docker(Duration.ofMinutes(1), "rm", "-f", extName);
      if (networkCreated) {
        docker(Duration.ofMinutes(1), "network", "rm", network);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** An app container. */
  static final class ContainerApp implements App {
    private final String name;
    private final Path runDir;
    private long toReady = -1;

    ContainerApp(String name, Path runDir) {
      this.name = name;
      this.runDir = runDir;
    }

    @Override
    public long spawnToPortMillis() {
      return -1;
    }

    @Override
    public long spawnToReadyMillis() {
      return toReady;
    }

    @Override
    public long cpuNanos() {
      try {
        Cmd.Result r =
            docker(Duration.ofSeconds(30), "exec", name, "cat", "/sys/fs/cgroup/cpu.stat");
        long usec = r.ok() ? ProcFs.cgroupUsageMicros(r.output()) : -1;
        return usec < 0 ? -1 : usec * 1000;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return -1;
      }
    }

    @Override
    public String cpuSource() {
      return "cgroup v2 cpu.stat usage_usec of the app container";
    }

    @Override
    public long osThreads() {
      try {
        Cmd.Result r = docker(Duration.ofSeconds(30), "exec", name, "cat", "/proc/1/status");
        return r.ok() ? ProcFs.statusThreads(r.output()) : -1;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return -1;
      }
    }

    @Override
    public Cmd.Result jcmd(String... args) throws InterruptedException {
      List<String> command = new ArrayList<>(List.of("exec", name, "jcmd", "1"));
      command.addAll(List.of(args));
      return docker(Duration.ofMinutes(2), command.toArray(String[]::new));
    }

    @Override
    public String agentLog() {
      return FileTools.readLenient(runDir.resolve("agent-logs").resolve("argos-agent.log"));
    }

    @Override
    public void stop(Path dir, boolean keepFullLog) throws InterruptedException {
      Cmd.run(List.of("docker", "stop", "-t", "10", name), Duration.ofMinutes(1));
      Cmd.run(
          List.of("docker", "logs", name), Duration.ofMinutes(5), Map.of(), dir.resolve("app.log"));
      Cmd.run(List.of("docker", "rm", "-f", name), Duration.ofMinutes(1));
      try {
        FileTools.excerpt(
            dir.resolve("app.log"), dir.resolve("app-log-excerpt.txt"), 300, 200, keepFullLog);
      } catch (IOException e) {
        throw new IllegalStateException("cannot read the app log in " + dir, e);
      }
    }
  }
}
