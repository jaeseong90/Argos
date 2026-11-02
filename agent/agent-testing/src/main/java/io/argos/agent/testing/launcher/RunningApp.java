/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.launcher;

import io.argos.agent.testing.baseline.TransformList;
import io.argos.agent.testing.export.JsonlTransactions;
import java.io.IOException;
import java.io.UncheckedIOException;
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
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * A sample app started by {@link AppLauncher}. {@link #close()} stops it: {@code destroy()}, then
 * {@code destroyForcibly()} after 15 s. On Windows both terminate the process without running JVM
 * shutdown hooks, so tests wait for what the agent writes periodically (JSONL about every 100 ms,
 * transformed-class list about every 2 s) instead of relying on the agent's shutdown flush.
 */
public final class RunningApp implements AutoCloseable {
  private final SampleApp app;
  private final Process process;
  private final int port;
  private final Path dir;
  private final List<String> command;
  private final int jdk;
  private final HttpClient client =
      HttpClient.newBuilder()
          .version(HttpClient.Version.HTTP_1_1)
          .connectTimeout(Duration.ofSeconds(5))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();
  private long spawnToPortMillis = -1;
  private long spawnToReadyMillis = -1;
  private TempWatch tempWatch;

  RunningApp(SampleApp app, Process process, int port, Path dir, List<String> command, int jdk) {
    this.app = app;
    this.process = process;
    this.port = port;
    this.dir = dir;
    this.command = List.copyOf(command);
    this.jdk = jdk;
  }

  /** The result of one HTTP call. */
  public record HttpResult(int status, String body, Map<String, List<String>> headers) {}

  void tempWatch(TempWatch watch) {
    this.tempWatch = watch;
  }

  void startup(long toPort, long toReady) {
    this.spawnToPortMillis = toPort;
    this.spawnToReadyMillis = toReady;
  }

  void checkAlive(long deadlineNanos) {
    if (!process.isAlive()) {
      throw new IllegalStateException(
          app.name()
              + " exited with code "
              + process.exitValue()
              + " before it was ready\n"
              + describe());
    }
    if (System.nanoTime() > deadlineNanos) {
      throw new IllegalStateException(app.name() + " was not ready in time\n" + describe());
    }
  }

  public SampleApp app() {
    return app;
  }

  public int jdk() {
    return jdk;
  }

  public int port() {
    return port;
  }

  public long pid() {
    return process.pid();
  }

  public boolean isAlive() {
    return process.isAlive();
  }

  /** The run's work directory (see {@link AppLauncher}). */
  public Path workDir() {
    return dir;
  }

  public List<String> command() {
    return command;
  }

  /** ADR-0004 C-49: process creation to port binding, in ms. */
  public long spawnToPortMillis() {
    return spawnToPortMillis;
  }

  /** Process creation to the first 200 of the readiness path, in ms. */
  public long spawnToReadyMillis() {
    return spawnToReadyMillis;
  }

  public String baseUrl() {
    return "http://127.0.0.1:" + port;
  }

  public HttpResult get(String path) throws IOException, InterruptedException {
    return request("GET", path, Map.of());
  }

  /** One HTTP/1.1 call to the app (no body, no redirects, 30 s timeout). */
  public HttpResult request(String method, String path, Map<String, String> headers)
      throws IOException, InterruptedException {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(baseUrl() + path))
            .timeout(Duration.ofSeconds(30))
            .method(method, HttpRequest.BodyPublishers.noBody());
    headers.forEach(request::header);
    HttpResponse<String> response =
        client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    return new HttpResult(response.statusCode(), response.body(), response.headers().map());
  }

  /** The JSONL exporter output ({@code out/tx.jsonl}). */
  public JsonlTransactions transactions() {
    return new JsonlTransactions(dir.resolve("out").resolve("tx.jsonl"));
  }

  public Path transformListFile() {
    return dir.resolve("out").resolve("transformed.txt");
  }

  /** The transformed-class list as written so far (empty before the first write). */
  public TransformList transformList() {
    return TransformList.read(transformListFile());
  }

  /** Waits (polling every 200 ms) until the transformed-class list satisfies {@code condition}. */
  public TransformList awaitTransformList(Predicate<TransformList> condition, Duration timeout) {
    long deadline = System.nanoTime() + timeout.toNanos();
    TransformList list = transformList();
    while (!condition.test(list) && System.nanoTime() < deadline) {
      try {
        Thread.sleep(200);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
      list = transformList();
    }
    return list;
  }

  public List<String> stdoutLines() {
    return lines(dir.resolve("stdout.log"));
  }

  public List<String> stderrLines() {
    return lines(dir.resolve("stderr.log"));
  }

  /** The agent log ({@code logs/argos-agent.log}), empty when absent. */
  public String agentLog() {
    Path log = dir.resolve("logs").resolve("argos-agent.log");
    try {
      return Files.exists(log) ? Files.readString(log, StandardCharsets.UTF_8) : "";
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Every entry created in the app's temp directory since before the spawn (T-BLD-006): top-level
   * entries seen by a watch service (also ones deleted again) plus the current tree, relative
   * paths.
   */
  public Set<String> tempCreated() {
    return tempWatch == null ? Set.of() : tempWatch.created();
  }

  /** Top-level temp entries seen by the watch service, with ms after the watch started. */
  public Map<String, Long> tempEvents() {
    return tempWatch == null ? Map.of() : tempWatch.events();
  }

  /** Names in the app's temp directory ({@code java.io.tmpdir}, TMP/TEMP/TMPDIR). */
  public List<String> tempEntries() {
    List<String> names = new ArrayList<>();
    try (Stream<Path> entries = Files.list(dir.resolve("tmp"))) {
      entries.forEach(path -> names.add(path.getFileName().toString()));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return names;
  }

  /** Command, startup and the tail of stdout/stderr, for assertion messages. */
  public String describe() {
    List<String> out = stdoutLines();
    List<String> err = stderrLines();
    return "app "
        + app.name()
        + " on JDK "
        + jdk
        + " in "
        + dir
        + "\ncommand: "
        + String.join(" ", command)
        + "\nstdout (last 40 lines):\n"
        + String.join("\n", out.subList(Math.max(0, out.size() - 40), out.size()))
        + "\nstderr:\n"
        + String.join("\n", err);
  }

  private static List<String> lines(Path file) {
    try {
      return Files.exists(file)
          ? new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8))
          : new ArrayList<>();
    } catch (IOException e) {
      // A line in progress may not be valid UTF-8 yet; read leniently.
      try {
        return new ArrayList<>(
            new String(Files.readAllBytes(file), StandardCharsets.UTF_8).lines().toList());
      } catch (IOException again) {
        throw new UncheckedIOException(again);
      }
    }
  }

  /** Stops the app (see the class comment) and waits for the process to end. */
  @Override
  public void close() {
    if (tempWatch != null) {
      tempWatch.close();
    }
    if (!process.isAlive()) {
      return;
    }
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
