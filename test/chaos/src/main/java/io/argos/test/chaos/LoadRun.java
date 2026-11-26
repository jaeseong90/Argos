/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.chaos;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * One run of the demo load generator ({@code java -jar load.jar}, sample-apps/load) against the
 * sample app: mix {@code bench} (18 §7.2), a fixed rate, one progress line per {@link #INTERVAL}
 * (read with its wall-clock time, {@link LoadInterval}) and the JSON summary at the end. 18 §11.1
 * names k6 at 200 TPS; the runner uses the project's own generator (same open-model fixed-rate
 * schedule, p99 per interval, no k6 on the runner), which the report states as a deviation.
 */
public final class LoadRun implements AutoCloseable {

  /** Report interval of the generator: the sub-windows of the noise band. */
  public static final Duration INTERVAL = Duration.ofSeconds(10);

  /** The generator's mix (18 §7.2). */
  public static final String MIX = "bench";

  private final Process process;
  private final Path summary;
  private final Thread reader;
  private final List<LoadInterval> intervals = new CopyOnWriteArrayList<>();
  private final Instant started;

  private LoadRun(Process process, Path summary, Path log, Instant started) {
    this.process = process;
    this.summary = summary;
    this.started = started;
    this.reader = new Thread(() -> read(log), "chaos-load-reader");
    this.reader.setDaemon(true);
    this.reader.start();
  }

  /** The command line of a run (also for the report). */
  static List<String> command(
      Path java, Path loadJar, String targetUrl, int rate, Duration duration, Path summary) {
    List<String> command = new ArrayList<>();
    command.add(java.toString());
    command.add("-Xmx256m");
    command.add("-jar");
    command.add(loadJar.toString());
    command.add("--target-url");
    command.add(targetUrl);
    command.add("--mix");
    command.add(MIX);
    command.add("--rate");
    command.add(Integer.toString(rate));
    command.add("--duration");
    command.add(duration.toSeconds() + "s");
    command.add("--report-interval");
    command.add(INTERVAL.toSeconds() + "s");
    command.add("--summary");
    command.add(summary.toString());
    return command;
  }

  /** Starts the generator; its output goes to {@code <dir>/load.log}. */
  public static LoadRun start(
      Path java, Path loadJar, String targetUrl, int rate, Duration duration, Path dir)
      throws IOException {
    Files.createDirectories(dir);
    Path summary = dir.resolve("load-summary.json");
    Files.deleteIfExists(summary);
    ProcessBuilder builder =
        new ProcessBuilder(command(java, loadJar, targetUrl, rate, duration, summary))
            .directory(dir.toFile())
            .redirectErrorStream(true);
    Map<String, String> env = builder.environment();
    // The generator also reads its settings from these variables; the options are the only source.
    for (String key :
        List.of(
            "TARGET_URL", "RATE", "MIX", "DURATION", "WARMUP", "SUMMARY_PATH", "REPORT_INTERVAL")) {
      env.remove(key);
    }
    env.remove("JAVA_TOOL_OPTIONS");
    env.remove("JDK_JAVA_OPTIONS");
    env.remove("_JAVA_OPTIONS");
    Instant started = Instant.now();
    Process process = builder.start();
    return new LoadRun(process, summary, dir.resolve("load.log"), started);
  }

  private void read(Path log) {
    try (BufferedReader in =
            new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        Writer out = Files.newBufferedWriter(log, StandardCharsets.UTF_8)) {
      String line;
      while ((line = in.readLine()) != null) {
        Instant now = Instant.now();
        out.write(now + " " + line + System.lineSeparator());
        out.flush();
        LoadInterval.parse(line, now, INTERVAL).ifPresent(intervals::add);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** When the generator was started. */
  public Instant started() {
    return started;
  }

  /** The intervals read so far, in order. */
  public List<LoadInterval> intervals() {
    return List.copyOf(intervals);
  }

  /** Waits for the end of the run and reads the summary. */
  public LoadSummary await(Duration timeout) throws IOException, InterruptedException {
    if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
      throw new IllegalStateException("the load generator did not end within " + timeout);
    }
    reader.join(TimeUnit.SECONDS.toMillis(10));
    if (process.exitValue() != 0 || !Files.isRegularFile(summary)) {
      throw new IllegalStateException(
          String.format(
              Locale.ROOT,
              "the load generator ended with %d (summary %s)",
              process.exitValue(),
              Files.isRegularFile(summary) ? "written" : "missing"));
    }
    return LoadSummary.parse(Files.readString(summary, StandardCharsets.UTF_8));
  }

  /** Stops the generator if it still runs. */
  @Override
  public void close() {
    if (process.isAlive()) {
      process.destroy();
      try {
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
          process.destroyForcibly();
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        process.destroyForcibly();
      }
    }
  }
}
