/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.apibench;

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
import java.util.regex.Pattern;

/**
 * The server of the query API bench as a child JVM started from the boot jar ({@code
 * -Dargos.test.bootJar}, set by the Gradle task; {@code
 * server/build/libs/argos-server-<version>.jar} otherwise), so that a "cold" call is the first call
 * after a real server restart: a new JVM (no JIT-compiled code), new connection pools and empty
 * caches. HTTP, management and the gRPC collector on free loopback ports (one server of the
 * environment at a time).
 */
final class BenchServer implements AutoCloseable {

  /** An {@code -Xmx} size: digits with an optional k, m or g. */
  static final Pattern MAX_HEAP = Pattern.compile("[1-9][0-9]{0,6}[kKmMgG]?");

  private final Process process;
  private final Path log;
  private final int httpPort;

  private BenchServer(Process process, Path log, int httpPort) {
    this.process = process;
    this.log = log;
    this.httpPort = httpPort;
  }

  /**
   * Starts the boot jar and waits until the collector and the HTTP port accept connections (the
   * {@code LISTENERS} step, after the migrations and the WAL recovery). Every port is chosen right
   * before the start, the collector's included (a port picked minutes earlier may meanwhile be the
   * local port of one of the load's many client connections); a start that fails anyway is retried
   * twice with new ports.
   *
   * @param work directory for the console log, the server log and the temporary files
   * @param name file name prefix of this start
   * @param maxHeap the {@code -Xmx} value of the server JVM, e.g. {@code 4g} (13 §7.1) or {@code
   *     1g}
   */
  static BenchServer start(ServerTestEnvironment env, Path work, String name, String maxHeap)
      throws IOException, InterruptedException {
    if (!MAX_HEAP.matcher(maxHeap).matches()) {
      throw new IllegalArgumentException(
          "Server heap '" + maxHeap + "' is not an -Xmx size such as 4g or 1536m");
    }
    IllegalStateException failure = null;
    for (int attempt = 1; attempt <= 3; attempt++) {
      try {
        return startOnce(env, work, attempt == 1 ? name : name + "-retry" + attempt, maxHeap);
      } catch (IllegalStateException e) {
        failure = e;
      }
    }
    throw failure;
  }

  private static BenchServer startOnce(
      ServerTestEnvironment env, Path work, String name, String maxHeap)
      throws IOException, InterruptedException {
    int http = freePort();
    int management = freePort();
    int collector = freePort();
    Map<String, String> properties = new LinkedHashMap<>(env.springProperties());
    properties.put("server.address", "127.0.0.1");
    properties.put("server.port", Integer.toString(http));
    properties.put("management.server.address", "127.0.0.1");
    properties.put("management.server.port", Integer.toString(management));
    properties.put("argos.collector.port", Integer.toString(collector));
    properties.put("argos.web.public-url", "http://127.0.0.1:" + http);
    Path tmp = Files.createDirectories(work.resolve(name + "-tmp"));
    List<String> command = new ArrayList<>();
    command.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
    command.add("-Xmx" + maxHeap);
    command.add("-Djava.io.tmpdir=" + tmp);
    command.add("-DLOG_PATH=" + work.resolve(name + "-logs"));
    command.add("-jar");
    command.add(bootJar().toString());
    // One argument per key: Spring joins a key given twice with a comma.
    properties.forEach((k, v) -> command.add("--" + k + "=" + v));
    Path log = work.resolve(name + "-stdout.log");
    Process process =
        new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    BenchServer server = new BenchServer(process, log, http);
    try {
      server.awaitPort(collector, TimeUnit.MINUTES.toNanos(5));
      server.awaitPort(http, TimeUnit.MINUTES.toNanos(1));
    } catch (InterruptedException | RuntimeException e) {
      server.close();
      throw e;
    }
    return server;
  }

  /** {@code http://127.0.0.1:<port>}. */
  String baseUrl() {
    return "http://127.0.0.1:" + httpPort;
  }

  /** The boot jar the Gradle task built. */
  private static Path bootJar() throws IOException {
    String configured = System.getProperty("argos.test.bootJar");
    if (configured != null && !configured.isBlank() && Files.isRegularFile(Path.of(configured))) {
      return Path.of(configured);
    }
    Path libs =
        Paths.get(System.getProperty("argos.repositoryRoot", ".."), "server", "build", "libs");
    if (Files.isDirectory(libs)) {
      try (DirectoryStream<Path> jars = Files.newDirectoryStream(libs, "argos-server-*.jar")) {
        for (Path jar : jars) {
          if (jar.getFileName()
              .toString()
              .matches("argos-server-\\d+\\.\\d+\\.\\d+(-SNAPSHOT)?\\.jar")) {
            return jar;
          }
        }
      }
    }
    throw new IllegalStateException(
        "The server boot jar is not built: run ./gradlew :server:bootJar first");
  }

  private void awaitPort(int port, long timeoutNanos) throws InterruptedException {
    long deadline = System.nanoTime() + timeoutNanos;
    while (!accepts(port)) {
      if (!process.isAlive()) {
        throw new IllegalStateException(
            "The bench server exited with " + process.exitValue() + "; last output:\n" + tail());
      }
      if (System.nanoTime() > deadline) {
        throw new IllegalStateException(
            "The bench server did not open port " + port + "; last output:\n" + tail());
      }
      TimeUnit.MILLISECONDS.sleep(250);
    }
  }

  private String tail() {
    try {
      // The setup token line of a fresh database stays out of the test output.
      List<String> all =
          Files.readAllLines(log, StandardCharsets.UTF_8).stream()
              .filter(l -> !l.contains("SetupTokenBootstrap"))
              .toList();
      return String.join("\n", all.subList(Math.max(0, all.size() - 40), all.size()));
    } catch (IOException e) {
      return "(no output: " + e.getMessage() + ")";
    }
  }

  /** Stops the process (no ingest runs during the bench, so nothing is lost). */
  @Override
  public void close() {
    if (process.isAlive()) {
      process.destroy();
      try {
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
          process.destroyForcibly();
          process.waitFor(30, TimeUnit.SECONDS);
        }
      } catch (InterruptedException e) {
        process.destroyForcibly();
        Thread.currentThread().interrupt();
      }
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
}
