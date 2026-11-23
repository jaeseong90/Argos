/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert;

import io.argos.server.alert.metric.AlertMetric;
import io.argos.server.alert.metric.ClickHouseProvider;
import io.argos.server.alert.metric.MetricRequest;
import io.argos.server.alert.metric.MetricValue;
import io.argos.server.alert.rule.RuleCodec;
import io.argos.server.alert.rule.RuleSpec;
import io.argos.server.alert.rule.RuleSpec.TargetType;
import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import io.argos.server.storage.query.QueryExecutor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import tools.jackson.databind.JsonNode;

/**
 * The preview of a rule (14 §4.13 {@code POST /alerts/rules/preview}, F-S06): the rule's metric
 * over the last days replayed through {@link ClickHouseProvider} (the minute rollups the engine
 * reads for long windows) and the firings the condition would have caused.
 *
 * <p>The range is cut into consecutive buckets of {@code step}: the rule window rounded up to whole
 * minutes, at least {@code days × 1440 / MAX_POINTS} minutes, so a preview costs at most {@value
 * #MAX_POINTS} rollup queries. Each bucket's value is the metric over that bucket (a window shorter
 * than the step is therefore smoothed). A bucket breaches when it has a value, a request metric has
 * at least {@code min_samples} requests and the value meets the warn threshold (or the critical one
 * without warn); a run of breaching buckets fires when it lasts at least {@code for}. The firing
 * starts {@code for} after the run starts and ends with the run.
 *
 * <p>Metrics that the rollups do not hold (registry, server self metrics, events, batch elapsed
 * time) have no preview: {@code 400 invalid_request} with {@code metric/preview_unsupported}.
 */
public class RulePreviewService {

  /** Buckets (and rollup queries) of a preview at most. */
  public static final int MAX_POINTS = 336;

  /** Days a preview covers at most (14 §4.13 {@code days: 7}). */
  public static final int MAX_DAYS = 7;

  /** Time a preview may take before it answers {@code 504 query_timeout}. */
  public static final Duration DEADLINE = Duration.ofSeconds(25);

  private final Supplier<QueryExecutor> executor;
  private final Clock clock;

  public RulePreviewService(Supplier<QueryExecutor> executor, Clock clock) {
    this.executor = executor;
    this.clock = clock;
  }

  /**
   * One would-be firing.
   *
   * @param start when it would have fired
   * @param end when its condition stopped holding
   * @param peakValue the worst value of the run
   */
  public record Firing(Instant start, Instant end, double peakValue) {}

  /**
   * A preview.
   *
   * @param from range start
   * @param to range end
   * @param step bucket length
   * @param firings the would-be firings, oldest first
   * @param maxDurationS seconds of the longest firing (0 without firings)
   * @param ts bucket starts
   * @param values the metric per bucket, null without a value
   */
  public record Preview(
      Instant from,
      Instant to,
      Duration step,
      List<Firing> firings,
      long maxDurationS,
      List<Instant> ts,
      List<Double> values) {}

  /**
   * Replays a rule (it need not be saved).
   *
   * @param rule the rule JSON (validated like a save, without the channel and application checks)
   * @param days days back from now, 1 to 7
   */
  public Preview preview(JsonNode rule, int days) {
    if (days < 1 || days > MAX_DAYS) {
      throw new ApiException(ErrorCode.INVALID_REQUEST, "days is 1 to 7")
          .detail("days", "out_of_range");
    }
    RuleSpec spec = RuleCodec.parse(rule);
    AlertMetric metric = AlertMetric.of(spec.metric());
    if (metric == null
        || !metric.rollupCapable()
        || metric == AlertMetric.BATCH_ELAPSED_SECONDS
        || spec.target().appId() == null) {
      throw new ApiException(ErrorCode.INVALID_REQUEST, "This rule has no preview")
          .detail("metric", "preview_unsupported");
    }
    TargetType type = spec.target().type();
    String targetId = null;
    if (type != TargetType.APP) {
      if (spec.target().ids().isEmpty()) {
        throw new ApiException(ErrorCode.INVALID_REQUEST, "The preview needs one target id")
            .detail("target.ids", "required");
      }
      targetId = spec.target().ids().get(0);
    }
    QueryExecutor ex = executor.get();
    if (ex == null) {
      throw new ApiException(ErrorCode.STORAGE_UNAVAILABLE);
    }
    long windowMinutes = Math.max(1, (spec.window().toSeconds() + 59) / 60);
    long minPerPoint = ((long) days * 1440 + MAX_POINTS - 1) / MAX_POINTS;
    long stepMinutes = Math.max(windowMinutes, minPerPoint);
    long stepMs = stepMinutes * 60_000;
    long nowMs = clock.millis();
    // Up to the end of the current minute: the rollups hold its rows so far.
    long endMs = (Math.floorDiv(nowMs, 60_000) + 1) * 60_000;
    long points = ((long) days * 86_400_000L) / stepMs;
    long startMs = endMs - points * stepMs;
    ClickHouseProvider provider = new ClickHouseProvider(ex);
    long deadline = System.nanoTime() + DEADLINE.toNanos();
    List<Instant> ts = new ArrayList<>((int) points);
    List<Double> values = new ArrayList<>((int) points);
    List<Boolean> breach = new ArrayList<>((int) points);
    Double threshold =
        spec.condition().warn() != null ? spec.condition().warn() : spec.condition().critical();
    for (long b = startMs; b < endMs; b += stepMs) {
      if (System.nanoTime() > deadline) {
        throw new ApiException(ErrorCode.QUERY_TIMEOUT);
      }
      // The provider reads [toMinute − window, toMinute) with toMinute the minute after nowMs.
      MetricValue v =
          provider.read(
              new MetricRequest(
                  metric,
                  spec.target().appId(),
                  spec.kind(),
                  type,
                  targetId,
                  Duration.ofMillis(stepMs),
                  b + stepMs - 60_000,
                  Set.of()));
      ts.add(Instant.ofEpochMilli(b));
      Double value = v.present() ? v.value() : null;
      values.add(value);
      boolean enough = !metric.request() || v.samples() >= spec.minSamples();
      breach.add(
          value != null
              && enough
              && threshold != null
              && spec.condition().operator().test(value, threshold));
    }
    if (provider.failures() > 0) {
      throw new ApiException(ErrorCode.STORAGE_UNAVAILABLE);
    }
    List<Firing> firings =
        firings(ts, values, breach, stepMs, spec.forDuration(), spec.condition().operator());
    long maxDuration = 0;
    for (Firing f : firings) {
      maxDuration = Math.max(maxDuration, Duration.between(f.start(), f.end()).toSeconds());
    }
    return new Preview(
        Instant.ofEpochMilli(startMs),
        Instant.ofEpochMilli(endMs),
        Duration.ofMillis(stepMs),
        List.copyOf(firings),
        maxDuration,
        List.copyOf(ts),
        java.util.Collections.unmodifiableList(values));
  }

  /** The runs of breaching buckets that last at least {@code forDuration}. */
  static List<Firing> firings(
      List<Instant> ts,
      List<Double> values,
      List<Boolean> breach,
      long stepMs,
      Duration forDuration,
      RuleSpec.Operator operator) {
    boolean higherIsWorse = operator.test(2, 1) || !operator.test(1, 2);
    List<Firing> out = new ArrayList<>();
    int i = 0;
    int n = ts.size();
    while (i < n) {
      if (!breach.get(i)) {
        i++;
        continue;
      }
      int j = i;
      double peak = values.get(i);
      while (j + 1 < n && breach.get(j + 1)) {
        j++;
        double v = values.get(j);
        peak = higherIsWorse ? Math.max(peak, v) : Math.min(peak, v);
      }
      Instant runStart = ts.get(i);
      Instant runEnd = ts.get(j).plusMillis(stepMs);
      if (Duration.between(runStart, runEnd).compareTo(forDuration) >= 0) {
        out.add(new Firing(runStart.plus(forDuration), runEnd, peak));
      }
      i = j + 1;
    }
    return out;
  }
}
