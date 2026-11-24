/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.launcher;

import io.argos.agent.testing.collector.FakeCollector;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Starts an application for an agent test (18 §5.2, DSN-18-04): sample app x JDK x agent on/off x
 * agent settings x JVM options x environment x virtual threads, as a local process or (with {@link
 * #container}) in Docker.
 *
 * <pre>
 * try (RunningApp app =
 *     AppLauncher.of(SampleApp.named("shop-boot3"), Jdk.of(21))
 *         .withAgent(agentJar, Map.of("async.step", "true"))
 *         .workDir(dir)
 *         .start(Duration.ofMinutes(3))) {
 *   app.get("/api/orders/1");
 *   assertThat(app.collected()).transactions().hasSize(1);
 * }
 * </pre>
 *
 * <p>Every run gets its own work directory: {@code tmp/} (the JVM's {@code java.io.tmpdir} and the
 * {@code TMP}/{@code TEMP}/{@code TMPDIR} of the process, watched for T-BLD-006), {@code
 * agent/argos-agent.jar} (a copy, so that nothing can appear next to the jar unseen), {@code logs/}
 * ({@code argos.log.dir}), {@code out/} ({@code argos-transactions.jsonl} of {@code
 * argos.test.exporter=file:<dir>}, {@code transformed.txt}, {@code transform.log}), {@code
 * stdout.log} and {@code stderr.log} (separate, T-AG-060), and {@code argos-agent.yml} when {@link
 * #agentConfigFile} is on.
 *
 * <p>JVM options: {@link JvmOptions#defaults} ({@code -XX:-UsePerfData}, C-27 verification, JDK 25
 * Unsafe deny), then {@link #withJvmOptions}. HTTP apps bind {@code 127.0.0.1} on a free port with
 * Spring Boot's banner off; {@code ARGOS_*}, {@code JAVA_TOOL_OPTIONS}, {@code JDK_JAVA_OPTIONS}
 * and {@code _JAVA_OPTIONS} of the harness environment are not inherited.
 *
 * <p>Agent settings with the harness defaults on ({@link #harnessDefaults}): {@code app.name} (the
 * sample app's name) and {@code app.env=test} (the required keys of 10 §4.3 P4, so that a normal
 * run logs no configuration ERROR or WARN), {@code log.dir}, {@code debug.transform-list}, {@code
 * debug.transform-log} and {@code test.selfdiag-file} ({@code out/selfdiag.json}, ADR-0010 C-95) in
 * the work directory, and {@code test.exporter=file:<workDir>/out} unless a {@link
 * #collector(FakeCollector)} is set (its {@code server.*} settings apply with or without the
 * defaults); the test's own settings come last and win. Readiness (HTTP apps): the port is bound
 * and the readiness path answers 200 (ADR-0004 C-49 startup is spawn to port binding).
 *
 * <p>WAR apps ({@link SampleApp#isWar}) run in a standalone Tomcat staged for the run ({@link
 * TomcatStaging}: CATALINA_HOME from the sample app's build, CATALINA_BASE {@code
 * <workDir>/catalina-base} with a {@code 127.0.0.1:<port>} connector, a loopback shutdown port with
 * a random command, one WAR copy per context and the extra web applications of {@link #webapp})
 * with the same JVM presets, agent, temp directory, environment rules and output files; readiness
 * is the port plus {@code /<context><readiness path>} = 200 for every context. The Tomcat is {@link
 * #tomcat(String)}, else {@link SampleApp#defaultTomcat(int)}. Request paths carry the context
 * ({@code app.get("/shop-a/api/orders/1")}). {@link RunningApp#gracefulStop()} (and {@code
 * close()}) of a WAR run sends the shutdown command first, so Tomcat stops its web applications
 * (and its leak detection logs) before the JVM exits, on Windows too.
 */
public final class AppLauncher {
  /** {@code app.env} of every harness run (harness defaults). */
  public static final String HARNESS_ENV = "test";

  /** File name of {@code argos.test.selfdiag-file} in {@code <workDir>/out} (harness defaults). */
  public static final String SELF_DIAGNOSTICS_FILE = "selfdiag.json";

  private final SampleApp app;
  private final Jdk jdk;
  private Path agentJar;
  private final AgentSettings agentSettings = new AgentSettings();
  private final List<String> jvmOptions = new ArrayList<>();
  private final Map<String, String> environment = new LinkedHashMap<>();
  private final List<String> programArguments = new ArrayList<>();
  private Path workDir;
  private Duration readyTimeout = Duration.ofMinutes(3);
  private boolean verification = true;
  private boolean harnessDefaults = true;
  private boolean configFile;
  private boolean watchSystemTemp;
  private FakeCollector collector;
  private boolean inDocker;
  private String cpus;
  private String memory;
  private String tokenFileMode;
  private String tomcatKey;
  private final Map<String, Path> webapps = new LinkedHashMap<>();

  private AppLauncher(SampleApp app, Jdk jdk) {
    this.app = app;
    this.jdk = jdk;
  }

  /** A launcher of {@code app} on {@code jdk} (18 §5.2 {@code AppLauncher.of(app, jdk)}). */
  public static AppLauncher of(SampleApp app, Jdk jdk) {
    return new AppLauncher(app, jdk);
  }

  /** A launcher of {@code app} on the JDK of {@code java} (feature version {@code feature}). */
  public static AppLauncher of(SampleApp app, Path java, int feature) {
    return new AppLauncher(app, new Jdk(feature, java));
  }

  /** Attaches the agent jar with the harness defaults (see the class comment). */
  public AppLauncher withAgent(Path jar) {
    this.agentJar = jar;
    return this;
  }

  /** Attaches the agent jar with these settings (keys of 10 §15 without {@code argos.}). */
  public AppLauncher withAgent(Path jar, Map<String, String> argosConfig) {
    this.agentJar = jar;
    agentSettings.putAll(argosConfig);
    return this;
  }

  /** The off baseline (no {@code -javaagent}). */
  public AppLauncher withoutAgent() {
    this.agentJar = null;
    return this;
  }

  /** One agent setting ({@code -Dargos.<key>=<value>}), after (and so above) the defaults. */
  public AppLauncher agentConfig(String key, String value) {
    agentSettings.put(key, value);
    return this;
  }

  /**
   * Also writes {@code <workDir>/argos-agent.yml} with every agent setting and passes {@code
   * -Dargos.config} (the agent reads YAML from WO-10 on; until then it logs that the file is not
   * supported yet, so the settings are passed as system properties too).
   */
  public AppLauncher agentConfigFile(boolean on) {
    this.configFile = on;
    return this;
  }

  /** Without the harness default agent settings: the test sets everything itself. */
  public AppLauncher harnessDefaults(boolean on) {
    this.harnessDefaults = on;
    return this;
  }

  /** JVM options after the presets ({@code -Xmx512m}, {@code -XX:NativeMemoryTracking=...}). */
  public AppLauncher withJvmOptions(String... options) {
    jvmOptions.addAll(Arrays.asList(options));
    return this;
  }

  /** Same as {@link #withJvmOptions}. */
  public AppLauncher jvmOptions(String... options) {
    return withJvmOptions(options);
  }

  /** An environment variable of the app (e.g. {@code SAMPLE_EXT_URL}). */
  public AppLauncher withEnv(String key, String value) {
    environment.put(key, value);
    return this;
  }

  /** Same as {@link #withEnv}. */
  public AppLauncher env(String key, String value) {
    return withEnv(key, value);
  }

  /** {@code SAMPLE_VT=true}: the sample app's virtual thread executor (18 §4.1, Boot 3.2+). */
  public AppLauncher virtualThreads(boolean on) {
    if (on) {
      environment.put("SAMPLE_VT", "true");
    } else {
      environment.remove("SAMPLE_VT");
    }
    return this;
  }

  /** Program arguments ({@link SampleApp#mainClass} apps). */
  public AppLauncher args(String... arguments) {
    programArguments.addAll(Arrays.asList(arguments));
    return this;
  }

  /**
   * Sends to {@code fakeCollector} through the agent's default {@code grpc} exporter instead of the
   * JSONL file exporter ({@code null} restores the file exporter): {@code server.address} (the
   * collector's {@code 127.0.0.1:<port>}, in container mode {@code host.docker.internal:<port>}),
   * {@code server.tls} as the collector runs (TLS unless it was started plaintext), {@code
   * server.tls.ca-file} ({@code <workDir>/collector/ca.pem}, a copy of {@link
   * FakeCollector#trustPem()}) and {@code server.token-file} ({@code <workDir>/collector/token},
   * the collector's test token, owner-only where the file system has POSIX permissions; never on
   * the command line, SEC-06; in container mode with {@link #tokenFileMode(String)} the agent reads
   * a copy inside the container). These apply with or without the harness defaults.
   */
  public AppLauncher collector(FakeCollector fakeCollector) {
    this.collector = fakeCollector;
    return this;
  }

  /** The collector of {@link #collector(FakeCollector)}, or null. */
  FakeCollector collector() {
    return collector;
  }

  /**
   * Runs the app in a Docker container ({@code eclipse-temurin:<jdk>-jdk}, Testcontainers) with
   * {@code --cpus}/{@code --memory} limits (18 §5.2, 01 §7.3 #9); {@code null} keeps a limit off.
   */
  public AppLauncher container(boolean docker, String cpuLimit, String memoryLimit) {
    this.inDocker = docker;
    this.cpus = cpuLimit;
    this.memory = memoryLimit;
    return this;
  }

  /**
   * Container mode with {@link #collector(FakeCollector)} only: the POSIX mode ({@code "0644"},
   * {@code "0600"}, octal) of the token file the agent reads. The container's entrypoint copies
   * {@code <workDir>/collector/token} to {@value ContainerApp#TOKEN_FILE} inside the container and
   * sets this mode with {@code chmod}, and {@code server.token-file} names that copy, so the
   * permissions the agent checks (16 §2.6 WARN) do not depend on how the host's bind mount shows
   * the run directory (Docker Desktop shows host files as readable by others). Null (default): the
   * agent reads the bind-mounted file.
   */
  public AppLauncher tokenFileMode(String octalMode) {
    if (octalMode != null && !octalMode.matches("[0-7]{3,4}")) {
      throw new IllegalArgumentException("token file mode must be octal (0600, 644): " + octalMode);
    }
    this.tokenFileMode = octalMode;
    return this;
  }

  /** The mode of {@link #tokenFileMode(String)}, or null. */
  String tokenFileMode() {
    return tokenFileMode;
  }

  /**
   * The standalone Tomcat of a WAR app, a key of its launch descriptor ({@code tomcat9}, {@code
   * tomcat10}, {@code tomcat11}); default {@link SampleApp#defaultTomcat(int)}.
   */
  public AppLauncher tomcat(String key) {
    this.tomcatKey = key;
    return this;
  }

  /**
   * WAR apps only: deploys {@code war} as one more web application of the staged Tomcat, served
   * under {@code /<context>} (e.g. agent-testing's Spring-free servlet fixture next to the sample
   * app's contexts). Readiness still waits for the sample app's contexts only; Tomcat deploys every
   * web application before it opens its connector.
   */
  public AppLauncher webapp(String context, Path war) {
    if (!app.isWar()) {
      throw new IllegalStateException(app.name() + " is not a WAR app");
    }
    webapps.put(context, war);
    return this;
  }

  /** The Tomcat key a WAR run uses (see {@link #tomcat(String)}). */
  public String tomcatKey() {
    if (!app.isWar()) {
      throw new IllegalStateException(app.name() + " is not a WAR app");
    }
    return tomcatKey != null ? tomcatKey : app.defaultTomcat(jdk.feature());
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

  /**
   * Also records agent-like entries ({@code argos*}, {@code bytebuddy*}) that appear in the harness
   * process's own temp directory during the run (T-BLD-006, see {@link ExitedApp}).
   */
  public AppLauncher watchSystemTemp(boolean on) {
    this.watchSystemTemp = on;
    return this;
  }

  public SampleApp app() {
    return app;
  }

  public Jdk jdk() {
    return jdk;
  }

  boolean dockerMode() {
    return inDocker;
  }

  String cpus() {
    return cpus;
  }

  String memory() {
    return memory;
  }

  Map<String, String> environment() {
    return environment;
  }

  /** The effective agent settings of a run in {@code dir} (defaults, collector, test settings). */
  public AgentSettings agentSettings(Path dir) {
    AgentSettings settings = new AgentSettings();
    if (harnessDefaults) {
      settings.put("app.name", app.name());
      settings.put("app.env", HARNESS_ENV);
      settings.put("log.dir", dir.resolve("logs").toString());
      settings.put(
          "debug.transform-list", dir.resolve("out").resolve("transformed.txt").toString());
      settings.put("debug.transform-log", dir.resolve("out").resolve("transform.log").toString());
      settings.put(
          "test.selfdiag-file", dir.resolve("out").resolve(SELF_DIAGNOSTICS_FILE).toString());
      if (collector == null) {
        settings.put("test.exporter", "file:" + dir.resolve("out"));
      }
    }
    if (collector != null) {
      settings.putAll(collectorSettings(dir));
    }
    settings.putAll(agentSettings.asMap());
    if (harnessDefaults
        && assumesEveryProfile(settings.get(NORMAL_RATE))
        && settings.get(OVERHEAD_MONITOR) == null) {
      // ADR-0010 C-240 (3): a suite that assumes normal-rate=1.0 must not see the CPU guard halve
      // it on a host with few cores (development builds only; a release build drops the key).
      settings.put(OVERHEAD_MONITOR, "off");
    }
    return settings;
  }

  /** {@code sampling.profile.normal-rate}. */
  static final String NORMAL_RATE = "sampling.profile.normal-rate";

  /** {@code test.overhead-monitor} (development builds only, C-240 (3)). */
  static final String OVERHEAD_MONITOR = "test.overhead-monitor";

  /** True for a {@code normal-rate} of 1.0 (every normal transaction keeps its profile). */
  static boolean assumesEveryProfile(String normalRate) {
    if (normalRate == null) {
      return false;
    }
    try {
      return Double.parseDouble(normalRate.trim()) >= 1.0;
    } catch (NumberFormatException e) {
      return false;
    }
  }

  /** Directory of the collector files of a run ({@link #collector(FakeCollector)}). */
  static final String COLLECTOR_DIR = "collector";

  /**
   * The {@code server.*} settings of {@link #collector(FakeCollector)} for a run in {@code dir}.
   */
  Map<String, String> collectorSettings(Path dir) {
    Map<String, String> settings = new LinkedHashMap<>();
    Path files = dir.resolve(COLLECTOR_DIR);
    settings.put("server.address", inDocker ? collector.containerAddress() : collector.address());
    settings.put("server.tls", Boolean.toString(collector.tls()));
    if (collector.tls()) {
      settings.put("server.tls.ca-file", files.resolve("ca.pem").toString());
    }
    settings.put(
        "server.token-file",
        inDocker && tokenFileMode != null
            ? ContainerApp.TOKEN_FILE
            : files.resolve("token").toString());
    return settings;
  }

  /** Writes the token file and the CA copy that {@link #collectorSettings} name. */
  private void writeCollectorFiles(Path dir) throws IOException {
    Path files = Files.createDirectories(dir.resolve(COLLECTOR_DIR));
    Path token = files.resolve("token");
    Files.deleteIfExists(token);
    if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
      Files.createFile(
          token,
          PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    } else {
      Files.createFile(token);
    }
    Files.writeString(token, collector.token() + "\n", StandardCharsets.UTF_8);
    Path pem = collector.trustPem();
    if (pem != null) {
      Files.copy(pem, files.resolve("ca.pem"), StandardCopyOption.REPLACE_EXISTING);
    }
  }

  /** The command line a run in {@code dir} uses (also for reports). */
  public List<String> command(Path dir) {
    LinkedHashSet<String> options =
        new LinkedHashSet<>(JvmOptions.defaults(jdk.feature(), verification));
    options.addAll(jvmOptions);
    List<String> command = new ArrayList<>();
    command.add(jdk.java().toString());
    command.addAll(options);
    command.add("-Djava.io.tmpdir=" + dir.resolve("tmp"));
    if (agentJar != null) {
      command.add("-javaagent:" + dir.resolve("agent").resolve("argos-agent.jar"));
      AgentSettings settings = agentSettings(dir);
      command.addAll(settings.systemProperties());
      if (configFile) {
        command.add("-Dargos.config=" + dir.resolve("argos-agent.yml"));
      }
    }
    if (app.isWar()) {
      // Tomcat's own options and main class; program arguments do not apply.
      command.addAll(
          TomcatStaging.arguments(
              app.tomcatHome(tomcatKey()), TomcatStaging.base(dir), jdk.feature()));
      return command;
    }
    if (app.isMainClass()) {
      command.add("-cp");
      command.add(
          app.classpath().stream()
              .map(p -> p.toAbsolutePath().toString())
              .collect(Collectors.joining(File.pathSeparator)));
      command.add(app.mainClass());
    } else {
      command.add("-jar");
      command.add(app.artifact().toAbsolutePath().toString());
    }
    command.addAll(programArguments);
    return command;
  }

  /** Creates the work directory layout and copies the agent jar. */
  Path prepare() throws IOException {
    if (workDir == null) {
      throw new IllegalStateException("workDir(...) is required");
    }
    if (tokenFileMode != null && (!inDocker || collector == null || agentJar == null)) {
      throw new IllegalStateException(
          "tokenFileMode(...) needs container mode, an agent and collector(...)");
    }
    Path dir = Files.createDirectories(workDir.toAbsolutePath());
    Files.createDirectories(dir.resolve("tmp"));
    Files.createDirectories(dir.resolve("out"));
    if (agentJar != null) {
      Path copy = Files.createDirectories(dir.resolve("agent")).resolve("argos-agent.jar");
      Files.copy(agentJar, copy, StandardCopyOption.REPLACE_EXISTING);
      if (collector != null) {
        writeCollectorFiles(dir);
      }
      if (configFile) {
        Files.writeString(
            dir.resolve("argos-agent.yml"), agentSettings(dir).yaml(), StandardCharsets.UTF_8);
      }
    }
    return dir;
  }

  private ProcessBuilder processBuilder(Path dir, List<String> command) {
    ProcessBuilder builder = new ProcessBuilder(command).directory(dir.toFile());
    Map<String, String> env = builder.environment();
    env.keySet().removeIf(key -> key.toUpperCase(Locale.ROOT).startsWith("ARGOS_"));
    env.remove("JAVA_TOOL_OPTIONS");
    env.remove("JDK_JAVA_OPTIONS");
    env.remove("_JAVA_OPTIONS");
    for (String variable : List.of("TMP", "TEMP", "TMPDIR")) {
      env.put(variable, dir.resolve("tmp").toString());
    }
    return builder;
  }

  /** {@link #start(Duration)} with the configured timeout (default 180 s). */
  public RunningApp start() throws IOException, InterruptedException {
    return start(readyTimeout);
  }

  /**
   * Starts an HTTP app and waits until its port is bound and the readiness path answers 200 (18
   * §5.2 {@code start(readyTimeout)}); for a WAR app, under every web context.
   */
  public RunningApp start(Duration timeout) throws IOException, InterruptedException {
    if (app.isMainClass()) {
      throw new UnsupportedOperationException(
          app.name() + " is a main-class app: it runs with runToExit()");
    }
    if (inDocker) {
      if (app.isWar()) {
        throw new UnsupportedOperationException(
            app.name() + " is a WAR app: container mode runs jar apps, WAR apps run as processes");
      }
      return ContainerApp.start(this, prepare(), timeout);
    }
    Path dir = prepare();
    int port = freePort();
    TomcatStaging.Shutdown shutdown = null;
    if (app.isWar()) {
      int shutdownPort = freePort();
      while (shutdownPort == port) {
        shutdownPort = freePort();
      }
      shutdown = TomcatStaging.Shutdown.random(shutdownPort);
      TomcatStaging.stage(
          app, app.tomcatHome(tomcatKey()), TomcatStaging.base(dir), port, shutdown, webapps);
    }
    List<String> command = command(dir);
    ProcessBuilder builder = processBuilder(dir, command);
    Map<String, String> env = builder.environment();
    if (!app.isWar()) {
      // Spring Boot's embedded server (relaxed binding); a WAR's port is Tomcat's server.xml.
      env.put("SERVER_PORT", Integer.toString(port));
      env.put("SERVER_ADDRESS", "127.0.0.1");
    }
    env.put("SPRING_MAIN_BANNER_MODE", "off");
    env.putAll(environment);
    builder.redirectOutput(dir.resolve("stdout.log").toFile());
    builder.redirectError(dir.resolve("stderr.log").toFile());

    // T-BLD-006: every entry created in the temp directory from before the spawn on.
    TempWatch tempWatch = TempWatch.start(dir.resolve("tmp"));
    long spawn = System.nanoTime();
    Process process = builder.start();
    ProcessApp running =
        new ProcessApp(app, jdk, process, port, dir, command, tempWatch, collector);
    running.tomcatShutdown(shutdown);
    try {
      long deadline = spawn + timeout.toNanos();
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
      for (String context : app.contextPaths()) {
        URI ready = URI.create(running.baseUrl() + context + app.readyPath());
        while (!answers200(client, ready)) {
          running.checkAlive(deadline);
          Thread.sleep(50);
        }
      }
      long readyAt = System.nanoTime();
      running.startup(Duration.ofNanos(portBound - spawn), Duration.ofNanos(readyAt - spawn));
      return running;
    } catch (InterruptedException | RuntimeException e) {
      running.close();
      throw e;
    }
  }

  /**
   * Runs a {@link SampleApp#mainClass} app to completion (the attach tests' trivial app) and
   * returns its outputs, the temp directory's events and the run directory.
   */
  public ExitedApp runToExit(Duration timeout) throws IOException, InterruptedException {
    if (inDocker) {
      throw new UnsupportedOperationException("runToExit runs local processes only");
    }
    Path dir = prepare();
    List<String> command = command(dir);
    ProcessBuilder builder = processBuilder(dir, command);
    builder.environment().putAll(environment);
    Path systemTemp = systemTemp();
    List<String> systemTempBefore = watchSystemTemp ? agentLikeEntries(systemTemp) : List.of();
    try (TempWatch watch = TempWatch.start(dir.resolve("tmp"))) {
      long spawn = System.nanoTime();
      Process process = builder.start();
      CompletableFuture<String> stdout = read(process.getInputStream());
      CompletableFuture<String> stderr = read(process.getErrorStream());
      boolean exited = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
      if (!exited) {
        process.destroyForcibly();
        process.waitFor(15, TimeUnit.SECONDS);
      }
      long end = System.nanoTime();
      // Watch events are delivered asynchronously: give the watch thread time to see the last.
      Thread.sleep(250);
      List<String> added = new ArrayList<>();
      if (watchSystemTemp) {
        added.addAll(agentLikeEntries(systemTemp));
        added.removeAll(systemTempBefore);
      }
      try {
        return new ExitedApp(
            app,
            jdk.feature(),
            dir,
            command,
            exited ? process.exitValue() : -1,
            !exited,
            stdout.get(10, TimeUnit.SECONDS),
            stderr.get(10, TimeUnit.SECONDS),
            watch.eventsAndLeftovers(),
            added,
            Duration.ofNanos(end - spawn));
      } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
        throw new IllegalStateException("cannot read the output of " + app.name(), e);
      }
    }
  }

  private static CompletableFuture<String> read(InputStream stream) {
    return CompletableFuture.supplyAsync(
        () -> {
          try (InputStream in = stream) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
        });
  }

  /** The temp directory of this (harness) process's environment, the agent's "system temp". */
  static Path systemTemp() {
    for (String variable : List.of("TMPDIR", "TEMP", "TMP")) {
      String value = System.getenv(variable);
      if (value != null && !value.isBlank() && Files.isDirectory(Path.of(value))) {
        return Path.of(value);
      }
    }
    return Path.of("/tmp");
  }

  /**
   * Entries whose names look like an agent's temporary files (jar extraction, ByteBuddy's {@code
   * byteBuddyJar*.jar}). The system temp directory is shared with other processes, so only these
   * names are compared; the repository's own check scripts ({@code argos-check-*}) are excluded.
   */
  static List<String> agentLikeEntries(Path directory) throws IOException {
    Pattern agentLike = Pattern.compile("(?i)^(argos|bytebuddy|byte-buddy).*");
    if (!Files.isDirectory(directory)) {
      return List.of();
    }
    try (Stream<Path> entries = Files.list(directory)) {
      return entries
          .map(path -> path.getFileName().toString())
          .filter(entry -> agentLike.matcher(entry).matches())
          .filter(entry -> !entry.startsWith("argos-check-"))
          .collect(Collectors.toList());
    }
  }

  static boolean answers200(HttpClient client, URI uri) throws InterruptedException {
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

  static boolean portOpen(int port) {
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
