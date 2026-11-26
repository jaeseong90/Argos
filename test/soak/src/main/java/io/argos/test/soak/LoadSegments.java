/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.soak;

import io.argos.test.soak.SoakSamples.Segment;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The constant load of the soak run (18 §11.2 "500 TPS 상시"): the sample-apps/load generator ({@code
 * load.jar}, open model, fixed arrival rate) run back to back, one run per hourly point, so that
 * each run's JSON summary gives that point's p99 ({@link Segment}). The first run carries the
 * warm-up ({@code --warmup}, excluded from its summary). The last run ends at the deadline; no run
 * is ever killed (a killed generator on Windows writes no summary). Between two runs the generator
 * JVM restarts (about a second without load).
 */
final class LoadSegments implements Runnable {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** What a summary says. */
  record Summary(
      double p99Millis, long sent, double unexpectedRate, double http5xxRate, double errorShare) {}

  private final Path loadJar;
  private final String targetUrl;
  private final SoakOptions options;
  private final Duration segment;
  private final Duration warmUp;
  private final Instant deadline;
  private final Path dir;
  private final SoakSamples samples;
  private final RunFacts facts;
  private final AtomicLong sent = new AtomicLong();
  private volatile double errorShare = Double.NaN;
  private volatile Process current;
  private volatile boolean stopped;

  LoadSegments(
      Path loadJar,
      String targetUrl,
      SoakOptions options,
      Duration segment,
      Duration warmUp,
      Instant deadline,
      Path dir,
      SoakSamples samples,
      RunFacts facts) {
    this.loadJar = loadJar;
    this.targetUrl = targetUrl;
    this.options = options;
    this.segment = segment;
    this.warmUp = warmUp;
    this.deadline = deadline;
    this.dir = dir;
    this.samples = samples;
    this.facts = facts;
  }

  /** Requests sent by the finished runs. */
  long sent() {
    return sent.get();
  }

  /** The mix's intended error share (routes whose expected status is 5xx), NaN before a summary. */
  double errorShare() {
    return errorShare;
  }

  /** Stops after the current run (and ends it at once when the run is shutting down). */
  void stop() {
    stopped = true;
    Process p = current;
    if (p != null) {
      p.destroy();
    }
  }

  @Override
  public void run() {
    int index = 0;
    boolean first = true;
    while (!stopped) {
      Instant start = Instant.now();
      Duration left = Duration.between(first ? start.plus(warmUp) : start, deadline);
      if (left.toSeconds() < 5) {
        break;
      }
      Duration run = left.compareTo(segment) < 0 ? left : segment;
      Path summary = dir.resolve(String.format("segment-%03d.json", index));
      int exit = runOnce(first ? warmUp : Duration.ZERO, run, summary, index);
      if (stopped) {
        break;
      }
      Summary s = read(summary);
      double seconds = samples.elapsed(first ? start.plus(warmUp) : start);
      if (s == null) {
        samples.add(new Segment(index, seconds, Double.NaN, 0, Double.NaN, Double.NaN, exit));
        facts.note("Load segment " + index + " wrote no readable summary (exit " + exit + ").");
      } else {
        sent.addAndGet(s.sent());
        errorShare = s.errorShare();
        samples.add(
            new Segment(
                index,
                seconds,
                s.p99Millis(),
                s.sent(),
                s.unexpectedRate(),
                s.http5xxRate(),
                exit));
      }
      index++;
      first = false;
    }
  }

  private int runOnce(Duration warm, Duration run, Path summary, int index) {
    List<String> command = new ArrayList<>();
    command.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
    command.add("-Xmx256m");
    command.add("-jar");
    command.add(loadJar.toAbsolutePath().toString());
    command.add("--target-url");
    command.add(targetUrl);
    command.add("--mix");
    command.add(options.mix());
    command.add("--rate");
    command.add(Integer.toString(options.tps()));
    if (!warm.isZero()) {
      command.add("--warmup");
      command.add(warm.toMillis() + "ms");
    }
    command.add("--duration");
    command.add(run.toMillis() + "ms");
    command.add("--report-interval");
    command.add("0");
    command.add("--summary");
    command.add(summary.toAbsolutePath().toString());
    try {
      Files.createDirectories(dir);
      Process p =
          new ProcessBuilder(command)
              .directory(dir.toFile())
              .redirectErrorStream(true)
              .redirectOutput(
                  ProcessBuilder.Redirect.appendTo(dir.resolve("load-generator.log").toFile()))
              .start();
      current = p;
      long budget = warm.plus(run).plusMinutes(2).toMillis();
      if (!p.waitFor(budget, TimeUnit.MILLISECONDS)) {
        p.destroyForcibly();
        facts.note("Load segment " + index + " did not end in time and was killed.");
        return -1;
      }
      return p.exitValue();
    } catch (IOException e) {
      facts.note("Load segment " + index + " could not start: " + e);
      return -1;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      stopped = true;
      return -1;
    } finally {
      current = null;
    }
  }

  /** The summary of a run, or null when there is none. */
  static Summary read(Path file) {
    if (!Files.isRegularFile(file)) {
      return null;
    }
    try {
      return parse(Files.readString(file, StandardCharsets.UTF_8));
    } catch (IOException e) {
      return null;
    }
  }

  /** Parses the generator's summary JSON (sample-apps/load {@code Summary}). */
  static Summary parse(String json) {
    JsonNode root;
    try {
      root = JSON.readTree(json);
    } catch (JacksonException e) {
      return null;
    }
    JsonNode requests = root.path("requests");
    JsonNode latency = root.path("latencyMs");
    if (!requests.isObject() || !latency.isObject()) {
      return null;
    }
    double share = 0;
    for (JsonNode route : root.path("routes")) {
      if (route.path("expectedStatus").asInt(0) >= 500) {
        share += route.path("weightPercent").asDouble(0) / 100.0;
      }
    }
    return new Summary(
        number(latency.path("p99")),
        requests.path("sent").asLong(0),
        number(requests.path("unexpectedRate")),
        number(requests.path("http5xxRate")),
        share);
  }

  private static double number(JsonNode n) {
    return n.isNumber() ? n.asDouble() : Double.NaN;
  }
}
