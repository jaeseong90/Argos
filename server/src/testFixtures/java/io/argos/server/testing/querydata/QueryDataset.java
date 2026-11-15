/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.testing.querydata;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The query test data loaded by {@link QueryTestData}: where it lies in time, the estate it
 * describes (the PostgreSQL rows seeded for it) and the ground truth of the generator.
 *
 * @param profile the generator profile
 * @param placement where the range lies
 * @param seed the generator seed (fixed: the same seed and start give the same rows)
 * @param start first xlog instant
 * @param end end of the xlog range (exclusive)
 * @param profileFrom first instant with profile_step rows
 * @param metricFrom first instant with metric rows
 * @param apps the applications ({@code apps.id} = ClickHouse {@code app_id})
 * @param skewedInstanceIds instances whose clock runs about 60 s ahead (server-corrected ts,
 *     agent-time tx_id: the C-50 two-day fallback)
 * @param rows rows per raw table (generator manifest; equal to the ClickHouse counts)
 * @param xlogSqlCount sum of {@code xlog.sql_count} (D-12)
 * @param samples query parameters from the data
 * @param dictionaryEntries PostgreSQL dictionary rows this load inserted (distinct keys; 0 when
 *     they were already there)
 */
public record QueryDataset(
    QueryDataProfile profile,
    Placement placement,
    long seed,
    Instant start,
    Instant end,
    Instant profileFrom,
    Instant metricFrom,
    List<App> apps,
    List<Integer> skewedInstanceIds,
    Map<String, Long> rows,
    long xlogSqlCount,
    Samples samples,
    int dictionaryEntries) {

  public QueryDataset {
    apps = List.copyOf(apps);
    skewedInstanceIds = List.copyOf(skewedInstanceIds);
    rows = Map.copyOf(rows);
  }

  /**
   * An application of the data.
   *
   * @param appId {@code apps.id} and ClickHouse {@code app_id}
   * @param name {@code app-01}..
   * @param env {@code prod} or {@code stg}
   * @param instances its instances
   */
  public record App(int appId, String name, String env, List<Instance> instances) {
    public App {
      instances = List.copyOf(instances);
    }
  }

  /**
   * An instance ({@code instances.id} and ClickHouse {@code instance_id}).
   *
   * @param instanceId id
   * @param name the stored name ({@code app-01-1:8080})
   * @param skewMs the agent clock offset the generator simulated (positive: ahead)
   */
  public record Instance(int instanceId, String name, long skewMs) {}

  /** The xlog rows of the data. */
  public long xlogRows() {
    return rows.getOrDefault("xlog", 0L);
  }

  /** The application {@code appId}. */
  public App app(int appId) {
    return apps.stream()
        .filter(a -> a.appId() == appId)
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("No app " + appId));
  }
}
