/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.launcher;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Starts a WO-01 sample app on a given JDK, with or without the agent (WO-02 minimal form of 18
 * §5.2 AppLauncher; WO-05 extends it with the fake collector, containers and WAR deployments).
 *
 * <pre>
 * try (RunningApp app =
 *     AppLauncher.of(SampleApp.fromArtifact(jar), javaExecutable, 21)
 *         .withAgent(agentJar)
 *         .workDir(dir)
 *         .start()) {
 *   app.get("/api/orders/1");
 *   app.transactions().await(JsonlTransactions.service("GET /api/orders/{id}"), 1, timeout);
 * }
 * </pre>
 *
 * <p>Every run gets its own work directory: {@code tmp/} (the JVM's {@code java.io.tmpdir} and the
 * {@code TMP}/{@code TEMP}/{@code TMPDIR} of the process), {@code agent/argos-agent.jar} (a copy,
 * so that nothing can appear next to the jar unseen), {@code logs/} ({@code argos.log.dir}), {@code
 * out/tx.jsonl} (JSONL exporter), {@code out/transformed.txt} ({@code argos.debug.transform-list}),
 * {@code stdout.log} and {@code stderr.log} (separate, T-AG-060). JVM flags: {@code
 * -XX:-UsePerfData} and, unless turned off, C-27 {@code -XX:+UnlockDiagnosticVMOptions
 * -XX:+BytecodeVerificationLocal}. The app binds {@code 127.0.0.1} on a free port; Spring Boot's
 * banner is off so that stdout holds log lines only. {@code ARGOS_*} and {@code JAVA_TOOL_OPTIONS}
 * of the harness environment are not inherited.
 *
 * <p>Startup (ADR-0004 C-49): {@link RunningApp#spawnToPortMillis()} is process creation to port
 * binding (jar apps), {@link RunningApp#spawnToReadyMillis()} to the first 200 of the readiness
 * path.
 */
public final class AppLauncher {
  /** C-27 (19 §11, 18 §5.10): verification of transformed JDK classes too. */
  public static final List<String> VERIFICATION_FLAGS =
      List.of("-XX:+UnlockDiagnosticVMOptions", "-XX:+BytecodeVerificationLocal");

  private final SampleApp app;
  private final Path java;
  private final int jdk;
  private Path agentJar;
  private final Map<String, String> agentConfig = new LinkedHashMap<>();
  private final List<String> jvmOptions = new ArrayList<>();
  private final Map<String, String> environment = new LinkedHashMap<>();
  private Path workDir;
  private Duration readyTimeout = Duration.ofMinutes(3);
  private boolean verification = true;

  private AppLauncher(SampleApp app, Path java, int jdk) {
    this.app = app;
    this.java = java;
    this.jdk = jdk;
  }

  /**
   * A launcher of {@code app} on the JDK whose {@code java} executable is {@code java} (feature
   * version {@code jdk}, used for version-specific flags).
   */
  public static AppLauncher of(SampleApp app, Path java, int jdk) {
    return new AppLauncher(app, java, jdk);
  }

  /** Attaches the agent jar with the JSONL exporter, log directory and transformed-class list. */
  public AppLauncher withAgent(Path jar) {
    this.agentJar = jar;
    return this;
  }

  /** The off baseline (no {@code -javaagent}). */
  public AppLauncher withoutAgent() {
    this.agentJar = null;
    return this;
  }

  /** An agent setting: {@code -Dargos.<key>=<value>}, after (and so above) the defaults. */
  public AppLauncher agentConfig(String key, String value) {
    agentConfig.put(key, value);
    return this;
  }

  public AppLauncher jvmOptions(String... options) {
    jvmOptions.addAll(Arrays.asList(options));
    return this;
  }

  public AppLauncher env(String name, String value) {
    environment.put(name, value);
    return this;
  }

  /** The run's directory (created; must not hold a previous run). */
  public AppLauncher workDir(Path dir) {
    this.workDir = dir;
    return this;
  }

  /** Startup timeout (default 180 s; shop-heavy needs 120 s or more, WO-01 report D-1). */
  public AppLauncher readyTimeout(Duration timeout) {
    this.readyTimeout = timeout;
    return this;
  }

  /** Turns the C-27 verification flags off (e.g. to see the JVM's CDS notice). */
  public AppLauncher bytecodeVerification(boolean on) {
    this.verification = on;
    return this;
  }

  /** The command line {@link #start()} runs (for reports). */
  public List<String> command(Path dir) {
    List<String> command = new ArrayList<>();
    command.add(java.toString());
    command.add("-XX:-UsePerfData");
    if (verification) {
      command.addAll(VERIFICATION_FLAGS);
    }
    command.addAll(jvmOptions);
    command.add("-Djava.io.tmpdir=" + dir.resolve("tmp"));
    if (agentJar != null) {
      command.add("-javaagent:" + dir.resolve("agent").resolve("argos-agent.jar"));
      Map<String, String> settings = new LinkedHashMap<>();
      settings.put("exporter", "file");
      settings.put("exporter.file.path", dir.resolve("out").resolve("tx.jsonl").toString());
      settings.put("log.dir", dir.resolve("logs").toString());
      settings.put(
          "debug.transform-list", dir.resolve("out").resolve("transformed.txt").toString());
      settings.putAll(agentConfig);
      for (Map.Entry<String, String> setting : settings.entrySet()) {
        command.add("-Dargos." + setting.getKey() + "=" + setting.getValue());
      }
    }
    command.add("-jar");
    command.add(app.artifact().toAbsolutePath().toString());
    return command;
  }

  /** Starts the app and waits until its port is bound and the readiness path answers 200. */
  public RunningApp start() throws IOException, InterruptedException {
    if (!app.isJar()) {
      throw new UnsupportedOperationException(
          app.name() + " is a " + app.kind() + " app; WAR deployments arrive with WO-05");
    }
    if (workDir == null) {
      throw new IllegalStateException("workDir(...) is required");
    }
    Path dir = Files.createDirectories(workDir.toAbsolutePath());
    Path tmp = Files.createDirectories(dir.resolve("tmp"));
    Files.createDirectories(dir.resolve("out"));
    if (agentJar != null) {
      Path copy = Files.createDirectories(dir.resolve("agent")).resolve("argos-agent.jar");
      Files.copy(agentJar, copy, StandardCopyOption.REPLACE_EXISTING);
    }
    int port = freePort();
    List<String> command = command(dir);
    ProcessBuilder builder = new ProcessBuilder(command).directory(dir.toFile());
    Map<String, String> env = builder.environment();
    env.keySet().removeIf(key -> key.toUpperCase(Locale.ROOT).startsWith("ARGOS_"));
    env.remove("JAVA_TOOL_OPTIONS");
    env.remove("JDK_JAVA_OPTIONS");
    env.remove("_JAVA_OPTIONS");
    for (String variable : List.of("TMP", "TEMP", "TMPDIR")) {
      env.put(variable, tmp.toString());
    }
    env.put("SERVER_PORT", Integer.toString(port));
    env.put("SERVER_ADDRESS", "127.0.0.1");
    env.put("SPRING_MAIN_BANNER_MODE", "off");
    env.putAll(environment);
    builder.redirectOutput(dir.resolve("stdout.log").toFile());
    builder.redirectError(dir.resolve("stderr.log").toFile());

    // T-BLD-006: every entry created in the temp directory from before the spawn on.
    TempWatch tempWatch = TempWatch.start(tmp);
    long spawn = System.nanoTime();
    Process process = builder.start();
    RunningApp running = new RunningApp(app, process, port, dir, command, jdk);
    running.tempWatch(tempWatch);
    try {
      long deadline = spawn + readyTimeout.toNanos();
      while (!portOpen(port)) {
        running.checkAlive(deadline);
        Thread.sleep(10);
      }
      long portBound = System.nanoTime();
      HttpClient client =
          HttpClient.newBuilder()
              .version(HttpClient.Version.HTTP_1_1)
              .connectTimeout(Duration.ofSeconds(2))
              .build();
      URI ready = URI.create(running.baseUrl() + app.readyPath());
      while (!answers200(client, ready)) {
        running.checkAlive(deadline);
        Thread.sleep(50);
      }
      long readyAt = System.nanoTime();
      running.startup(
          TimeUnit.NANOSECONDS.toMillis(portBound - spawn),
          TimeUnit.NANOSECONDS.toMillis(readyAt - spawn));
      return running;
    } catch (InterruptedException | RuntimeException e) {
      running.close();
      throw e;
    }
  }

  private static boolean answers200(HttpClient client, URI uri) throws InterruptedException {
    try {
      HttpResponse<Void> response =
          client.send(
              HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET().build(),
              HttpResponse.BodyHandlers.discarding());
      return response.statusCode() == 200;
    } catch (IOException e) {
      return false;
    }
  }

  private static boolean portOpen(int port) {
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 200);
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  static int freePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      socket.setReuseAddress(true);
      return socket.getLocalPort();
    }
  }
}
