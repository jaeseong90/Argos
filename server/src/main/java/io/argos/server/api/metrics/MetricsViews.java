/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.metrics;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.argos.server.api.openapi.ArgosUntrusted;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** The response types of the instance metrics (14 §4.10). */
public final class MetricsViews {

  private MetricsViews() {}

  /**
   * The series of an instance.
   *
   * @param ts bucket starts, shared by every series
   * @param series one per requested name, in request order
   */
  @Schema(name = "InstanceMetrics")
  public record Metrics(List<Instant> ts, List<Series> series) {}

  /**
   * One series on the grid of {@code ts}.
   *
   * @param name the stored series name with its labels, as requested
   * @param metric the base name
   * @param labels the labels
   * @param kind {@code gauge} or {@code counter}, null when the range has no sample
   * @param unit {@code bytes}, {@code seconds}, {@code ratio}, {@code ms}, or null
   * @param ts bucket starts (the same as the response's {@code ts})
   * @param value gauge: the average of the bucket's samples; counter: the increase in the bucket;
   *     null for a bucket without samples (D-09), never 0
   * @param min gauge: the smallest sample of the bucket, or null
   * @param max gauge: the largest sample of the bucket, or null
   */
  @Schema(name = "MetricSeries")
  public record Series(
      @ArgosUntrusted String name,
      @ArgosUntrusted String metric,
      @ArgosUntrusted Map<String, String> labels,
      @Nullable String kind,
      @Nullable String unit,
      List<Instant> ts,
      List<@Nullable Double> value,
      List<@Nullable Double> min,
      List<@Nullable Double> max) {}

  /**
   * A series an instance reported.
   *
   * @param name the stored name with labels, as the metrics request takes it
   * @param metric the base name
   * @param labels the labels
   * @param kind {@code gauge} or {@code counter}
   * @param unit unit, or null
   * @param lastSeenAt the start of the last hour with samples
   */
  @Schema(name = "MetricName")
  public record Name(
      @ArgosUntrusted String name,
      @ArgosUntrusted String metric,
      @ArgosUntrusted Map<String, String> labels,
      String kind,
      @Nullable String unit,
      @JsonProperty("last_seen_at") Instant lastSeenAt) {}
}
