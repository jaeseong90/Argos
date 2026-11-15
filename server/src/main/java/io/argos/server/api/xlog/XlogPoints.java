/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.xlog;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.argos.server.api.openapi.ArgosUntrusted;
import io.argos.server.api.query.ResponseEnvelope;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.List;
import java.util.Map;

/**
 * The XLog points in columns (14 §4.5, DSN-14-08; the same columns as the WebSocket {@code xlog}
 * topic, 14 §5.2, 12 §3.7): element {@code i} of every array describes point {@code i}, in {@code
 * ts}, {@code tx_id} order. Primitive arrays keep 100,000 points small in memory.
 */
@Schema(name = "XlogPoints")
@JsonPropertyOrder({
  "tx_id",
  "ts",
  "elapsed_ms",
  "status",
  "instance_id",
  "service_id",
  "kind",
  "dictionary"
})
public final class XlogPoints {

  private final List<String> txId;
  private final long[] ts;
  private final long[] elapsedMs;
  private final int[] status;
  private final long[] instanceId;
  private final List<String> serviceId;
  private final int[] kind;
  private final Dictionary dictionary;

  XlogPoints(
      List<String> txId,
      long[] ts,
      long[] elapsedMs,
      int[] status,
      long[] instanceId,
      List<String> serviceId,
      int[] kind,
      Dictionary dictionary) {
    this.txId = List.copyOf(txId);
    this.ts = ts;
    this.elapsedMs = elapsedMs;
    this.status = status;
    this.instanceId = instanceId;
    this.serviceId = List.copyOf(serviceId);
    this.kind = kind;
    this.dictionary = dictionary;
  }

  /** Transaction ids (unsigned decimal strings). */
  @JsonProperty("tx_id")
  @Schema(requiredMode = RequiredMode.REQUIRED)
  public List<String> getTxId() {
    return txId;
  }

  /** Starts in epoch milliseconds. */
  @JsonProperty("ts")
  @Schema(requiredMode = RequiredMode.REQUIRED)
  public long[] getTs() {
    return ts;
  }

  /** Elapsed times in ms. */
  @JsonProperty("elapsed_ms")
  @Schema(requiredMode = RequiredMode.REQUIRED)
  public long[] getElapsedMs() {
    return elapsedMs;
  }

  /**
   * 0 normal, 1 slow ({@code elapsed_ms >= meta.slow_threshold_ms}), 2 error. The document gives
   * the range as {@code minimum}/{@code maximum}: an {@code allowableValues} enum of primitive
   * array items is written as strings ({@code '0'}), which no integer matches.
   */
  @JsonProperty("status")
  @ArraySchema(
      schema =
          @Schema(
              type = "integer",
              format = "int32",
              minimum = "0",
              maximum = "2",
              description = "0 normal, 1 slow (elapsed_ms >= meta.slow_threshold_ms), 2 error"),
      arraySchema = @Schema(requiredMode = RequiredMode.REQUIRED))
  public int[] getStatus() {
    return status;
  }

  /** Instances. */
  @JsonProperty("instance_id")
  @Schema(requiredMode = RequiredMode.REQUIRED)
  public long[] getInstanceId() {
    return instanceId;
  }

  /** Service dictionary keys (unsigned decimal strings, names in {@code dictionary.service}). */
  @JsonProperty("service_id")
  @Schema(requiredMode = RequiredMode.REQUIRED)
  public List<String> getServiceId() {
    return serviceId;
  }

  /** 1 HTTP, 2 BATCH, 3 MESSAGE (a range, as for {@link #getStatus()}). */
  @JsonProperty("kind")
  @ArraySchema(
      schema =
          @Schema(
              type = "integer",
              format = "int32",
              minimum = "1",
              maximum = "3",
              description = "1 HTTP, 2 BATCH, 3 MESSAGE"),
      arraySchema = @Schema(requiredMode = RequiredMode.REQUIRED))
  public int[] getKind() {
    return kind;
  }

  /** The names of the services and instances of the points. */
  @JsonProperty("dictionary")
  @Schema(requiredMode = RequiredMode.REQUIRED)
  public Dictionary getDictionary() {
    return dictionary;
  }

  /** Points. */
  public int size() {
    return ts.length;
  }

  /**
   * Names by key (keys are decimal strings).
   *
   * @param service service names by {@code service_id}
   * @param instance instance names by {@code instance_id}
   */
  @Schema(name = "XlogDictionary")
  public record Dictionary(
      @JsonProperty("service") @Schema(requiredMode = RequiredMode.REQUIRED) @ArgosUntrusted
          Map<String, String> service,
      @JsonProperty("instance") @Schema(requiredMode = RequiredMode.REQUIRED) @ArgosUntrusted
          Map<String, String> instance) {}

  /**
   * The response (documentation type of the envelope that the controller writes itself).
   *
   * @param data the points
   * @param meta {@code shown}, {@code total}, {@code sampling}, {@code slow_threshold_ms}, {@code
   *     truncated}, range and source
   */
  @Schema(name = "XlogResponse")
  public record Response(
      @JsonProperty("data") @Schema(requiredMode = RequiredMode.REQUIRED) XlogPoints data,
      @JsonProperty("meta") @Schema(requiredMode = RequiredMode.REQUIRED)
          ResponseEnvelope.Meta meta) {}
}
