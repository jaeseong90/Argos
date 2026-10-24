/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.load;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * shop-boot3 (the packaged jar) as a child process on 127.0.0.1 and a free port, without an agent;
 * output goes to {@code app.log} in the given directory. Closing stops it.
 */
final class ShopBoot3App implements AutoCloseable {

  private static final Duration READY_TIMEOUT = Duration.ofMinutes(3);

  private final Process process;
  private final Path log;
  private final String baseUrl;

  private ShopBoot3App(Process process, Path log, String baseUrl) {
    this.process = process;
    this.log = log;
    this.baseUrl = baseUrl;
  }

  /** Starts the app with {@code environment} added and waits for /actuator/health. */
  static ShopBoot3App start(Path java, Path jar, Path dir, Map<String, String> environment)
      throws IOException, InterruptedException {
    Files.createDirectories(dir.resolve("tmp"));
    int port = freePort();
    Path log = dir.resolve("app.log");
    List<String> command = new ArrayList<>();
    command.add(java.toString());
    command.add("-Xms256m");
    command.add("-Xmx768m");
    command.add("-Djava.io.tmpdir=" + dir.resolve("tmp"));
    command.add("-jar");
    command.add(jar.toString());
    command.add("--server.address=127.0.0.1");
    command.add("--server.port=" + port);
    ProcessBuilder builder =
        new ProcessBuilder(command)
            .directory(dir.toFile())
            .redirectErrorStream(true)
            .redirectOutput(log.toFile());
    // Deterministic switches: nothing from the developer's shell (SAMPLE_*, SERVER_*).
    builder
        .environment()
        .keySet()
        .removeIf(
            k ->
                k.startsWith("SAMPLE_")
                    || k.startsWith("SERVER_")
                    || k.equals("JAVA_TOOL_OPTIONS"));
    builder.environment().putAll(environment);
    ShopBoot3App app = new ShopBoot3App(builder.start(), log, "http://127.0.0.1:" + port);
    try {
      app.awaitReady();
    } catch (IOException | RuntimeException | AssertionError | InterruptedException e) {
      app.close();
      throw e;
    }
    return app;
  }

  String baseUrl() {
    return baseUrl;
  }

  Path log() {
    return log;
  }

  private void awaitReady() throws IOException, InterruptedException {
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    HttpRequest health =
        HttpRequest.newBuilder(URI.create(baseUrl + "/actuator/health"))
            .timeout(Duration.ofSeconds(5))
            .build();
    long deadline = System.nanoTime() + READY_TIMEOUT.toNanos();
    while (System.nanoTime() < deadline) {
      if (!process.isAlive()) {
        throw new AssertionError(
            "shop-boot3 exited with " + process.exitValue() + " during startup:\n" + tail());
      }
      try {
        HttpResponse<String> response = client.send(health, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 200 && response.body().contains("UP")) {
          return;
        }
      } catch (IOException e) {
        // Not listening yet.
      }
      Thread.sleep(250);
    }
    throw new AssertionError("shop-boot3 not ready after " + READY_TIMEOUT + ":\n" + tail());
  }

  /** The last lines of the app log, for failure messages. */
  String tail() throws IOException {
    if (!Files.exists(log)) {
      return "(no log)";
    }
    List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
    return String.join("\n", lines.subList(Math.max(0, lines.size() - 40), lines.size()));
  }

  private static int freePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      return socket.getLocalPort();
    }
  }

  @Override
  public void close() {
    process.descendants().forEach(ProcessHandle::destroy);
    process.destroy();
    try {
      if (!process.waitFor(15, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        process.waitFor(15, TimeUnit.SECONDS);
      }
    } catch (InterruptedException e) {
      process.destroyForcibly();
      Thread.currentThread().interrupt();
    }
  }
}
