/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.load;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Settings of the demo load generator. Environment variables (17 §3.2 compose: {@code TARGET_URL},
 * {@code RATE}, {@code MIX}) and command-line options; an option wins over its variable.
 *
 * @param target base URL of the app (a WAR context path may follow the port)
 * @param rate requests per second (fixed arrival rate)
 * @param mix request mix
 * @param duration how long to send after the warm-up; {@code null} runs until the process is
 *     stopped
 * @param warmup sending at the same rate and mix before the measured window, excluded from the
 *     summary (zero: none)
 * @param maxInFlight requests allowed in flight; due requests beyond it are dropped and counted
 * @param requestTimeout per-request timeout
 * @param connectTimeout TCP connect timeout
 * @param reportInterval period of the progress line; zero disables it
 * @param summaryPath where the JSON summary goes at the end; {@code null} for none
 * @param httpThreads completion threads of the HTTP client
 * @param maxUnexpectedRate exit code 1 when the share of unexpected outcomes is higher; NaN: no
 *     gate
 */
record LoadConfig(
    URI target,
    double rate,
    Mix mix,
    Duration duration,
    Duration warmup,
    int maxInFlight,
    Duration requestTimeout,
    Duration connectTimeout,
    Duration reportInterval,
    Path summaryPath,
    int httpThreads,
    double maxUnexpectedRate) {

  static final String DEFAULT_TARGET = "http://localhost:8080";
  static final double DEFAULT_RATE = 50;
  static final double MAX_RATE = 20_000;

  static final String USAGE =
      """
      usage: java -jar load.jar [--target-url URL] [--rate N] [--mix default|demo|bench] [--duration D]
                                [--warmup D] [--max-in-flight N] [--request-timeout D]
                                [--connect-timeout D] [--report-interval D] [--summary FILE]
                                [--http-threads N] [--max-unexpected-rate R]
      Environment (the option wins): TARGET_URL (http://localhost:8080), RATE (50), MIX (default),
      DURATION (unset: run until stopped), WARMUP (0s; excluded from the summary),
      MAX_IN_FLIGHT (max(64, rate) up to 4096), REQUEST_TIMEOUT (10s), CONNECT_TIMEOUT (3s),
      REPORT_INTERVAL (10s, 0 disables), SUMMARY_PATH, HTTP_THREADS (4),
      MAX_UNEXPECTED_RATE (unset: no gate, e.g. 0.01).
      Durations: 500ms, 30s, 1m30s, 2h. MIX=default: the docs/17 §3.9 shares over the shop apps,
      MIX=demo: the docs/17 §3.9 demo profile over argos-demo-app (order-api -> payment-api),
      MIX=bench: the benchmark mix of docs/18 §7.2 (same as sample-apps/load/bench.js).
      """;

  private static final Map<String, String> OPTIONS = options();

  private static Map<String, String> options() {
    Map<String, String> m = new LinkedHashMap<>();
    m.put("--target-url", "TARGET_URL");
    m.put("--rate", "RATE");
    m.put("--mix", "MIX");
    m.put("--duration", "DURATION");
    m.put("--warmup", "WARMUP");
    m.put("--max-in-flight", "MAX_IN_FLIGHT");
    m.put("--request-timeout", "REQUEST_TIMEOUT");
    m.put("--connect-timeout", "CONNECT_TIMEOUT");
    m.put("--report-interval", "REPORT_INTERVAL");
    m.put("--summary", "SUMMARY_PATH");
    m.put("--http-threads", "HTTP_THREADS");
    m.put("--max-unexpected-rate", "MAX_UNEXPECTED_RATE");
    return m;
  }

  /** Reads the environment, then the options. Throws IllegalArgumentException with the usage. */
  static LoadConfig from(Map<String, String> environment, String... args) {
    Map<String, String> values = new LinkedHashMap<>();
    for (String variable : OPTIONS.values()) {
      String value = environment.get(variable);
      if (value != null && !value.isBlank()) {
        values.put(variable, value.trim());
      }
    }
    for (int i = 0; i < args.length; i += 2) {
      String variable = OPTIONS.get(args[i]);
      if (variable == null) {
        throw new IllegalArgumentException("unknown option " + args[i] + "\n" + USAGE);
      }
      if (i + 1 >= args.length) {
        throw new IllegalArgumentException(args[i] + " needs a value\n" + USAGE);
      }
      values.put(variable, args[i + 1].trim());
    }
    try {
      return build(values);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(e.getMessage() + "\n" + USAGE, e);
    }
  }

  private static LoadConfig build(Map<String, String> v) {
    URI target = target(v.getOrDefault("TARGET_URL", DEFAULT_TARGET));
    double rate = number(v, "RATE", DEFAULT_RATE);
    if (!(rate > 0 && rate <= MAX_RATE)) {
      throw new IllegalArgumentException("RATE must be > 0 and <= " + (long) MAX_RATE);
    }
    Mix mix = Mix.byName(v.get("MIX"));
    Duration duration = v.containsKey("DURATION") ? Durations.parse(v.get("DURATION")) : null;
    if (duration != null && duration.isZero()) {
      duration = null;
    }
    Duration warmup = v.containsKey("WARMUP") ? Durations.parse(v.get("WARMUP")) : Duration.ZERO;
    int defaultInFlight = (int) Math.min(4096, Math.max(64, Math.ceil(rate)));
    int maxInFlight = (int) number(v, "MAX_IN_FLIGHT", defaultInFlight);
    if (maxInFlight < 1 || maxInFlight > 100_000) {
      throw new IllegalArgumentException("MAX_IN_FLIGHT must be between 1 and 100000");
    }
    Duration requestTimeout = positive(v, "REQUEST_TIMEOUT", Duration.ofSeconds(10));
    Duration connectTimeout = positive(v, "CONNECT_TIMEOUT", Duration.ofSeconds(3));
    Duration reportInterval =
        v.containsKey("REPORT_INTERVAL")
            ? Durations.parse(v.get("REPORT_INTERVAL"))
            : Duration.ofSeconds(10);
    Path summary = v.containsKey("SUMMARY_PATH") ? Path.of(v.get("SUMMARY_PATH")) : null;
    int threads = (int) number(v, "HTTP_THREADS", 4);
    if (threads < 1 || threads > 64) {
      throw new IllegalArgumentException("HTTP_THREADS must be between 1 and 64");
    }
    double maxUnexpected = number(v, "MAX_UNEXPECTED_RATE", Double.NaN);
    if (!Double.isNaN(maxUnexpected) && (maxUnexpected < 0 || maxUnexpected > 1)) {
      throw new IllegalArgumentException("MAX_UNEXPECTED_RATE must be between 0 and 1");
    }
    return new LoadConfig(
        target,
        rate,
        mix,
        duration,
        warmup,
        maxInFlight,
        requestTimeout,
        connectTimeout,
        reportInterval,
        summary,
        threads,
        maxUnexpected);
  }

  private static URI target(String text) {
    URI uri;
    try {
      uri = URI.create(text);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("TARGET_URL is not a URL: " + text, e);
    }
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    if (!(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null) {
      throw new IllegalArgumentException(
          "TARGET_URL must be http(s)://host[:port][/path]: " + text);
    }
    if (uri.getQuery() != null || uri.getFragment() != null) {
      throw new IllegalArgumentException("TARGET_URL must not have a query or fragment: " + text);
    }
    return uri;
  }

  private static double number(Map<String, String> v, String name, double fallback) {
    String text = v.get(name);
    if (text == null) {
      return fallback;
    }
    try {
      return Double.parseDouble(text);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(name + " is not a number: " + text, e);
    }
  }

  private static Duration positive(Map<String, String> v, String name, Duration fallback) {
    Duration value = v.containsKey(name) ? Durations.parse(v.get(name)) : fallback;
    if (value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(name + " must be positive");
    }
    return value;
  }
}
