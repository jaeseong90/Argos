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
import java.util.List;
import java.util.Locale;
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
  private static final String FAILED_METRIC = "\"metric\":\"http_req_failed\"";
  private static final String POINT = "\"type\":\"Point\"";

  /** Distinct failure messages kept as samples (bounded: the raw file may hold millions). */
  static final int MAX_FAILURE_SAMPLES = 20;

  /** Longest failure message kept in a sample. */
  static final int MAX_SAMPLE_LENGTH = 200;

  /**
   * Local socket errors of the load generator at dial (ADR-0009 C-80 H-2): the load machine has no
   * free ephemeral port or cannot bind its local address. Linux {@code EADDRNOTAVAIL} ("cannot
   * assign requested address") and {@code EADDRINUSE} ("address already in use"), Windows {@code
   * WSAEADDRINUSE} (10048, "Only one usage of each socket address ...") and {@code
   * WSAEADDRNOTAVAIL} (10049, "The requested address is not valid in its context"), and the errno
   * names themselves. Lower case; matched against the lower-cased message.
   */
  static final List<String> LOADER_SOCKET_ERRORS =
      List.of(
          "cannot assign requested address",
          "address already in use",
          "only one usage of each socket address",
          "the requested address is not valid in its context",
          "eaddrnotavail",
          "eaddrinuse",
          "wsaeaddrinuse",
          "wsaeaddrnotavail");

  /** Words that place an error at connection set-up on the load machine (Go: dial, connectex). */
  private static final List<String> DIAL_WORDS = List.of("dial", "connect", "bind");

  /**
   * Histograms of one raw file.
   *
   * @param points measured points counted
   * @param otherPhase points of another phase (warm-up, setup) skipped
   * @param discarded points skipped by {@code discardSeconds}
   * @param unreadable points whose value could not be read
   * @param failed {@code http_req_failed} points of value 1 in the measured phase (bench.js counts
   *     them as unexpected outcomes)
   * @param loaderSocketErrors those of {@code failed} whose {@code error} tag is a loader-side
   *     socket error ({@link #isLoaderSocketError})
   * @param failureSamples count per distinct {@code error} tag (at most {@value
   *     #MAX_FAILURE_SAMPLES}, the rest under "(other)")
   */
  record Result(
      LatencyHistogram all,
      Map<String, LatencyHistogram> routes,
      long points,
      long otherPhase,
      long discarded,
      long unreadable,
      String firstTime,
      String lastTime,
      long failed,
      long loaderSocketErrors,
      Map<String, Long> failureSamples) {

    Result(
        LatencyHistogram all,
        Map<String, LatencyHistogram> routes,
        long points,
        long otherPhase,
        long discarded,
        long unreadable,
        String firstTime,
        String lastTime) {
      this(
          all,
          routes,
          points,
          otherPhase,
          discarded,
          unreadable,
          firstTime,
          lastTime,
          0,
          0,
          Map.of());
    }

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
      out.put("failedPoints", failed);
      out.put("loaderSocketErrors", loaderSocketErrors);
      out.put("failureSamples", failureSamples);
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
    Failures failures = new Failures();
    for (String line = reader.readLine(); line != null; line = reader.readLine()) {
      if (line.contains(FAILED_METRIC) && line.contains(POINT)) {
        // Every failed request of the measured phase (the whole k6 run, like bench.js's
        // unexpectedRate): discardSeconds does not apply.
        String phase = tag(line, "phase");
        if ((phase == null || phase.equals("measure")) && number(line, "\"value\":") == 1) {
          failures.add(tagValue(line, "error"));
        }
        continue;
      }
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
    return new Result(
        all,
        routes,
        points,
        otherPhase,
        discarded,
        unreadable,
        firstTime,
        lastTime,
        failures.failed(),
        failures.loaderSocketErrors(),
        failures.samples());
  }

  /**
   * Whether a k6 request error message is a loader-side local socket error at dial (H-2): one of
   * {@link #LOADER_SOCKET_ERRORS} together with a dial, connect or bind step. Everything else
   * (connection refused, reset, timeout, HTTP status) stays an unexpected outcome of the app.
   */
  static boolean isLoaderSocketError(String message) {
    if (message == null || message.isEmpty()) {
      return false;
    }
    String m = message.toLowerCase(Locale.ROOT);
    return LOADER_SOCKET_ERRORS.stream().anyMatch(m::contains)
        && DIAL_WORDS.stream().anyMatch(m::contains);
  }

  /** Failed requests and their classification (bounded samples of distinct messages). */
  static final class Failures {
    private long failed;
    private long loader;
    private final Map<String, Long> samples = new LinkedHashMap<>();

    void add(String error) {
      failed++;
      if (isLoaderSocketError(error)) {
        loader++;
      }
      String key = error == null || error.isEmpty() ? "(no error tag)" : error;
      if (key.length() > MAX_SAMPLE_LENGTH) {
        key = key.substring(0, MAX_SAMPLE_LENGTH) + "...";
      }
      if (!samples.containsKey(key) && samples.size() >= MAX_FAILURE_SAMPLES) {
        key = "(other)";
      }
      samples.merge(key, 1L, Long::sum);
    }

    long failed() {
      return failed;
    }

    long loaderSocketErrors() {
      return loader;
    }

    Map<String, Long> samples() {
      return new LinkedHashMap<>(samples);
    }
  }

  private static final String LOG_FAILED = "Request Failed";
  private static final String LOG_ERROR = "error=\"";

  /**
   * Failed requests from k6's log ({@code level=warning msg="Request Failed" error="..."}), the
   * fallback when the raw output is off ({@code --raw off}). k6 logs every failed request that does
   * not throw; the measured k6 invocation has no warm-up phase, only its setup request.
   */
  static Failures failuresInLog(String log) {
    Failures failures = new Failures();
    for (String line : log.split("\\R", -1)) {
      if (!line.contains(LOG_FAILED)) {
        continue;
      }
      int at = line.indexOf(LOG_ERROR);
      failures.add(at < 0 ? null : unquote(line, at + LOG_ERROR.length()));
    }
    return failures;
  }

  /**
   * The string value of tag {@code name} with JSON escapes resolved (an {@code error} tag may hold
   * quoted URLs), or null.
   */
  static String tagValue(String line, String name) {
    int tags = line.indexOf("\"tags\":{");
    if (tags < 0) {
      return null;
    }
    String key = "\"" + name + "\":\"";
    int at = line.indexOf(key, tags);
    return at < 0 ? null : unquote(line, at + key.length());
  }

  /** The rest of a quoted string starting at {@code from} (after the opening quote), unescaped. */
  private static String unquote(String line, int from) {
    StringBuilder out = new StringBuilder();
    for (int i = from; i < line.length(); i++) {
      char c = line.charAt(i);
      if (c == '"') {
        return out.toString();
      }
      if (c == '\\' && i + 1 < line.length()) {
        char next = line.charAt(++i);
        switch (next) {
          case 'n' -> out.append('\n');
          case 't' -> out.append('\t');
          case 'r' -> out.append('\r');
          case 'u' -> {
            if (i + 4 < line.length()) {
              try {
                out.append((char) Integer.parseInt(line.substring(i + 1, i + 5), 16));
                i += 4;
              } catch (NumberFormatException e) {
                out.append('u');
              }
            }
          }
          default -> out.append(next);
        }
        continue;
      }
      out.append(c);
    }
    return out.toString();
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
