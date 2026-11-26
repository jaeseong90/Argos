/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.testing.load;

import io.argos.server.testing.ServerTestEnvironment;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * The whole server as a child JVM started from the boot jar for the server load run (WO-29, 18 §8),
 * with the JVM options of 17 §7.2 and what the measurement needs:
 *
 * <ul>
 *   <li>{@code -Xms} = {@code -Xmx} = the heap of the profile, G1 with {@code
 *       MaxGCPauseMillis=200}, {@code MaxDirectMemorySize}, {@code ExitOnOutOfMemoryError}, {@code
 *       HeapDumpOnOutOfMemoryError} and {@code ErrorFile} into the work directory, a GC log, UTC
 *       and UTF-8;
 *   <li>{@code -XX:NativeMemoryTracking=summary} for {@code jcmd VM.native_memory summary};
 *   <li>a JFR recording of {@code jdk.VirtualThreadPinned} only (12 open item 4), dumped by {@link
 *       #dumpJfr};
 *   <li>the environment's databases, WAL and collector port ({@link
 *       ServerTestEnvironment#springProperties()}), HTTP and management on free loopback ports;
 *   <li>a metrics bearer token generated at run time into a file of the work directory (owner-only
 *       on POSIX), allowed from loopback only. The token is never printed.
 * </ul>
 *
 * <p>The server's console (JSON lines) goes to {@code <work>/server-stdout.log}.
 */
public final class LoadServerJvm implements AutoCloseable {

  /** The JFR recording name. */
  public static final String JFR_NAME = "argos-load";

  private static final SecureRandom RANDOM = new SecureRandom();

  /**
   * Options of the child JVM.
   *
   * @param bootJar the server's executable jar
   * @param heap {@code -Xms}/{@code -Xmx}, e.g. {@code 4g}
   * @param maxDirectMemory {@code -XX:MaxDirectMemorySize}, e.g. {@code 512m}
   * @param jfrPinned record {@code jdk.VirtualThreadPinned}
   * @param extraProperties server properties that win over the environment's
   * @param startTimeout how long the start may take
   */
  public record Options(
      Path bootJar,
      String heap,
      String maxDirectMemory,
      boolean jfrPinned,
      Map<String, String> extraProperties,
      Duration startTimeout) {

    /** Validates and copies. */
    public Options {
      Objects.requireNonNull(bootJar, "bootJar");
      Objects.requireNonNull(heap, "heap");
      Objects.requireNonNull(maxDirectMemory, "maxDirectMemory");
      extraProperties = Map.copyOf(extraProperties);
      Objects.requireNonNull(startTimeout, "startTimeout");
    }
  }

  private final Process process;
  private final Path work;
  private final Path log;
  private final int httpPort;
  private final int managementPort;
  private final String metricsToken;
  private final List<String> jvmOptions;

  private LoadServerJvm(
      Process process,
      Path work,
      Path log,
      int httpPort,
      int managementPort,
      String metricsToken,
      List<String> jvmOptions) {
    this.process = process;
    this.work = work;
    this.log = log;
    this.httpPort = httpPort;
    this.managementPort = managementPort;
    this.metricsToken = metricsToken;
    this.jvmOptions = List.copyOf(jvmOptions);
  }

  /**
   * Starts the server and waits until the gRPC collector and the management port accept
   * connections.
   */
  public static LoadServerJvm start(ServerTestEnvironment env, Path work, Options options)
      throws IOException, InterruptedException {
    Files.createDirectories(work);
    int http = freePort();
    int management = freePort();
    String token = newToken();
    Path tokenFile = work.resolve("metrics.token");
    writeOwnerOnly(tokenFile, token);
    Map<String, String> properties = new LinkedHashMap<>(env.springProperties());
    properties.put("server.address", "127.0.0.1");
    properties.put("server.port", Integer.toString(http));
    properties.put("management.server.address", "127.0.0.1");
    properties.put("management.server.port", Integer.toString(management));
    properties.put("argos.web.public-url", "http://127.0.0.1:" + http);
    properties.put("argos.metrics.token-file", tokenFile.toAbsolutePath().toString());
    properties.put("argos.metrics.allow-cidrs[0]", "127.0.0.1/32");
    properties.put("argos.metrics.allow-cidrs[1]", "::1/128");
    properties.putAll(options.extraProperties());
    Path tmp = Files.createDirectories(work.resolve("server-tmp"));
    List<String> jvm = new ArrayList<>();
    jvm.add("-Xms" + options.heap());
    jvm.add("-Xmx" + options.heap());
    jvm.add("-XX:+UseG1GC");
    jvm.add("-XX:MaxGCPauseMillis=200");
    jvm.add("-XX:MaxDirectMemorySize=" + options.maxDirectMemory());
    jvm.add("-XX:+ExitOnOutOfMemoryError");
    jvm.add("-XX:+HeapDumpOnOutOfMemoryError");
    jvm.add("-XX:HeapDumpPath=" + work.toAbsolutePath());
    jvm.add("-XX:ErrorFile=" + work.resolve("hs_err_pid%p.log").toAbsolutePath());
    jvm.add(
        "-Xlog:gc*:file="
            + work.resolve("gc.log").toAbsolutePath()
            + ":time,uptime:filecount=5,filesize=20m");
    jvm.add("-XX:NativeMemoryTracking=summary");
    if (options.jfrPinned()) {
      Path settings = work.resolve("pinned.jfc");
      Files.writeString(settings, pinnedSettings(), StandardCharsets.UTF_8);
      jvm.add(
          "-XX:StartFlightRecording=name="
              + JFR_NAME
              + ",settings="
              + settings.toAbsolutePath()
              + ",disk=true,maxsize=256m");
    }
    jvm.add("-Djava.security.egd=file:/dev/urandom");
    jvm.add("-Dfile.encoding=UTF-8");
    jvm.add("-Duser.timezone=UTC");
    jvm.add("-Djava.io.tmpdir=" + tmp.toAbsolutePath());
    jvm.add("-DLOG_PATH=" + work.resolve("server-logs").toAbsolutePath());
    List<String> command = new ArrayList<>();
    command.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
    command.addAll(jvm);
    command.add("-jar");
    command.add(options.bootJar().toAbsolutePath().toString());
    // One argument per key: a key given twice would be joined with a comma by Spring.
    properties.forEach((k, v) -> command.add("--" + k + "=" + v));
    Path log = work.resolve("server-stdout.log");
    Process process =
        new ProcessBuilder(command)
            .directory(work.toFile())
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start();
    LoadServerJvm server = new LoadServerJvm(process, work, log, http, management, token, jvm);
    try {
      long deadline = System.nanoTime() + options.startTimeout().toNanos();
      server.awaitPort(env.collectorPort(), deadline);
      server.awaitPort(management, deadline);
      server.awaitPort(http, deadline);
    } catch (InterruptedException | RuntimeException e) {
      server.close();
      throw e;
    }
    return server;
  }

  /** The JFR settings: {@code jdk.VirtualThreadPinned} with stack traces, threshold 20 ms. */
  static String pinnedSettings() {
    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <configuration version="2.0" label="argos-load" description="WO-29: pinned virtual threads">
          <event name="jdk.VirtualThreadPinned">
            <setting name="enabled">true</setting>
            <setting name="stackTrace">true</setting>
            <setting name="threshold">20 ms</setting>
          </event>
        </configuration>
        """;
  }

  /** The JVM options given (without the jar and the server properties). */
  public List<String> jvmOptions() {
    return jvmOptions;
  }

  /** The process id. */
  public long pid() {
    return process.pid();
  }

  /** The HTTP port (loopback). */
  public int httpPort() {
    return httpPort;
  }

  /** {@code http://127.0.0.1:<httpPort>}. */
  public String baseUrl() {
    return "http://127.0.0.1:" + httpPort;
  }

  /** The management port (loopback). */
  public int managementPort() {
    return managementPort;
  }

  /** A scraper of {@code /actuator/prometheus} with the generated token. */
  public MetricsScraper scraper() {
    return new MetricsScraper(managementPort, metricsToken);
  }

  /** The work directory (logs, GC log, JFR, hs_err, heap dumps). */
  public Path workDirectory() {
    return work;
  }

  /** The console log. */
  public Path consoleLog() {
    return log;
  }

  /** Whether the process runs. */
  public boolean isAlive() {
    return process.isAlive();
  }

  /** The exit code once the process ended. */
  public OptionalInt exitCode() {
    return process.isAlive() ? OptionalInt.empty() : OptionalInt.of(process.exitValue());
  }

  /** {@code hs_err_pid*.log} and {@code *.hprof} files the JVM wrote into the work directory. */
  public List<Path> crashFiles() {
    try (Stream<Path> files = Files.list(work)) {
      return files
          .filter(
              p -> {
                String name = p.getFileName().toString();
                return name.startsWith("hs_err_pid") || name.endsWith(".hprof");
              })
          .sorted()
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Lines of the console log that contain {@code needle}. */
  public long consoleLines(String needle) {
    try (Stream<String> lines = Files.lines(log, StandardCharsets.UTF_8)) {
      return lines.filter(l -> l.contains(needle)).count();
    } catch (IOException | UncheckedIOException e) {
      return 0;
    }
  }

  /** {@code jcmd <pid> JFR.dump} into {@code file}; false when no recording runs. */
  public boolean dumpJfr(Path file) {
    if (!process.isAlive()) {
      return false;
    }
    try {
      Jcmd.run(pid(), 120, "JFR.dump", "name=" + JFR_NAME, "filename=" + file.toAbsolutePath());
      return Files.isRegularFile(file);
    } catch (IllegalStateException e) {
      return false;
    }
  }

  /** The last {@code lines} lines of the console log. */
  public String tail(int lines) {
    try {
      List<String> all = Files.readAllLines(log, StandardCharsets.UTF_8);
      return String.join("\n", all.subList(Math.max(0, all.size() - lines), all.size()));
    } catch (IOException e) {
      return "(no output: " + e.getMessage() + ")";
    }
  }

  private void awaitPort(int port, long deadlineNanos) throws InterruptedException {
    while (!accepts(port)) {
      if (!process.isAlive()) {
        throw new IllegalStateException(
            "The server exited with " + process.exitValue() + "; last output:\n" + tail(40));
      }
      if (System.nanoTime() > deadlineNanos) {
        throw new IllegalStateException(
            "The server did not open port " + port + "; last output:\n" + tail(40));
      }
      TimeUnit.MILLISECONDS.sleep(250);
    }
  }

  /** Stops the process ({@code destroy}, then {@code destroyForcibly} after 30 s). */
  @Override
  public void close() {
    if (!process.isAlive()) {
      return;
    }
    process.destroy();
    try {
      if (!process.waitFor(30, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        process.waitFor(60, TimeUnit.SECONDS);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      process.destroyForcibly();
    }
  }

  private static boolean accepts(int port) {
    try (Socket s = new Socket(InetAddress.getLoopbackAddress(), port)) {
      return s.isConnected();
    } catch (IOException e) {
      return false;
    }
  }

  private static int freePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      return socket.getLocalPort();
    }
  }

  private static String newToken() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static void writeOwnerOnly(Path file, String value) throws IOException {
    Files.writeString(file, value, StandardCharsets.UTF_8);
    if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
      Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
    }
  }

  @Override
  public String toString() {
    return "LoadServerJvm[pid=" + process.pid() + ", http=" + httpPort + "]";
  }
}
