/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.launcher;

import io.argos.agent.testing.collector.Collected;
import io.argos.agent.testing.collector.FakeCollector;
import io.argos.agent.testing.collector.JsonlCollected;
import io.argos.agent.testing.jvm.JcmdClient;
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

/** A {@link RunningApp} that is a local process (the default of {@link AppLauncher}). */
public final class ProcessApp implements RunningApp {
  private final SampleApp app;
  private final Jdk jdk;
  private final Process process;
  private final int port;
  private final Path dir;
  private final List<String> command;
  private final TempWatch tempWatch;
  private final FakeCollector collector;
  private final HttpClient client =
      HttpClient.newBuilder()
          .version(HttpClient.Version.HTTP_1_1)
          .connectTimeout(Duration.ofSeconds(5))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();
  private Duration startup = Duration.ZERO;
  private Duration ready = Duration.ZERO;

  ProcessApp(
      SampleApp app,
      Jdk jdk,
      Process process,
      int port,
      Path dir,
      List<String> command,
      TempWatch tempWatch,
      FakeCollector collector) {
    this.app = app;
    this.jdk = jdk;
    this.process = process;
    this.port = port;
    this.dir = dir;
    this.command = List.copyOf(command);
    this.tempWatch = tempWatch;
    this.collector = collector;
  }

  void startup(Duration toPort, Duration toReady) {
    this.startup = toPort;
    this.ready = toReady;
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

  @Override
  public SampleApp app() {
    return app;
  }

  @Override
  public int jdk() {
    return jdk.feature();
  }

  @Override
  public int port() {
    return port;
  }

  @Override
  public long pid() {
    return process.pid();
  }

  @Override
  public boolean isAlive() {
    return process.isAlive();
  }

  @Override
  public Path workDir() {
    return dir;
  }

  @Override
  public List<String> command() {
    return command;
  }

  @Override
  public HttpResult call(String method, String path, Map<String, String> headers)
      throws IOException, InterruptedException {
    return Http.call(client, baseUrl(), method, path, headers);
  }

  @Override
  public LoadResult load(int rps, Duration duration, String... paths) throws InterruptedException {
    return Http.load(baseUrl(), rps, duration, paths);
  }

  @Override
  public Collected collected() {
    return collector != null ? collector.collected() : new JsonlCollected(transactions());
  }

  @Override
  public JcmdClient jcmd() {
    return new JcmdClient(jdk.tool("jcmd"), pid());
  }

  @Override
  public SelfDiagnostics selfDiagnostics() {
    return readSelfDiagnostics();
  }

  @Override
  public Duration startupTime() {
    return startup;
  }

  @Override
  public Duration readyTime() {
    return ready;
  }

  @Override
  public void kill9() {
    process.destroyForcibly();
    waitFor(15);
  }

  @Override
  public void gracefulStop() {
    process.destroy();
    if (!waitFor(15)) {
      process.destroyForcibly();
      waitFor(15);
    }
  }

  private boolean waitFor(int seconds) {
    try {
      return process.waitFor(seconds, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      process.destroyForcibly();
      return false;
    }
  }

  @Override
  public List<String> stdoutLines() {
    return lines(dir.resolve("stdout.log"));
  }

  @Override
  public List<String> stderrLines() {
    return lines(dir.resolve("stderr.log"));
  }

  @Override
  public String agentLog() {
    Path log = agentLogDir().resolve("argos-agent.log");
    try {
      return Files.exists(log) ? Files.readString(log, StandardCharsets.UTF_8) : "";
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public Set<String> tempCreated() {
    return tempWatch == null ? Set.of() : tempWatch.created();
  }

  @Override
  public Map<String, Long> tempEvents() {
    return tempWatch == null ? Map.of() : tempWatch.events();
  }

  @Override
  public String describe() {
    List<String> out = stdoutLines();
    List<String> err = stderrLines();
    return "app "
        + app.name()
        + " on JDK "
        + jdk.feature()
        + " in "
        + dir
        + "\ncommand: "
        + String.join(" ", command)
        + "\nstdout (last 40 lines):\n"
        + String.join("\n", out.subList(Math.max(0, out.size() - 40), out.size()))
        + "\nstderr:\n"
        + String.join("\n", err)
        + tomcatLogs();
  }

  /** The last 40 lines of each Tomcat log of a WAR run ({@code catalina-base/logs}). */
  private String tomcatLogs() {
    Path logs = TomcatStaging.base(dir).resolve("logs");
    if (!app.isWar() || !Files.isDirectory(logs)) {
      return "";
    }
    StringBuilder text = new StringBuilder();
    try (java.util.stream.Stream<Path> files = Files.list(logs)) {
      for (Path file : files.sorted().toList()) {
        List<String> lines = lines(file);
        text.append("\n")
            .append(file.getFileName())
            .append(" (last 40 lines):\n")
            .append(String.join("\n", lines.subList(Math.max(0, lines.size() - 40), lines.size())));
      }
    } catch (IOException e) {
      text.append("\n(cannot list ").append(logs).append(": ").append(e).append(')');
    }
    return text.toString();
  }

  static List<String> lines(Path file) {
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

  @Override
  public void close() {
    if (tempWatch != null) {
      tempWatch.close();
    }
    if (process.isAlive()) {
      gracefulStop();
    }
  }

  /** HTTP helpers shared by the process and container apps. */
  static final class Http {
    private Http() {}

    static HttpResult call(
        HttpClient client, String baseUrl, String method, String path, Map<String, String> headers)
        throws IOException, InterruptedException {
      HttpRequest.Builder request =
          HttpRequest.newBuilder(URI.create(baseUrl + path))
              .timeout(Duration.ofSeconds(30))
              .method(method, HttpRequest.BodyPublishers.noBody());
      headers.forEach(request::header);
      HttpResponse<String> response =
          client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      return new HttpResult(response.statusCode(), response.body(), response.headers().map());
    }

    /** Open model: one request every {@code 1/rps} s for {@code duration}, cycling the paths. */
    static LoadResult load(String baseUrl, int rps, Duration duration, String... paths)
        throws InterruptedException {
      if (rps <= 0 || paths.length == 0) {
        throw new IllegalArgumentException("load needs rps > 0 and at least one path");
      }
      HttpClient client =
          HttpClient.newBuilder()
              .version(HttpClient.Version.HTTP_1_1)
              .connectTimeout(Duration.ofSeconds(5))
              .followRedirects(HttpClient.Redirect.NEVER)
              .build();
      long total = Math.max(1, duration.toMillis() * rps / 1000);
      long intervalNanos = 1_000_000_000L / rps;
      java.util.concurrent.ConcurrentHashMap<Integer, java.util.concurrent.atomic.LongAdder>
          statuses = new java.util.concurrent.ConcurrentHashMap<>();
      java.util.concurrent.atomic.LongAdder failures = new java.util.concurrent.atomic.LongAdder();
      List<java.util.concurrent.CompletableFuture<?>> pending = new ArrayList<>();
      long start = System.nanoTime();
      for (long i = 0; i < total; i++) {
        long due = start + i * intervalNanos;
        long wait = due - System.nanoTime();
        if (wait > 0) {
          TimeUnit.NANOSECONDS.sleep(wait);
        }
        HttpRequest request =
            HttpRequest.newBuilder(URI.create(baseUrl + paths[(int) (i % paths.length)]))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        pending.add(
            client
                .sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .whenComplete(
                    (response, error) -> {
                      if (error != null) {
                        failures.increment();
                      } else {
                        statuses
                            .computeIfAbsent(
                                response.statusCode(),
                                k -> new java.util.concurrent.atomic.LongAdder())
                            .increment();
                      }
                    }));
      }
      for (java.util.concurrent.CompletableFuture<?> future : pending) {
        try {
          future.get(60, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException
            | java.util.concurrent.TimeoutException e) {
          // counted in whenComplete (or still running: reported as missing below)
        }
      }
      double seconds = (System.nanoTime() - start) / 1e9;
      Map<Integer, Long> counts = new java.util.TreeMap<>();
      statuses.forEach((status, count) -> counts.put(status, count.sum()));
      return new LoadResult(total, counts, failures.sum(), seconds);
    }
  }
}
