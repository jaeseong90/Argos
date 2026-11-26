/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.chaos;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What {@code GET /api/v1/apps/{id}/collection-health} shows of a scenario (14 §4.15, schema {@code
 * CollectionHealth}): the agent drops, the server drops (all reasons and quota), and the outages.
 *
 * @param agentDrops the sum of the {@code agent_drops} series (null values skipped)
 * @param serverDrops the sum of {@code server_drops}
 * @param serverDropsQuota the sum of {@code server_drops_quota}
 * @param outages the outages
 */
public record HealthView(
    double agentDrops, double serverDrops, double serverDropsQuota, List<Outage> outages) {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /**
   * One outage.
   *
   * @param from start
   * @param to end, null while it lasts
   * @param reason {@code server_down}, {@code session_lost}, {@code queue_overflow}
   */
  public record Outage(Instant from, Instant to, String reason) {

    /** Whether it overlaps [a, b]. */
    public boolean overlaps(Instant a, Instant b) {
      return !from.isAfter(b) && (to == null || !to.isBefore(a));
    }
  }

  /** Copies. */
  public HealthView {
    outages = List.copyOf(outages);
  }

  /** Parses the {@code data} member. */
  public static HealthView of(JsonNode data) {
    List<Outage> outages = new ArrayList<>();
    for (JsonNode o : data.path("outages")) {
      String to =
          o.path("to").isNull() || o.path("to").isMissingNode() ? null : o.path("to").asString();
      outages.add(
          new Outage(
              time(o.path("from").asString()),
              to == null ? null : time(to),
              o.path("reason").asString()));
    }
    return new HealthView(
        sum(data.path("agent_drops")),
        sum(data.path("server_drops")),
        sum(data.path("server_drops_quota")),
        outages);
  }

  /** Parses JSON text of the {@code data} member (tests). */
  public static HealthView parse(String json) {
    return of(JSON.readTree(json));
  }

  /** Whether an outage of {@code reason} overlaps [from, to]. */
  public boolean hasOutage(String reason, Instant from, Instant to) {
    return outages.stream().anyMatch(o -> o.reason().equals(reason) && o.overlaps(from, to));
  }

  private static double sum(JsonNode series) {
    double sum = 0;
    for (JsonNode v : series.path("value")) {
      if (v.isNumber()) {
        sum += v.asDouble();
      }
    }
    return sum;
  }

  private static Instant time(String text) {
    return OffsetDateTime.parse(text).toInstant();
  }
}
