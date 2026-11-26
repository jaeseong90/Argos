/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.chaos;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The JSON summary of the demo load generator ({@code --summary}, sample-apps/load README): the
 * request counts of the measured window and its whole-window p99.
 *
 * @param sent requests sent
 * @param answered requests with an HTTP answer (any status)
 * @param failedWithoutAnswer requests without an answer (timeout, connection failure)
 * @param unexpected answers with another status than the route's, and requests without an answer
 * @param dropped requests the generator could not start
 * @param inFlightAtEnd requests still unanswered after the drain
 * @param p99 whole-window p99 in ms (NaN when absent)
 */
public record LoadSummary(
    long sent,
    long answered,
    long failedWithoutAnswer,
    long unexpected,
    long dropped,
    long inFlightAtEnd,
    double p99) {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** Parses the summary file's text. */
  public static LoadSummary parse(String json) {
    JsonNode root = JSON.readTree(json);
    JsonNode r = root.path("requests");
    JsonNode latency = root.path("latencyMs").path("p99");
    return new LoadSummary(
        r.path("sent").asLong(),
        r.path("answered").asLong(),
        r.path("failedWithoutAnswer").asLong(),
        r.path("unexpected").asLong(),
        r.path("dropped").asLong(),
        r.path("inFlightAtEnd").asLong(),
        latency.isNumber() ? latency.asDouble() : Double.NaN);
  }

  /** The fewest transactions the application handled: every answered request is one transaction. */
  public long minTransactions() {
    return answered;
  }

  /**
   * The most transactions the application can have handled: a request without an answer may still
   * have run (timeout), one still in flight may finish after the generator stopped.
   */
  public long maxTransactions() {
    return answered + failedWithoutAnswer + inFlightAtEnd;
  }
}
