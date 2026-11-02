/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.bench;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;

/**
 * Reads k6's raw output ({@code k6 run --out json=<file>[.gz]}, one JSON object per line) and
 * re-aggregates the per-request latencies ({@code http_req_duration} points) into {@link
 * LatencyHistogram}s: one for the whole mix and one per {@code route} tag of bench.js (18 §7.2).
 *
 * <p>Only points of the measured phase count: bench.js tags every request with {@code
 * phase=warmup|measure} (and its setup request with {@code setup}), and {@code discardSeconds}
 * additionally drops the points of the first seconds after the first measured point (18 §7.2: the
 * first 3 minutes of a 13-minute run are discarded; the harness runs the warm-up as its own k6
 * invocation, so its measured runs use 0). Lines are scanned with string searches, not parsed as
 * JSON: a 10-minute window at 1,000/s has about 8 million lines.
 */
final class K6Raw {
  private K6Raw() {}

  /** Name of the raw file the harness asks k6 to write next to the summary. */
  static final String FILE_NAME = "k6-raw.json.gz";

  private static final String METRIC = "\"metric\":\"http_req_duration\"";
  private static final String POINT = "\"type\":\"Point\"";

  /**
   * Histograms of one raw file.
   *
   * @param points measured points counted
   * @param otherPhase points of another phase (warm-up, setup) skipped
   * @param discarded points skipped by {@code discardSeconds}
   * @param unreadable points whose value could not be read
   */
  record Result(
      LatencyHistogram all,
      Map<String, LatencyHistogram> routes,
      long points,
      long otherPhase,
      long discarded,
      long unreadable,
      String firstTime,
      String lastTime) {

    /** JSON form written as {@code latency-hdr.json} next to the raw file. */
    Map<String, Object> toJson(String source, double discardSeconds) {
      Map<String, Object> out = new LinkedHashMap<>();
      out.put("source", source);
      out.put("metric", "http_req_duration");
      out.put("phase", "measure");
      out.put("discardSeconds", discardSeconds);
      out.put("points", points);
      out.put("otherPhasePoints", otherPhase);
      out.put("discardedPoints", discarded);
      out.put("unreadablePoints", unreadable);
      out.put("firstTime", firstTime);
      out.put("lastTime", lastTime);
      out.put("all", all.toJson());
      Map<String, Object> r = new LinkedHashMap<>();
      routes.forEach((k, v) -> r.put(k, v.toJson()));
      out.put("routes", r);
      return out;
    }
  }

  /** Reads {@code file} (gzip when its name ends with .gz or its first bytes say so). */
  static Result read(Path file, double discardSeconds) throws IOException {
    try (InputStream raw = new BufferedInputStream(Files.newInputStream(file), 1 << 16)) {
      raw.mark(2);
      int b0 = raw.read();
      int b1 = raw.read();
      raw.reset();
      InputStream in = b0 == 0x1f && b1 == 0x8b ? new GZIPInputStream(raw, 1 << 16) : raw;
      return parse(FileTools.utf8Reader(in), discardSeconds);
    }
  }

  static Result parse(BufferedReader reader, double discardSeconds) throws IOException {
    LatencyHistogram all = new LatencyHistogram();
    Map<String, LatencyHistogram> routes = new TreeMap<>();
    long points = 0;
    long otherPhase = 0;
    long discarded = 0;
    long unreadable = 0;
    Instant start = null;
    String firstTime = null;
    String lastTime = null;
    for (String line = reader.readLine(); line != null; line = reader.readLine()) {
      if (!line.contains(METRIC) || !line.contains(POINT)) {
        continue;
      }
      String phase = tag(line, "phase");
      if (phase != null && !phase.equals("measure")) {
        otherPhase++;
        continue;
      }
      double value = number(line, "\"value\":");
      if (Double.isNaN(value)) {
        unreadable++;
        continue;
      }
      String time = string(line, "\"time\":\"");
      if (discardSeconds > 0) {
        Instant at = instant(time);
        if (at != null) {
          if (start == null) {
            start = at;
          }
          if (at.isBefore(start.plusNanos((long) (discardSeconds * 1e9)))) {
            discarded++;
            continue;
          }
        }
      }
      if (firstTime == null) {
        firstTime = time;
      }
      lastTime = time;
      points++;
      all.recordMillis(value);
      String route = tag(line, "route");
      if (route != null) {
        routes.computeIfAbsent(route, k -> new LatencyHistogram()).recordMillis(value);
      }
    }
    return new Result(all, routes, points, otherPhase, discarded, unreadable, firstTime, lastTime);
  }

  /** The string value of tag {@code name} in a point line, or null. */
  static String tag(String line, String name) {
    int tags = line.indexOf("\"tags\":{");
    if (tags < 0) {
      return null;
    }
    String key = "\"" + name + "\":\"";
    int at = line.indexOf(key, tags);
    if (at < 0) {
      return null;
    }
    int from = at + key.length();
    int to = line.indexOf('"', from);
    return to < 0 ? null : line.substring(from, to);
  }

  private static String string(String line, String key) {
    int at = line.indexOf(key);
    if (at < 0) {
      return null;
    }
    int from = at + key.length();
    int to = line.indexOf('"', from);
    return to < 0 ? null : line.substring(from, to);
  }

  /** The number after {@code key} (k6 writes plain decimals), NaN when absent or malformed. */
  static double number(String line, String key) {
    int at = line.indexOf(key);
    if (at < 0) {
      return Double.NaN;
    }
    int from = at + key.length();
    int to = from;
    while (to < line.length() && "+-0123456789.eE".indexOf(line.charAt(to)) >= 0) {
      to++;
    }
    try {
      return Double.parseDouble(line.substring(from, to));
    } catch (NumberFormatException e) {
      return Double.NaN;
    }
  }

  private static Instant instant(String time) {
    if (time == null) {
      return null;
    }
    try {
      return OffsetDateTime.parse(time).toInstant();
    } catch (DateTimeParseException e) {
      return null;
    }
  }
}
