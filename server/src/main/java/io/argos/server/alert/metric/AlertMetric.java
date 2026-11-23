/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert.metric;

import io.argos.server.alert.rule.RuleSpec.TargetType;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * The alert metrics of 12 §5.3 (F-S31) and where each one comes from (12 §5.2). The wire name is
 * the rule's {@code metric} value.
 *
 * <p>Sources: {@link Source#WINDOW} the 15-minute in-memory request windows ({@code
 * WindowProvider}), {@link Source#COUNTER} the 15-minute in-memory metric rings ({@code
 * CounterProvider}), {@link Source#REGISTRY} the session registry ({@code RegistryProvider}),
 * {@link Source#SELF} the server's Micrometer registry ({@code SelfProvider}), {@link Source#EVENT}
 * transitions decided outside the evaluation loop (instance state, channel health), {@link
 * Source#NONE} not computable in this version. A window longer than 15 minutes reads the minute
 * rollups instead ({@code ClickHouseProvider}) for the request and metric sources.
 */
public enum AlertMetric {
  /** Median response time, ms. */
  RESPONSE_TIME_P50(Source.WINDOW, Unit.MS, true, Aggregation.MERGE, Targets.REQUEST),
  /** 95th percentile response time, ms. */
  RESPONSE_TIME_P95(Source.WINDOW, Unit.MS, true, Aggregation.MERGE, Targets.REQUEST),
  /** 99th percentile response time, ms. */
  RESPONSE_TIME_P99(Source.WINDOW, Unit.MS, true, Aggregation.MERGE, Targets.REQUEST),
  /** Transactions per second. */
  TPS(Source.WINDOW, Unit.PER_SECOND, false, Aggregation.MERGE, Targets.REQUEST),
  /** Errors / transactions. */
  ERROR_RATE(Source.WINDOW, Unit.RATIO, true, Aggregation.MERGE, Targets.REQUEST),
  /** Apdex score (D-17). */
  APDEX(Source.WINDOW, Unit.SCORE, true, Aggregation.MERGE, Targets.REQUEST),
  /** Active transactions (latest snapshot). */
  ACTIVE_TRANSACTIONS(Source.REGISTRY, Unit.COUNT, false, Aggregation.SUM, Targets.APP_INSTANCE),
  /** Active transactions / Tomcat max threads. */
  ACTIVE_RATIO_TO_MAX_THREADS(
      Source.REGISTRY, Unit.RATIO, false, Aggregation.MAX, Targets.APP_INSTANCE),
  /** Heap used / heap max. */
  JVM_HEAP_USED_RATIO(Source.COUNTER, Unit.RATIO, false, Aggregation.MAX, Targets.APP_INSTANCE),
  /**
   * Old generation used after the last GC / max: the agent does not collect the after-GC pool usage
   * (10 §11), so it has no value ("가능 시", 12 §5.3; the heap template uses {@link
   * #JVM_HEAP_USED_RATIO}).
   */
  JVM_OLD_GEN_AFTER_GC_RATIO(Source.NONE, Unit.RATIO, false, Aggregation.MAX, Targets.APP_INSTANCE),
  /** GC pause seconds / wall seconds of the window (pauses only, D-25). */
  JVM_GC_PAUSE_RATIO(Source.COUNTER, Unit.RATIO, false, Aggregation.MAX, Targets.APP_INSTANCE),
  /** Live threads. */
  JVM_THREADS_LIVE(Source.COUNTER, Unit.COUNT, false, Aggregation.MAX, Targets.APP_INSTANCE),
  /** Busy / max Tomcat threads (all connectors). */
  TOMCAT_THREADS_USAGE_RATIO(
      Source.COUNTER, Unit.RATIO, false, Aggregation.MAX, Targets.APP_INSTANCE),
  /** Threads waiting for a pool connection (largest pool). */
  JDBC_POOL_WAITING(Source.COUNTER, Unit.COUNT, false, Aggregation.MAX, Targets.APP_INSTANCE),
  /** Active / max connections (fullest pool). */
  JDBC_POOL_USAGE_RATIO(Source.COUNTER, Unit.RATIO, false, Aggregation.MAX, Targets.APP_INSTANCE),
  /** 95th percentile of outgoing calls to a host, ms. */
  EXT_CALL_P95(Source.WINDOW, Unit.MS, true, Aggregation.MERGE, Targets.EXT_HOST),
  /** Failed / all outgoing calls to a host. */
  EXT_CALL_ERROR_RATIO(Source.WINDOW, Unit.RATIO, true, Aggregation.MERGE, Targets.EXT_HOST),
  /** (Timed out + connection failed) / all outgoing calls to a host. */
  EXT_CALL_TIMEOUT_RATIO(Source.WINDOW, Unit.RATIO, true, Aggregation.MERGE, Targets.EXT_HOST),
  /** Longest elapsed time of a batch job (running or finished in the window), seconds. */
  BATCH_ELAPSED_SECONDS(Source.WINDOW, Unit.SECONDS, false, Aggregation.MAX, Targets.SERVICE),
  /** System CPU (container limit as the denominator). */
  SYSTEM_CPU_RATIO(Source.COUNTER, Unit.RATIO, false, Aggregation.MAX, Targets.APP_INSTANCE),
  /** System memory used / total (limit). */
  SYSTEM_MEMORY_RATIO(Source.COUNTER, Unit.RATIO, false, Aggregation.MAX, Targets.APP_INSTANCE),
  /** Disk used / total (fullest mount). */
  SYSTEM_DISK_USAGE_RATIO(Source.COUNTER, Unit.RATIO, false, Aggregation.MAX, Targets.APP_INSTANCE),
  /** Items the agents dropped in the window. */
  ARGOS_AGENT_DROPPED_TOTAL(
      Source.COUNTER, Unit.COUNT, false, Aggregation.SUM, Targets.APP_INSTANCE),
  /** Items the server dropped at receipt in the window. */
  ARGOS_SERVER_DROPPED_TOTAL(Source.SELF, Unit.COUNT, false, Aggregation.SUM, Targets.SERVER),
  /** Receive to queryable delay (p99 of the recent inserts), seconds. */
  ARGOS_SERVER_INGEST_LAG_SECONDS(
      Source.SELF, Unit.SECONDS, false, Aggregation.MAX, Targets.SERVER),
  /** ClickHouse and WAL disk usage (WO-28's meter). */
  ARGOS_SERVER_DISK_USAGE_RATIO(Source.SELF, Unit.RATIO, false, Aggregation.MAX, Targets.SERVER),
  /** Failed notification deliveries in the window (the channel failure rule is event driven). */
  ARGOS_NOTIFICATION_FAILURES_TOTAL(
      Source.SELF, Unit.COUNT, false, Aggregation.SUM, Targets.SERVER),
  /** Days until the collector certificate expires (WO-28's meter). */
  ARGOS_SERVER_TLS_CERT_EXPIRY_DAYS(Source.SELF, Unit.DAYS, false, Aggregation.MIN, Targets.SERVER),
  /** Days until the first agent token expires (WO-26's meter). */
  AGENT_TOKEN_EXPIRY_DAYS(Source.SELF, Unit.DAYS, false, Aggregation.MIN, Targets.SERVER),
  /** Instance DOWN (12 §4.2): decided by the instance state transitions, not by evaluation. */
  INSTANCE_STATE(Source.EVENT, Unit.STATE, false, Aggregation.MAX, Targets.APP_INSTANCE),
  /** A new error group appeared (S, not in this version: no value). */
  ERROR_GROUP_NEW(Source.NONE, Unit.COUNT, false, Aggregation.SUM, Targets.APP_ONLY);

  private final Source source;
  private final Unit unit;
  private final boolean request;
  private final Aggregation aggregation;

  @SuppressWarnings("ImmutableEnumChecker") // an unmodifiable EnumSet, never changed
  private final Set<TargetType> targets;

  AlertMetric(
      Source source, Unit unit, boolean request, Aggregation aggregation, Set<TargetType> targets) {
    this.source = source;
    this.unit = unit;
    this.request = request;
    this.aggregation = aggregation;
    this.targets = Collections.unmodifiableSet(targets);
  }

  /** The rule's {@code metric} value. */
  public String wire() {
    return name().toLowerCase(Locale.ROOT);
  }

  /** The metric of a wire name, or null. */
  public static AlertMetric of(String wire) {
    if (wire == null) {
      return null;
    }
    for (AlertMetric m : values()) {
      if (m.wire().equals(wire)) {
        return m;
      }
    }
    return null;
  }

  /** Where the value comes from. */
  public Source source() {
    return source;
  }

  /** The unit of the value. */
  public Unit unit() {
    return unit;
  }

  /**
   * Whether it is a request metric: {@code min_samples} applies and {@code no_data} defaults to
   * {@code ignore} (12 §5.4, F-S25).
   */
  public boolean request() {
    return request;
  }

  /** How instance values combine into the application's value. */
  public Aggregation aggregation() {
    return aggregation;
  }

  /** The target types a rule of this metric may have. */
  public Set<TargetType> targets() {
    return targets;
  }

  /** Whether a rule window longer than 15 minutes reads the minute rollups. */
  public boolean rollupCapable() {
    return source == Source.WINDOW || source == Source.COUNTER;
  }

  /** Where a metric's value comes from (12 §5.2). */
  public enum Source {
    /** In-memory request windows. */
    WINDOW,
    /** In-memory metric rings. */
    COUNTER,
    /** The session registry. */
    REGISTRY,
    /** The server's own meters. */
    SELF,
    /** Transitions decided outside the evaluation loop. */
    EVENT,
    /** Not computable in this version. */
    NONE
  }

  /** Unit of a value (message formatting). */
  public enum Unit {
    /** Milliseconds. */
    MS,
    /** Seconds. */
    SECONDS,
    /** Days. */
    DAYS,
    /** 0..1, shown as a percentage. */
    RATIO,
    /** Apdex 0..1. */
    SCORE,
    /** A count. */
    COUNT,
    /** Per second. */
    PER_SECOND,
    /** 1 = DOWN. */
    STATE
  }

  /** How instance values combine. */
  public enum Aggregation {
    /** The samples of the instances merged (percentiles, ratios over all requests). */
    MERGE,
    /** The sum. */
    SUM,
    /** The largest (worst instance). */
    MAX,
    /** The smallest. */
    MIN
  }

  private static final class Targets {
    static final Set<TargetType> REQUEST =
        EnumSet.of(TargetType.APP, TargetType.INSTANCE, TargetType.SERVICE);
    static final Set<TargetType> APP_INSTANCE = EnumSet.of(TargetType.APP, TargetType.INSTANCE);
    static final Set<TargetType> APP_ONLY = EnumSet.of(TargetType.APP);
    static final Set<TargetType> EXT_HOST = EnumSet.of(TargetType.EXT_HOST);
    static final Set<TargetType> SERVICE = EnumSet.of(TargetType.SERVICE);
    static final Set<TargetType> SERVER = EnumSet.of(TargetType.SERVER);

    private Targets() {}
  }
}
