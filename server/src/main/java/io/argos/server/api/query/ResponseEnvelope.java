/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.query;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.argos.server.web.RequestIdFilter;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.MDC;

/**
 * The response envelope of the query API (14 §2.5, DSN-14-04, 02 §7, C-6): {@code {"data": ...,
 * "meta": {...}}}, the {@code meta} fields in the order of 14 §2.5. {@link Meta} is the only {@code
 * meta} of the API: {@code io.argos.server.api.Envelope} (authentication, setup, user
 * administration) carries the same record with {@code request_id}, {@code generated_at} and {@code
 * truncated} only.
 *
 * <p><b>Absent versus null.</b> A {@code meta} field is either present with a value or absent; it
 * is never {@code null}, with one exception:
 *
 * <ul>
 *   <li>always present: {@code request_id}, {@code generated_at};
 *   <li>present on every endpoint with a time range: {@code from}, {@code to}, {@code source},
 *       {@code truncated}, {@code retention_exceeded} ({@link Builder#plan(SourcePlan)} sets them;
 *       {@code source} on time-series and statistics endpoints);
 *   <li>present where the endpoint has them: {@code shown} and {@code total} (XLog, search), {@code
 *       sampling} (XLog: {@code applied}, plus {@code rule} when applied), {@code outages}
 *       (dashboard, XLog, collection health; an empty array when there is none, D-18, C-5), {@code
 *       slow_threshold_ms} (XLog, 14 §4.5);
 *   <li>{@code next_cursor}: present on paged lists, {@code null} on the last page (14 §2.5 shows
 *       {@code "next_cursor": null}); absent on endpoints that do not page.
 * </ul>
 *
 * Times are ISO-8601 UTC with milliseconds (14 §2.4).
 *
 * @param data the payload
 * @param meta the metadata
 * @param <T> the payload type
 */
public record ResponseEnvelope<T>(T data, Meta meta) {

  public ResponseEnvelope {
    Objects.requireNonNull(meta, "meta");
  }

  /** A builder with the request id of the current request and the current time. */
  public static <T> Builder<T> builder(T data) {
    return new Builder<>(data);
  }

  /** Only {@code request_id} and {@code generated_at}: an endpoint without range or list meta. */
  public static <T> ResponseEnvelope<T> of(T data) {
    return builder(data).build();
  }

  /**
   * The metadata (14 §2.5).
   *
   * @param requestId the request id (also in {@code X-Request-Id} and the logs)
   * @param generatedAt when the server built the response
   * @param from range start as requested
   * @param to range end (exclusive) as requested
   * @param source {@code raw}, {@code 1m}, {@code 1h} or {@code mixed}
   * @param truncated whether a list was cut at its row limit
   * @param shown points or rows in {@code data}
   * @param total points or rows in the range ({@code count()})
   * @param sampling the XLog sampling of A-5
   * @param nextCursor the cursor of the next page; {@code Optional.empty()} is written as {@code
   *     null} (last page), a null reference leaves the field out (no paging)
   * @param retentionExceeded whether part of the range is older than the raw retention (D-03)
   * @param outages collection outages in the range (D-18)
   * @param slowThresholdMs the slow threshold of the XLog status (14 §4.5)
   */
  @JsonPropertyOrder({
    "request_id",
    "generated_at",
    "from",
    "to",
    "source",
    "truncated",
    "shown",
    "total",
    "sampling",
    "next_cursor",
    "retention_exceeded",
    "outages",
    "slow_threshold_ms"
  })
  @JsonInclude(JsonInclude.Include.NON_NULL)
  @Schema(
      name = "QueryMeta",
      description =
          "The meta of every response (14 §2.5): request_id and generated_at always, the other"
              + " fields where the endpoint has them")
  public record Meta(
      @JsonProperty("request_id") @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
          String requestId,
      @JsonProperty("generated_at") @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
          Instant generatedAt,
      @JsonProperty("from") Instant from,
      @JsonProperty("to") Instant to,
      @JsonProperty("source") Source source,
      @JsonProperty("truncated") Boolean truncated,
      @JsonProperty("shown") Long shown,
      @JsonProperty("total") Long total,
      @JsonProperty("sampling") Sampling sampling,
      @JsonProperty("next_cursor") Optional<String> nextCursor,
      @JsonProperty("retention_exceeded") Boolean retentionExceeded,
      @JsonProperty("outages") List<Outage> outages,
      @JsonProperty("slow_threshold_ms") Integer slowThresholdMs) {

    public Meta {
      outages = outages == null ? null : List.copyOf(outages);
    }
  }

  /**
   * The XLog sampling of A-5: applied when the range has more than 100,000 points, with the
   * deterministic rule as text.
   *
   * @param applied whether points were sampled
   * @param rule the rule, e.g. {@code error OR slow OR cityHash64(tx_id) % 2 = 0}; absent when not
   *     applied
   */
  @JsonPropertyOrder({"applied", "rule"})
  @Schema(name = "QuerySampling")
  public record Sampling(
      @JsonProperty("applied") boolean applied,
      @JsonProperty("rule") @JsonInclude(JsonInclude.Include.NON_NULL) String rule) {}

  /**
   * A collection outage (D-18, C-5; 14 §4.15).
   *
   * @param from start
   * @param to end, absent while it lasts
   * @param reason {@code server_down}, {@code session_lost} or {@code queue_overflow}
   */
  @JsonPropertyOrder({"from", "to", "reason"})
  @JsonInclude(JsonInclude.Include.NON_NULL)
  @Schema(name = "QueryOutage")
  public record Outage(
      @JsonProperty("from") @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant from,
      @JsonProperty("to") Instant to,
      @JsonProperty("reason") @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String reason) {}

  /** Builds an envelope. */
  public static final class Builder<T> {

    private final T data;
    private String requestId = MDC.get(RequestIdFilter.MDC_KEY);
    private Instant generatedAt = Instant.now();
    private Instant from;
    private Instant to;
    private Source source;
    private Boolean truncated;
    private Long shown;
    private Long total;
    private Sampling sampling;
    private Optional<String> nextCursor;
    private Boolean retentionExceeded;
    private List<Outage> outages;
    private Integer slowThresholdMs;

    private Builder(T data) {
      this.data = data;
    }

    /** Overrides the request id (outside a request). */
    public Builder<T> requestId(String value) {
      this.requestId = value;
      return this;
    }

    /** Overrides the generation time. */
    public Builder<T> generatedAt(Instant value) {
      this.generatedAt = value;
      return this;
    }

    /** {@code from}, {@code to}. */
    public Builder<T> range(Instant rangeFrom, Instant rangeTo) {
      this.from = rangeFrom;
      this.to = rangeTo;
      return this;
    }

    /**
     * {@code from}, {@code to}, {@code source} and {@code retention_exceeded} of a plan, and {@code
     * truncated} = false unless set.
     */
    public Builder<T> plan(SourcePlan plan) {
      range(plan.from(), plan.to());
      this.source = plan.source();
      this.retentionExceeded = plan.retentionExceeded();
      if (truncated == null) {
        truncated = false;
      }
      return this;
    }

    /** {@code source}. */
    public Builder<T> source(Source value) {
      this.source = value;
      return this;
    }

    /** {@code truncated}. */
    public Builder<T> truncated(boolean value) {
      this.truncated = value;
      return this;
    }

    /** {@code retention_exceeded}. */
    public Builder<T> retentionExceeded(boolean value) {
      this.retentionExceeded = value;
      return this;
    }

    /** {@code shown} and {@code total}. */
    public Builder<T> counts(long shownRows, long totalRows) {
      this.shown = shownRows;
      this.total = totalRows;
      return this;
    }

    /** {@code sampling}; {@code rule} is kept only when applied. */
    public Builder<T> sampling(boolean applied, String rule) {
      this.sampling = new Sampling(applied, applied ? rule : null);
      return this;
    }

    /** {@code next_cursor} of a paged list: the cursor, or null on the last page. */
    public Builder<T> nextCursor(String cursor) {
      this.nextCursor = Optional.ofNullable(cursor);
      return this;
    }

    /** {@code outages}; an empty list is written as {@code []}. */
    public Builder<T> outages(List<Outage> value) {
      this.outages = value == null ? List.of() : value;
      return this;
    }

    /** {@code slow_threshold_ms}. */
    public Builder<T> slowThresholdMs(int value) {
      this.slowThresholdMs = value;
      return this;
    }

    /** The envelope. */
    public ResponseEnvelope<T> build() {
      return new ResponseEnvelope<>(
          data,
          new Meta(
              requestId,
              millis(generatedAt),
              millis(from),
              millis(to),
              source,
              truncated,
              shown,
              total,
              sampling,
              nextCursor,
              retentionExceeded,
              outages,
              slowThresholdMs));
    }

    private static Instant millis(Instant t) {
      return t == null ? null : t.truncatedTo(ChronoUnit.MILLIS);
    }
  }
}
