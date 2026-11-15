/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.transportpoc.bench;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * One measured JVM: the host application on one JDK with no agent, the base agent ({@code
 * test.exporter=noop}) or a comparison jar (the default grpc exporter to the fake collector), and
 * what came out of it.
 */
final class HostRun {
  /**
   * Text of the one line the JVM itself prints (prefixed "OpenJDK 64-Bit Server VM warning: ") when
   * an agent appends to the bootstrap search path and CDS is on (10 §14 "CDS", JDK 17+).
   */
  static final List<String> ALLOWED_JVM_LINES =
      List.of(
          "Sharing is only supported for boot loader classes because bootstrap classpath has been appended");

  /**
   * What to run. With {@code untilBatches > 0} the host application stops as soon as the fake
   * collector has that many batches of the run ({@code durationMillis} is then the upper bound), so
   * that a slow, shared machine does not turn a functional check into a timing check.
   */
  record Spec(
      String scenario,
      String jdk,
      String agent,
      List<String> jvmOptions,
      long durationMillis,
      boolean c27,
      int untilBatches) {
    Spec(
        String scenario,
        String jdk,
        String agent,
        List<String> jvmOptions,
        long durationMillis,
        boolean c27) {
      this(scenario, jdk, agent, jvmOptions, durationMillis, c27, 0);
    }

    String label() {
      return agent + "@" + jdk + (jvmOptions.isEmpty() ? "" : " " + String.join(" ", jvmOptions));
    }
  }

  /** What happened. */
  record Result(
      Spec spec,
      int repetition,
      int instance,
      Path dir,
      int exitCode,
      boolean timedOut,
      double cpuBefore,
      double cpuDuring,
      Properties host,
      List<String> stdout,
      List<String> stderr,
      List<String> agentLog,
      List<CollectorProcess.Event> events,
      List<String> tmpEntries,
      List<String> tmpCreated) {

    long hostLong(String key) {
      String value = host.getProperty(key);
      return value == null ? -1 : Long.parseLong(value);
    }

    /** Lines of stderr that are neither allowed JVM lines nor empty. */
    List<String> foreignStderr() {
      return stderr.stream()
          .filter(l -> !l.isBlank() && ALLOWED_JVM_LINES.stream().noneMatch(l::contains))
          .toList();
    }

    List<String> unsafeWarnings() {
      return stderr.stream().filter(l -> l.contains("sun.misc.Unsafe")).toList();
    }

    List<String> agentLines(String level) {
      return agentLog.stream().filter(l -> l.contains(" " + level + " ")).toList();
    }

    String agentLine(String marker) {
      return agentLog.stream().filter(l -> l.contains(marker)).findFirst().orElse(null);
    }

    /** First batch arrival at the collector, ms after JVM start (-1: none). */
    long firstBatchMillis() {
      long start = hostLong("jvm.start.ms");
      return events.isEmpty() || start < 0 ? -1 : events.get(0).ms() - start;
    }

    String tls() {
      return events.isEmpty() ? "-" : events.get(0).tls() + " " + events.get(0).cipher();
    }
  }

  private HostRun() {}

  static Result run(
      Spec spec,
      int repetition,
      int instance,
      Path dir,
      BenchOptions options,
      CollectorProcess collector,
      CpuLoad cpu)
      throws Exception {
    // Short temporary directory (<out>/t/<n>): AF_UNIX socket paths must stay below 108 characters,
    // otherwise Windows JDK 16+ selectors fall back to TCP loopback and their files never appear.
    Path tmpDir = dir.getParent().getParent().resolve("t").resolve(Integer.toString(instance));
    Files.createDirectories(tmpDir);
    Files.createDirectories(dir.resolve("logs"));
    Path home = options.jdks().get(spec.jdk());
    List<String> command = new ArrayList<>();
    command.add(Jdks.tool(home, "java").toString());
    command.add("-XX:-UsePerfData");
    if (spec.c27()) {
      command.addAll(List.of("-XX:+UnlockDiagnosticVMOptions", "-XX:+BytecodeVerificationLocal"));
    }
    command.add("-Djava.io.tmpdir=" + tmpDir);
    if (!spec.agent().equals("none")) {
      Path jar =
          spec.agent().equals("base") ? options.baseAgent() : options.variants().get(spec.agent());
      command.add("-javaagent:" + jar);
      command.add("-Dargos.log.dir=" + dir.resolve("logs"));
      command.add("-Dargos.app.name=transport-poc");
      command.add("-Dargos.app.env=bench");
      command.add("-Dargos.app.instance=" + instance);
      if (spec.agent().equals("base")) {
        // P-3 baseline: encode, then drop (ADR-0009 C-88 moved the PoC key exporter to the
        // development-only test.exporter; noop, not drop, keeps the encoding cost comparable).
        command.add("-Dargos.test.exporter=noop");
      } else {
        // grpc is the exporter of a configuration without test keys (C-88).
        int port = spec.agent().startsWith("http1") ? collector.httpPort : collector.grpcPort;
        command.add("-Dargos.server.address=localhost:" + port);
        command.add("-Dargos.server.tls=true");
        command.add("-Dargos.server.tls.ca-file=" + collector.caFile);
      }
    }
    command.addAll(spec.jvmOptions());
    command.addAll(
        List.of(
            "-cp",
            options.hostClasses(),
            "example.transportpoc.HostApp",
            dir.resolve("host.properties").toString(),
            Long.toString(spec.durationMillis()),
            dir.resolve("stop").toString()));
    Files.write(dir.resolve("command.txt"), command, StandardCharsets.UTF_8);

    ProcessBuilder builder =
        new ProcessBuilder(command)
            .directory(dir.toFile())
            .redirectOutput(dir.resolve("stdout.txt").toFile())
            .redirectError(dir.resolve("stderr.txt").toFile());
    Map<String, String> env = builder.environment();
    env.keySet()
        .removeIf(
            k ->
                k.startsWith("ARGOS_")
                    || k.endsWith("JAVA_OPTIONS")
                    || k.equals("JAVA_TOOL_OPTIONS"));
    for (String name : List.of("TMP", "TEMP", "TMPDIR")) {
      env.put(name, tmpDir.toString());
    }
    double before = cpu.latest();
    long started = System.currentTimeMillis();
    boolean finished;
    Process process;
    List<String> created;
    TempWatch watch = TempWatch.start(tmpDir);
    try {
      process = builder.start();
      if (spec.untilBatches() > 0) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(spec.durationMillis());
        while (process.isAlive() && System.nanoTime() - deadline < 0) {
          if (collector.events(instance).size() >= spec.untilBatches()) {
            Files.writeString(dir.resolve("stop"), "stop" + System.lineSeparator());
            break;
          }
          Thread.sleep(200);
        }
      }
      finished = process.waitFor(spec.durationMillis() + 90_000, TimeUnit.MILLISECONDS);
      if (!finished) {
        process.destroyForcibly();
        process.waitFor(30, TimeUnit.SECONDS);
      }
    } finally {
      watch.close();
    }
    created = watch.created();
    long ended = System.currentTimeMillis();
    Properties host = new Properties();
    Path hostFile = dir.resolve("host.properties");
    if (Files.exists(hostFile)) {
      try (InputStream in = Files.newInputStream(hostFile)) {
        host.load(in);
      }
    }
    // The fake collector writes asynchronously; give the last batch a moment.
    Thread.sleep(200);
    List<CollectorProcess.Event> events = collector.events(instance);
    Files.write(
        dir.resolve("collector-events.txt"),
        events.stream().map(Object::toString).toList(),
        StandardCharsets.UTF_8);
    List<String> tmp;
    try (Stream<Path> entries = Files.list(tmpDir)) {
      tmp = entries.map(p -> p.getFileName().toString()).sorted().toList();
    }
    return new Result(
        spec,
        repetition,
        instance,
        dir,
        finished ? process.exitValue() : -1,
        !finished,
        before,
        cpu.mean(started, ended),
        host,
        lines(dir.resolve("stdout.txt")),
        lines(dir.resolve("stderr.txt")),
        lines(dir.resolve("logs").resolve("argos-agent.log")),
        events,
        tmp,
        created);
  }

  static List<String> lines(Path file) throws IOException {
    if (!Files.exists(file)) {
      return List.of();
    }
    // Decoding errors (JVM messages in the console code page) must not fail the bench.
    String text = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
    return text.isEmpty()
        ? List.of()
        : Arrays.asList(text.split("\\R", -1)).stream().filter(l -> !l.isEmpty()).toList();
  }
}
