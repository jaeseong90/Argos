/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.e2e;

import io.argos.server.testing.ServerTestEnvironment;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The whole server as a child JVM started from the boot jar ({@code -Dargos.test.bootJar}, set by
 * the Gradle test task; {@code server/build/libs/argos-server-<version>.jar} otherwise) against the
 * databases of a {@link ServerTestEnvironment}: every start phase, the gRPC collector on the
 * environment's port, HTTP and management on free loopback ports, the WAL in the environment's
 * private directory (a restart on the same environment recovers it).
 *
 * <p>{@link #kill()} is {@code destroyForcibly} ({@code TerminateProcess} on Windows, {@code
 * SIGKILL} elsewhere): no shutdown hook, no last flush, what a crash leaves behind. The console log
 * (JSON lines) goes to {@code <work>/<name>-stdout.log}.
 */
final class ServerProcess implements AutoCloseable {

  private final Process process;
  private final Path log;
  private final int httpPort;
  private final int managementPort;
  private final String name;

  private ServerProcess(Process process, Path log, int httpPort, int managementPort, String name) {
    this.process = process;
    this.log = log;
    this.httpPort = httpPort;
    this.managementPort = managementPort;
    this.name = name;
  }

  /**
   * Starts the boot jar and waits until the gRPC collector accepts connections (the {@code
   * LISTENERS} step, after the WAL recovery) and, when {@code awaitManagement}, until the
   * management port answers.
   *
   * @param work directory for the console log, the log directory and the temporary files
   * @param name file name prefix of this start ({@code start-1}, ...)
   * @param extra properties that win over the environment's
   * @param heap {@code -Xmx} value, e.g. {@code 1g}
   */
  static ServerProcess start(
      ServerTestEnvironment env,
      Path work,
      String name,
      Map<String, String> extra,
      String heap,
      boolean awaitManagement)
      throws IOException, InterruptedException {
    Path jar = bootJar();
    int http = freePort();
    int management = freePort();
    Map<String, String> properties = new LinkedHashMap<>(env.springProperties());
    properties.put("server.address", "127.0.0.1");
    properties.put("server.port", Integer.toString(http));
    properties.put("management.server.address", "127.0.0.1");
    properties.put("management.server.port", Integer.toString(management));
    properties.put("argos.web.public-url", "http://127.0.0.1:" + http);
    properties.putAll(extra);
    Path tmp = Files.createDirectories(work.resolve(name + "-tmp"));
    List<String> command = new ArrayList<>();
    command.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
    command.add("-Xmx" + heap);
    command.add("-Djava.io.tmpdir=" + tmp);
    command.add("-DLOG_PATH=" + work.resolve(name + "-logs"));
    command.add("-jar");
    command.add(jar.toString());
    // One argument per key: a key given twice would be joined with a comma by Spring.
    properties.forEach((k, v) -> command.add("--" + k + "=" + v));
    Path log = work.resolve(name + "-stdout.log");
    Process process =
        new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    ServerProcess server = new ServerProcess(process, log, http, management, name);
    try {
      server.awaitPort(env.collectorPort(), TimeUnit.MINUTES.toNanos(5));
      if (awaitManagement) {
        server.awaitPort(management, TimeUnit.MINUTES.toNanos(1));
      }
    } catch (IOException | InterruptedException | RuntimeException e) {
      server.kill();
      throw e;
    }
    return server;
  }

  /** The boot jar the Gradle task built. */
  static Path bootJar() throws IOException {
    String configured = System.getProperty("argos.test.bootJar");
    if (configured != null && !configured.isBlank() && Files.isRegularFile(Path.of(configured))) {
      return Path.of(configured);
    }
    Path libs =
        Paths.get(System.getProperty("argos.repositoryRoot", ".."), "server", "build", "libs");
    if (Files.isDirectory(libs)) {
      try (DirectoryStream<Path> jars = Files.newDirectoryStream(libs, "argos-server-*.jar")) {
        for (Path jar : jars) {
          // The executable jar has no classifier: not -plain, -test-fixtures, -sources.
          if (jar.getFileName()
              .toString()
              .matches("argos-server-\\d+\\.\\d+\\.\\d+(-SNAPSHOT)?\\.jar")) {
            return jar;
          }
        }
      }
    }
    throw new IllegalStateException(
        "The server boot jar is not built: run ./gradlew :server:bootJar (the integrationTest task"
            + " depends on it)");
  }

  private void awaitPort(int port, long timeoutNanos) throws IOException, InterruptedException {
    long deadline = System.nanoTime() + timeoutNanos;
    while (!accepts(port)) {
      if (!process.isAlive()) {
        throw new IllegalStateException(
            "The server ("
                + name
                + ") exited with "
                + process.exitValue()
                + "; last output:\n"
                + tail(40));
      }
      if (System.nanoTime() > deadline) {
        throw new IllegalStateException(
            "The server (" + name + ") did not open port " + port + "; last output:\n" + tail(40));
      }
      TimeUnit.MILLISECONDS.sleep(250);
    }
  }

  /** The process id. */
  long pid() {
    return process.pid();
  }

  /** The HTTP port. */
  int httpPort() {
    return httpPort;
  }

  /** The management port (8081 in production). */
  int managementPort() {
    return managementPort;
  }

  /** The console log file. */
  Path log() {
    return log;
  }

  /** The console log so far. */
  String logText() throws IOException {
    return Files.exists(log) ? Files.readString(log, StandardCharsets.UTF_8) : "";
  }

  /** Whether the process runs. */
  boolean isAlive() {
    return process.isAlive();
  }

  /** {@code destroyForcibly} and wait for the exit (a crash: no shutdown hook runs). */
  void kill() throws InterruptedException {
    process.destroyForcibly();
    if (!process.waitFor(60, TimeUnit.SECONDS)) {
      throw new IllegalStateException("The server (" + name + ") did not exit after the kill");
    }
  }

  /** The last {@code lines} lines of the console log. */
  String tail(int lines) {
    try {
      List<String> all = Files.readAllLines(log, StandardCharsets.UTF_8);
      return String.join("\n", all.subList(Math.max(0, all.size() - lines), all.size()));
    } catch (IOException e) {
      return "(no output: " + e.getMessage() + ")";
    }
  }

  /** Kills the process if it still runs (end of a test). */
  @Override
  public void close() {
    if (process.isAlive()) {
      process.destroyForcibly();
      try {
        process.waitFor(60, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  static boolean accepts(int port) {
    try (Socket s = new Socket(InetAddress.getLoopbackAddress(), port)) {
      return s.isConnected();
    } catch (IOException e) {
      return false;
    }
  }

  static int freePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      return socket.getLocalPort();
    }
  }
}
