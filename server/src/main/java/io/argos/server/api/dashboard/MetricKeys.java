/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.dashboard;

import io.argos.server.api.query.SourcePlan;
import io.argos.server.storage.query.QueryParams;
import io.argos.server.storage.query.QueryRow;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The JVM, container and agent series that the dashboard, the instance comparison, the instance
 * list and the collection health read by key from {@code metric}/{@code metric_1m}/{@code
 * metric_1h} (10 §11 names; templates {@code dashboard/metric_keys.*}):
 *
 * <table>
 *   <caption>Keys</caption>
 *   <tr><th>Key</th><th>Series</th><th>Value</th></tr>
 *   <tr><td>{@value #HEAP_USED}, {@value #HEAP_MAX}</td><td>{@code jvm_memory_used_bytes},
 *       {@code jvm_memory_max_bytes} with {@code area="heap"}</td><td>gauge</td></tr>
 *   <tr><td>{@value #GC_PAUSE}</td><td>{@code jvm_gc_pause_seconds_total} (pauses only, D-25)</td>
 *       <td>increase</td></tr>
 *   <tr><td>{@value #CPU}</td><td>{@code process_cpu_usage_ratio}</td><td>gauge</td></tr>
 *   <tr><td>{@value #SYSTEM_CPU}</td><td>{@code system_cpu_usage_ratio} (the agent's cgroup-limited
 *       system CPU, 10 §11; ADR-0010 C-212 (1))</td><td>gauge</td></tr>
 *   <tr><td>{@value #TOMCAT_BUSY}, {@value #TOMCAT_MAX}</td><td>{@code tomcat_threads_busy},
 *       {@code tomcat_threads_max}</td><td>gauge</td></tr>
 *   <tr><td>{@value #POOL_ACTIVE}, {@value #POOL_MAX}</td><td>{@code jdbc_pool_active}, {@code
 *       jdbc_pool_max} (label: pool)</td><td>gauge</td></tr>
 *   <tr><td>{@value #AGENT_DROPS}</td><td>{@code argos_agent_dropped_total}</td><td>increase</td></tr>
 *   <tr><td>{@value #EXCLUDED}</td><td>{@code argos_agent_ignored_total} (label: reason)</td>
 *       <td>increase</td></tr>
 *   <tr><td>{@value #ACTIVE}</td><td>{@code argos_active_transactions} (recorded by the server
 *       from each active snapshot, ADR-0010 C-178 (1))</td><td>gauge</td></tr>
 * </table>
 *
 * A gauge is first averaged per instance (value sum / samples), then summed ({@code total}) or
 * averaged ({@code mean}) over the instances; a counter's increase is summed. A key without rows
 * has no value (null, D-09).
 */
@Component
public class MetricKeys {

  /** Heap used, bytes. */
  public static final String HEAP_USED = "heap_used";

  /** Heap max, bytes. */
  public static final String HEAP_MAX = "heap_max";

  /** GC pause seconds. */
  public static final String GC_PAUSE = "gc_pause";

  /** Process CPU ratio. */
  public static final String CPU = "cpu";

  /** System CPU ratio (ADR-0010 C-212 (1)). */
  public static final String SYSTEM_CPU = "system_cpu";

  /** Busy Tomcat threads. */
  public static final String TOMCAT_BUSY = "tomcat_busy";

  /** Tomcat thread maximum. */
  public static final String TOMCAT_MAX = "tomcat_max";

  /** Active pool connections. */
  public static final String POOL_ACTIVE = "pool_active";

  /** Pool maximum. */
  public static final String POOL_MAX = "pool_max";

  /** Agent drops. */
  public static final String AGENT_DROPS = "agent_drops";

  /** Transactions the agent excluded by rule. */
  public static final String EXCLUDED = "excluded";

  /** Active transactions (the server's gauge of the active snapshots, ADR-0010 C-178 (1)). */
  public static final String ACTIVE = "active";

  /** Every key. */
  public static final Set<String> ALL =
      Set.of(
          HEAP_USED,
          HEAP_MAX,
          GC_PAUSE,
          CPU,
          SYSTEM_CPU,
          TOMCAT_BUSY,
          TOMCAT_MAX,
          POOL_ACTIVE,
          POOL_MAX,
          AGENT_DROPS,
          EXCLUDED,
          ACTIVE);

  private static final String FRAGMENTS = "dashboard/metric_keys";

  private final TimeQueries queries;

  public MetricKeys(TimeQueries queries) {
    this.queries = queries;
  }

  /**
   * One key and label in one bucket.
   *
   * @param bucket bucket start
   * @param key key
   * @param label pool or reason label, empty for the others
   * @param total sum over the instances of their bucket average
   * @param mean average over the instances of their bucket average
   * @param increase counter increase over the instances
   */
  public record BucketValue(
      Instant bucket, String key, String label, Double total, Double mean, Double increase) {}

  /**
   * One key and label of one instance over the whole range.
   *
   * @param instanceId instance
   * @param key key
   * @param label pool or reason label, empty for the others
   * @param value gauge average over the range
   * @param increase counter increase over the range
   */
  public record InstanceValue(
      int instanceId, String key, String label, Double value, Double increase) {}

  /** The {@code keys} per bucket of the plan, of the {@code instances} (all when empty). */
  public List<BucketValue> series(
      int appId, Collection<Integer> instances, SourcePlan plan, Collection<String> keys) {
    return series(appId, instances, List.of(), plan, keys);
  }

  /**
   * The {@code keys} per bucket of the plan, of the {@code instances} (all when empty) without the
   * {@code excluded} ones ({@code exclude_instance}, ADR-0010 C-212 (1)).
   */
  public List<BucketValue> series(
      int appId,
      Collection<Integer> instances,
      Collection<Integer> excluded,
      SourcePlan plan,
      Collection<String> keys) {
    List<BucketValue> out = new ArrayList<>();
    for (QueryRow r :
        queries.run(
            "dashboard/metric_keys.outer",
            FRAGMENTS,
            plan,
            params(appId, instances, excluded, keys))) {
      out.add(
          new BucketValue(
              r.getInstant("b"),
              r.getString("k"),
              r.getString("l"),
              r.getDoubleOrNull("total"),
              r.getDoubleOrNull("mean"),
              r.getDoubleOrNull("increase")));
    }
    return out;
  }

  /** The {@code keys} per instance over the plan's range. */
  public List<InstanceValue> perInstance(
      int appId, Collection<Integer> instances, SourcePlan plan, Collection<String> keys) {
    return perInstance(appId, instances, List.of(), plan, keys);
  }

  /** The {@code keys} per instance over the plan's range, without the {@code excluded} ones. */
  public List<InstanceValue> perInstance(
      int appId,
      Collection<Integer> instances,
      Collection<Integer> excluded,
      SourcePlan plan,
      Collection<String> keys) {
    List<InstanceValue> out = new ArrayList<>();
    for (QueryRow r :
        queries.run(
            "dashboard/metric_instances.outer",
            FRAGMENTS,
            plan,
            params(appId, instances, excluded, keys))) {
      out.add(
          new InstanceValue(
              (int) r.getLong("i"),
              r.getString("k"),
              r.getString("l"),
              r.getDoubleOrNull("v"),
              r.getDoubleOrNull("increase")));
    }
    return out;
  }

  private static QueryParams params(
      int appId,
      Collection<Integer> instances,
      Collection<Integer> excluded,
      Collection<String> keys) {
    return TimeQueries.instanceFilter(
            QueryParams.create().uint32("app", appId), instances, excluded)
        .stringArray("keys", List.copyOf(keys));
  }
}
