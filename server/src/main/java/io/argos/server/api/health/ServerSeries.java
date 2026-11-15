/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.health;

import io.argos.server.api.dashboard.TimeQueries;
import io.argos.server.api.query.SourcePlan;
import io.argos.server.storage.query.QueryParams;
import io.argos.server.storage.query.QueryRow;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The server's own collection series in {@code metric} ({@code app_id = 0}; 12 §9.4, DSN-12-14,
 * written by WO-28): {@code argos_collector_dropped_total{reason}} (server drops, server-wide) and
 * {@code argos_ingest_lag_seconds}. Before WO-28 writes them there are no rows, and the responses
 * carry null for them (D-23).
 */
@Component
public class ServerSeries {

  /** Key of {@code argos_collector_dropped_total}. */
  public static final String DROPPED = "dropped";

  /** Key of {@code argos_ingest_lag_seconds}. */
  public static final String INGEST_LAG = "ingest_lag";

  /** The drop reason of the per-instance quota (12 §9.4 {@code reason=quota}). */
  public static final String QUOTA = "quota";

  private final TimeQueries queries;

  public ServerSeries(TimeQueries queries) {
    this.queries = queries;
  }

  /**
   * One key and label in one bucket.
   *
   * @param bucket bucket start
   * @param key {@value #DROPPED} or {@value #INGEST_LAG}
   * @param label the drop reason, empty for the lag
   * @param value gauge average
   * @param increase counter increase
   */
  public record Value(Instant bucket, String key, String label, Double value, Double increase) {}

  /** The buckets of the plan that have rows. */
  public List<Value> read(SourcePlan plan) {
    List<Value> out = new ArrayList<>();
    for (QueryRow r :
        queries.run(
            "health/server_series.outer", "health/server_series", plan, QueryParams.create())) {
      out.add(
          new Value(
              r.getInstant("b"),
              r.getString("k"),
              r.getString("l"),
              r.getDoubleOrNull("v"),
              r.getDoubleOrNull("increase")));
    }
    return out;
  }
}
