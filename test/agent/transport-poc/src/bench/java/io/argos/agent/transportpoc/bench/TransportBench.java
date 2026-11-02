/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.transportpoc.bench;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * WO-02 P-3 runner (02 §11 P-3, 10 §10.3, 11 §15 #2-#4): launches the host application with no
 * agent, the base agent and every comparison jar on several JDKs against the fake collector, and
 * writes {@code summary.md} plus raw files (one directory per JVM run).
 *
 * <ul>
 *   <li>{@code --mode=smoke}: every jar on JDK 8 and 21 with the C-27 flags must deliver 3 batches
 *       within 60 s, print nothing, create no temporary file, log no ERROR and create no
 *       java.util.logging logger; exit code 1 otherwise. The one documented exception is the known
 *       finding of {@link #knownAfUnixSelectorFile}, reported as such in smoke.md.
 *   <li>{@code --mode=bench}: the measurement matrix (sizes, metaspace, startup, direct memory,
 *       threads on JDK 21 and 8 with repetitions; JDK 11/17 function; Java 8u242/8u252 TLS; JDK 25
 *       warn/deny; classes loaded at run time that reference Unsafe; C-23 property isolation;
 *       unreachable collector).
 * </ul>
 */
public final class TransportBench {
  static final long GATE_JAR_BYTES = 15_000_000L;
  static final long GATE_STARTUP_MILLIS = 2_000L;
  static final int GATE_THREADS = 6;
  static final long GATE_DIRECT_BYTES = 8L * 1024 * 1024;
  static final String KNOWN_AF_UNIX =
      "known finding ADR-0005 P3 (Windows JDK 16+ NIO selector AF_UNIX wake-up socket, deleted"
          + " at once; T-BLD-006 decision pending)";

  private final BenchOptions options;
  private final Path dir;
  private final CollectorProcess collector;
  private final CpuLoad cpu;
  private final List<HostRun.Result> results = new ArrayList<>();
  private int instance;

  private TransportBench(BenchOptions options, Path dir, CollectorProcess collector, CpuLoad cpu) {
    this.options = options;
    this.dir = dir;
    this.collector = collector;
    this.cpu = cpu;
  }

  public static void main(String[] args) throws Exception {
    BenchOptions options = BenchOptions.parse(args);
    Path dir =
        options.bench()
            ? options
                .out()
                .resolve(
                    DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                        .format(LocalDateTime.now(ZoneId.systemDefault())))
            : options.out();
    if (!options.bench()) {
      deleteTree(dir);
    }
    Files.createDirectories(dir.resolve("raw"));
    int exit;
    try (CpuLoad cpu = new CpuLoad();
        CollectorProcess collector =
            CollectorProcess.start(
                dir.resolve("collector"), options.jdks().get("21"), options.collectorClasspath())) {
      TransportBench bench = new TransportBench(options, dir, collector, cpu);
      exit = options.bench() ? bench.bench() : bench.smoke();
    }
    System.out.println(
        "P-3 "
            + options.mode()
            + " results: "
            + dir.resolve(options.bench() ? "summary.md" : "smoke.md"));
    System.exit(exit);
  }

  // --- smoke -----------------------------------------------------------------------------------

  private int smoke() throws Exception {
    List<String> failures = new ArrayList<>();
    List<String> knownFindings = new ArrayList<>();
    List<String> lines = new ArrayList<>();
    lines.add("# P-3 smoke (" + now() + ")");
    lines.add("");
    lines.add(
        "| run | exit | batches | first batch ms | TLS | stdout | stderr | tmp created | agent ERROR | result |");
    lines.add("|---|---|---|---|---|---|---|---|---|---|");
    for (String jdk : List.of("8", "21")) {
      if (!options.jdks().containsKey(jdk)) {
        continue;
      }
      List<String> agents = new ArrayList<>();
      agents.add("base");
      agents.addAll(options.variants().keySet());
      for (String agent : agents) {
        // Base: fixed 7 s. Comparison jars: until 3 batches arrived, at most 60 s.
        HostRun.Spec spec =
            agent.equals("base")
                ? new HostRun.Spec("smoke", jdk, agent, List.of(), 7_000, true)
                : new HostRun.Spec("smoke", jdk, agent, List.of(), 60_000, true, 3);
        HostRun.Result r = run(spec, 1);
        List<String> problems = new ArrayList<>();
        if (r.timedOut() || r.exitCode() != 0) {
          problems.add("exit " + r.exitCode() + (r.timedOut() ? " (timed out)" : ""));
        }
        if (r.host().isEmpty()) {
          problems.add("host result missing");
        }
        if (!agent.equals("base")) {
          if (r.events().size() < 3) {
            problems.add(r.events().size() + " batches (expected >= 3)");
          }
          if (r.agentLine("transport-poc first-ack") == null) {
            problems.add("no first-ack in the agent log");
          }
        }
        if (!r.stdout().isEmpty()) {
          problems.add("stdout: " + r.stdout().get(0));
        }
        if (!r.foreignStderr().isEmpty()) {
          problems.add("stderr: " + r.foreignStderr().get(0));
        }
        List<String> known = new ArrayList<>();
        if (!r.tmpEntries().isEmpty() || !r.tmpCreated().isEmpty()) {
          String problem =
              "temp files (T-BLD-006): left "
                  + r.tmpEntries()
                  + ", created during the run "
                  + r.tmpCreated();
          if (knownAfUnixSelectorFile(agent, jdk, r)) {
            known.add(problem + " = " + KNOWN_AF_UNIX);
          } else {
            problems.add(problem);
          }
        }
        if (!r.agentLines("ERROR").isEmpty()) {
          problems.add("agent ERROR: " + r.agentLines("ERROR").get(0));
        }
        // CLAUDE.md: no java.util.logging in the agent (the shaded libraries use JulLogger).
        if (r.hostLong("jul.agent.loggers") > 0) {
          problems.add(
              "JUL loggers created by the agent: "
                  + r.host().getProperty("jul.agent.logger.names"));
        }
        String verdict;
        if (!problems.isEmpty()) {
          verdict = "FAIL: " + String.join("; ", problems);
          failures.add(r.spec().label() + ": " + String.join("; ", problems));
        } else if (!known.isEmpty()) {
          verdict = "KNOWN FINDING: " + String.join("; ", known);
          knownFindings.add(r.spec().label() + ": " + String.join("; ", known));
        } else {
          verdict = "PASS";
        }
        lines.add(
            "| %s | %d | %d | %d | %s | %d | %d | %d | %d | %s |"
                .formatted(
                    r.spec().label(),
                    r.exitCode(),
                    r.events().size(),
                    r.firstBatchMillis(),
                    r.tls(),
                    r.stdout().size(),
                    r.foreignStderr().size(),
                    r.tmpCreated().size(),
                    r.agentLines("ERROR").size(),
                    verdict.replace("|", "/")));
        System.out.println(r.spec().label() + ": " + verdict);
      }
    }
    lines.add("");
    lines.add(
        failures.isEmpty()
            ? "No unexpected result (" + knownFindings.size() + " known finding(s))."
            : failures.size() + " run(s) failed:");
    failures.forEach(f -> lines.add("- " + f));
    if (!knownFindings.isEmpty()) {
      lines.add("");
      lines.add(
          "Known findings (not counted as failures; pass --known-af-unix-temp= empty, Gradle"
              + " -Ptransport.poc.strict=true, to count them):");
      knownFindings.forEach(f -> lines.add("- " + f));
    }
    Files.write(dir.resolve("smoke.md"), lines, StandardCharsets.UTF_8);
    return failures.isEmpty() ? 0 : 1;
  }

  /**
   * ADR-0005 transport (P3, PM decision pending): on Windows, JDK 16+ NIO selectors create their
   * wake-up pipe as an AF_UNIX socket and briefly put a {@code socket_<n>} file into the temporary
   * directory (deleted at once). The grpc-netty event loop owns one selector, so every grpc-netty
   * run on Windows with JDK 16+ creates such files. Only that exact pattern counts as the known
   * finding, and only for the variants passed with {@code --known-af-unix-temp}: a file that stays,
   * any other name, another JDK, another OS or another variant is still a failure.
   */
  private boolean knownAfUnixSelectorFile(String agent, String jdk, HostRun.Result r) {
    return options.knownAfUnixTemp().contains(agent)
        && System.getProperty("os.name", "").startsWith("Windows")
        && Jdks.major(jdk) >= 16
        && r.tmpEntries().isEmpty()
        && !r.tmpCreated().isEmpty()
        && r.tmpCreated().stream().allMatch(name -> name.equals("socket_#"));
  }

  // --- bench -----------------------------------------------------------------------------------

  private int bench() throws Exception {
    Map<String, JarFacts> jars = new LinkedHashMap<>();
    jars.put(
        "base",
        JarFacts.of(
            options.baseAgent(),
            options.out().resolve("..").resolve("base").resolve("reports").normalize()));
    for (Map.Entry<String, Path> variant : options.variants().entrySet()) {
      jars.put(variant.getKey(), JarFacts.of(variant.getValue()));
    }
    List<String> agents = new ArrayList<>();
    agents.add("none");
    agents.add("base");
    agents.addAll(options.variants().keySet());

    // Resources and startup: JDK 21 and 8, alternating agents within each repetition.
    for (String jdk : List.of("21", "8")) {
      if (!options.jdks().containsKey(jdk)) {
        continue;
      }
      for (int rep = 1; rep <= options.repetitions(); rep++) {
        for (String agent : agents) {
          run(new HostRun.Spec("resources", jdk, agent, List.of(), 10_000, false), rep);
        }
      }
    }
    // Function on the remaining LTS JDKs and the Java 8 ALPN boundary (11 §15 #4).
    for (String jdk : List.of("11", "17", "8u242", "8u252")) {
      if (!options.jdks().containsKey(jdk)) {
        continue;
      }
      for (String agent : agents.subList(1, agents.size())) {
        run(new HostRun.Spec("function", jdk, agent, List.of(), 8_000, false), 1);
      }
    }
    // JDK 25: default (warn) and deny (F-A35, 10 §14 "Unsafe").
    if (options.jdks().containsKey("25")) {
      for (List<String> flags :
          List.of(List.<String>of(), List.of("--sun-misc-unsafe-memory-access=deny"))) {
        for (String agent : agents.subList(1, agents.size())) {
          run(new HostRun.Spec("jdk25", "25", agent, flags, 8_000, false), 1);
        }
      }
    }
    // Classes loaded at run time (which Unsafe-referencing classes are actually used).
    for (String jdk : List.of("21", "25", "8")) {
      if (!options.jdks().containsKey(jdk)) {
        continue;
      }
      List<String> flags =
          Jdks.major(jdk) >= 9
              ? List.of("-Xlog:class+load=info:file=classload.txt")
              : List.of("-verbose:class");
      // On JDK 21 also without agent and with the base agent: what the transports add (JUL, ...).
      List<String> loadAgents =
          new ArrayList<>(jdk.equals("21") ? agents : agents.subList(2, agents.size()));
      for (String agent : loadAgents) {
        run(new HostRun.Spec("classload", jdk, agent, flags, 8_000, false), 1);
      }
    }
    // C-23: the application's own io.netty.* settings must not reach the agent's Netty.
    for (String agent : options.variants().keySet()) {
      if (agent.startsWith("grpc-netty")) {
        run(
            new HostRun.Spec(
                "c23",
                "21",
                agent,
                List.of(
                    "-Dio.netty.noUnsafe=false",
                    "-Dio.netty.allocator.type=pooled",
                    "-Dio.netty.maxDirectMemory=0"),
                6_000,
                false),
            1);
      }
    }
    // Collector unreachable: retries stay in the agent log, nothing on stdout/stderr.
    for (String agent : options.variants().keySet()) {
      run(
          new HostRun.Spec(
              "unreachable",
              "21",
              agent,
              List.of("-Dargos.server.address=localhost:1"),
              6_000,
              false),
          1);
    }

    Report report = new Report(options, jars, results, cpu);
    Files.write(dir.resolve("summary.md"), report.markdown(), StandardCharsets.UTF_8);
    Files.write(dir.resolve("runs.tsv"), report.runsTsv(), StandardCharsets.UTF_8);
    return 0;
  }

  private HostRun.Result run(HostRun.Spec spec, int repetition) throws Exception {
    int id = ++instance;
    String name =
        "%04d-%s-%s-%s-r%d".formatted(id, spec.scenario(), spec.agent(), spec.jdk(), repetition);
    if (spec.jvmOptions().stream().anyMatch(o -> o.contains("deny"))) {
      name += "-deny";
    }
    HostRun.Result result =
        HostRun.run(
            spec, repetition, id, dir.resolve("raw").resolve(name), options, collector, cpu);
    results.add(result);
    System.out.printf(
        "%s exit=%d batches=%d main=%dms metaspace=%d threads=%s cpu=%.0f%%%n",
        name,
        result.exitCode(),
        result.events().size(),
        result.hostLong("main.uptime.ms"),
        result.hostLong("metaspace.used"),
        result.host().getProperty("threads.count"),
        result.cpuBefore());
    return result;
  }

  static String now() {
    return LocalDateTime.now(ZoneId.systemDefault()).withNano(0).toString();
  }

  static String machine() {
    com.sun.management.OperatingSystemMXBean os =
        (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    return System.getProperty("os.name")
        + " "
        + System.getProperty("os.version")
        + ", "
        + os.getAvailableProcessors()
        + " logical CPUs, "
        + (os.getTotalMemorySize() / (1024 * 1024))
        + " MiB RAM";
  }

  private static void deleteTree(Path root) throws Exception {
    if (!Files.exists(root)) {
      return;
    }
    try (Stream<Path> walk = Files.walk(root)) {
      for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
        Files.delete(p);
      }
    }
  }
}
