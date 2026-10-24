/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.load;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The JSON summary and the progress lines of a generator run. */
final class Summary {

  static final double[] PERCENTILES = {50, 95, 99, 99.9};

  private Summary() {}

  static Map<String, Object> of(
      LoadConfig config,
      String version,
      String startedAt,
      FixedRateGenerator.Result result,
      LoadStats.Snapshot s,
      Map<String, Object> machine) {
    return of(config, version, startedAt, result, s, null, machine);
  }

  /**
   * The summary of the measured window {@code s}; {@code warmup} (nullable) adds what happened
   * during the warm-up, outside every rate and percentile.
   */
  static Map<String, Object> of(
      LoadConfig config,
      String version,
      String startedAt,
      FixedRateGenerator.Result result,
      LoadStats.Snapshot s,
      LoadStats.Snapshot warmup,
      Map<String, Object> machine) {
    double window = result.windowNanos() / 1e9;
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("tool", "argos-demo-load");
    root.put("version", version);
    root.put("startedAt", startedAt);

    Map<String, Object> settings = new LinkedHashMap<>();
    settings.put("targetUrl", config.target().toString());
    settings.put("rate", config.rate());
    settings.put("mix", config.mix().name());
    settings.put(
        "durationSeconds", config.duration() == null ? null : Durations.seconds(config.duration()));
    settings.put("warmupSeconds", Durations.seconds(config.warmup()));
    settings.put("maxInFlight", config.maxInFlight());
    settings.put("requestTimeoutMs", config.requestTimeout().toMillis());
    settings.put("connectTimeoutMs", config.connectTimeout().toMillis());
    root.put("config", settings);

    Map<String, Object> requests = new LinkedHashMap<>();
    requests.put("windowSeconds", window);
    requests.put("due", result.dueRequests());
    requests.put("sent", s.sent());
    requests.put("dropped", s.dropped());
    requests.put("answered", s.completed());
    requests.put("expected", s.expected());
    requests.put("unexpected", s.unexpected());
    requests.put("failedWithoutAnswer", s.failures());
    requests.put("status5xx", s.status5xx());
    requests.put("inFlightAtEnd", result.inFlightAtEnd());
    requests.put("targetRate", config.rate());
    requests.put("achievedRate", window > 0 ? s.sent() / window : Double.NaN);
    requests.put("answeredRate", window > 0 ? s.completed() / window : Double.NaN);
    // Share of due requests without the expected outcome: wrong status, no answer, dropped, or
    // still unanswered after the drain.
    requests.put(
        "unexpectedRate",
        ratio(s.unexpected() + s.dropped() + result.inFlightAtEnd(), s.scheduled()));
    requests.put("http5xxRate", ratio(s.status5xx() + s.failures(), s.finished()));
    requests.put("droppedRate", ratio(s.dropped(), s.scheduled()));
    root.put("requests", requests);

    if (warmup != null && !config.warmup().isZero()) {
      Map<String, Object> w = new LinkedHashMap<>();
      w.put("sent", warmup.sent());
      w.put("dropped", warmup.dropped());
      w.put("unexpectedAnswersOrFailures", warmup.unexpected());
      w.put("latencyMs", latency(warmup.latency()));
      root.put("warmupExcluded", w);
    }

    root.put("latencyMs", latency(s.latency()));
    root.put("scheduleLagMs", latency(s.lag()));

    List<Object> routes = new ArrayList<>();
    for (LoadStats.RouteSnapshot r : s.routes()) {
      Map<String, Object> route = new LinkedHashMap<>();
      route.put("route", r.route().key());
      route.put("service", r.route().service());
      route.put("weightPercent", r.route().weight());
      route.put("expectedStatus", r.route().expectedStatus());
      route.put("sent", r.sent());
      route.put("sharePercent", s.sent() == 0 ? Double.NaN : 100.0 * r.sent() / s.sent());
      route.put("dropped", r.dropped());
      route.put("expected", r.expected());
      route.put("unexpectedStatus", r.unexpectedStatus());
      route.put("timeouts", r.timeouts());
      route.put("connectFailures", r.connectFails());
      route.put("ioErrors", r.ioErrors());
      Map<String, Object> statuses = new LinkedHashMap<>();
      r.statuses().forEach((status, n) -> statuses.put(Integer.toString(status), n));
      route.put("statuses", statuses);
      route.put("latencyMs", latency(r.latency()));
      routes.add(route);
    }
    root.put("routes", routes);
    root.put("machine", machine);
    return root;
  }

  static Map<String, Object> latency(LatencyHistogram.Snapshot h) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("count", h.count());
    m.put("mean", h.meanMillis());
    for (double p : PERCENTILES) {
      m.put("p" + label(p), h.percentileMillis(p));
    }
    m.put("max", h.maxMillis());
    return m;
  }

  /** One progress line: interval rate and outcomes, interval percentiles. */
  static String progressLine(
      double elapsedSeconds,
      double intervalSeconds,
      LoadStats.Snapshot now,
      LoadStats.Snapshot before,
      int inFlight) {
    long sent = now.sent() - before.sent();
    LatencyHistogram.Snapshot interval = now.latency().minus(before.latency());
    return String.format(
        Locale.ROOT,
        "[demo-load] t=%.0fs rate=%.1f/s sent=%d expected=%d unexpected=%d 5xx=%d dropped=%d"
            + " inFlight=%d p50=%.1fms p95=%.1fms p99=%.1fms",
        elapsedSeconds,
        intervalSeconds > 0 ? sent / intervalSeconds : Double.NaN,
        sent,
        now.expected() - before.expected(),
        now.unexpected() - before.unexpected(),
        now.status5xx() - before.status5xx(),
        now.dropped() - before.dropped(),
        inFlight,
        interval.percentileMillis(50),
        interval.percentileMillis(95),
        interval.percentileMillis(99));
  }

  /** The final line: whole-run rate, unexpected share and percentiles. */
  static String finalLine(Map<String, Object> summary) {
    Map<?, ?> requests = (Map<?, ?>) summary.get("requests");
    Map<?, ?> latency = (Map<?, ?>) summary.get("latencyMs");
    return String.format(
        Locale.ROOT,
        "[demo-load] done: window=%ss sent=%s achieved=%s/s unexpectedRate=%s http5xxRate=%s"
            + " dropped=%s p50=%sms p95=%sms p99=%sms p99.9=%sms max=%sms",
        text(requests.get("windowSeconds")),
        text(requests.get("sent")),
        text(requests.get("achievedRate")),
        text(requests.get("unexpectedRate")),
        text(requests.get("http5xxRate")),
        text(requests.get("dropped")),
        text(latency.get("p50")),
        text(latency.get("p95")),
        text(latency.get("p99")),
        text(latency.get("p99.9")),
        text(latency.get("max")));
  }

  private static String text(Object value) {
    if (value instanceof Double d) {
      return Double.isNaN(d) ? "n/a" : String.format(Locale.ROOT, "%.4g", d);
    }
    return String.valueOf(value);
  }

  private static String label(double p) {
    return p == Math.rint(p) ? Long.toString((long) p) : Double.toString(p);
  }

  private static double ratio(long part, long whole) {
    return whole == 0 ? Double.NaN : (double) part / whole;
  }
}
