/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert.metric;

import io.argos.proto.v1.ActiveTransaction;
import io.argos.proto.v1.TransactionKind;
import io.argos.server.alert.rule.RuleSpec.TargetType;
import io.argos.server.session.AgentSession;
import io.argos.server.session.SessionRegistry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Session registry values of the alert engine (12 §5.2 {@code RegistryProvider}): active
 * transactions of the latest snapshot of each live session, the active ratio to Tomcat's max
 * threads, the running batch jobs' elapsed time, the live instances of an application and the
 * warm-up start of each instance (the acceptance of the first session of its agent epoch, 12 §5.4
 * {@code warmup_grace}).
 *
 * <p>Read from the alert engine's thread only.
 */
public final class RegistryProvider implements MetricProvider {

  /** A snapshot older than this does not count (twice the 5 s period plus slack). */
  public static final long SNAPSHOT_MAX_AGE_MS = 15_000;

  private final SessionRegistry registry;
  private final CounterProvider counters;
  private final Map<Integer, Start> starts = new HashMap<>();

  public RegistryProvider(SessionRegistry registry, CounterProvider counters) {
    this.registry = registry;
    this.counters = counters;
  }

  @Override
  public MetricValue read(MetricRequest r) {
    if (r.appId() == null) {
      return MetricValue.unavailable();
    }
    return switch (r.metric()) {
      case ACTIVE_TRANSACTIONS, ACTIVE_RATIO_TO_MAX_THREADS -> active(r);
      case BATCH_ELAPSED_SECONDS -> batch(r);
      default -> MetricValue.unavailable();
    };
  }

  private MetricValue active(MetricRequest r) {
    Double out = null;
    for (AgentSession s : registry.byApp(r.appId())) {
      int id = s.instanceId();
      if (r.targetType() == TargetType.INSTANCE
          ? id != (int) r.targetIdAsLong()
          : r.excludedInstances().contains(id)) {
        continue;
      }
      AgentSession.ActiveSnapshot snap = s.latestSnapshot();
      if (snap == null || r.nowMs() - snap.receivedAtMs() > SNAPSHOT_MAX_AGE_MS) {
        continue;
      }
      double active = Integer.toUnsignedLong(snap.snapshot().getTotalActive());
      Double v;
      if (r.metric() == AlertMetric.ACTIVE_TRANSACTIONS) {
        v = active;
      } else {
        Double max = counters.tomcatMaxThreads(id, r.nowMs());
        v = max == null || !(max > 0) ? null : active / max;
      }
      if (v != null) {
        out =
            out == null
                ? v
                : r.metric() == AlertMetric.ACTIVE_TRANSACTIONS ? out + v : Math.max(out, v);
      }
    }
    return out == null ? MetricValue.noData(0) : MetricValue.of(out);
  }

  /** The longest running BATCH transaction of the job (service), seconds. */
  private MetricValue batch(MetricRequest r) {
    if (r.targetId() == null) {
      return MetricValue.noData(0);
    }
    long job = r.targetIdAsLong();
    Double max = null;
    boolean anySnapshot = false;
    for (AgentSession s : registry.byApp(r.appId())) {
      AgentSession.ActiveSnapshot snap = s.latestSnapshot();
      if (snap == null || r.nowMs() - snap.receivedAtMs() > SNAPSHOT_MAX_AGE_MS) {
        continue;
      }
      anySnapshot = true;
      for (ActiveTransaction t : snap.snapshot().getItemsList()) {
        if (t.getKind() == TransactionKind.BATCH && t.getServiceId() == job) {
          double sec = Integer.toUnsignedLong(t.getElapsedMs()) / 1000.0;
          max = max == null ? sec : Math.max(max, sec);
        }
      }
    }
    if (max != null) {
      return MetricValue.of(max);
    }
    return anySnapshot ? MetricValue.of(0) : MetricValue.noData(0);
  }

  /** The running batch jobs (service ids) of the application's live snapshots. */
  public List<Long> runningBatchJobs(int appId, long nowMs) {
    List<Long> out = new ArrayList<>();
    for (AgentSession s : registry.byApp(appId)) {
      AgentSession.ActiveSnapshot snap = s.latestSnapshot();
      if (snap == null || nowMs - snap.receivedAtMs() > SNAPSHOT_MAX_AGE_MS) {
        continue;
      }
      for (ActiveTransaction t : snap.snapshot().getItemsList()) {
        if (t.getKind() == TransactionKind.BATCH && !out.contains(t.getServiceId())) {
          out.add(t.getServiceId());
        }
      }
    }
    return out;
  }

  /** Whether the instance has a session. */
  public boolean live(int instanceId) {
    return registry.find(instanceId).isPresent();
  }

  /** The live instances (with a session) of the application. */
  public List<Integer> liveInstances(int appId) {
    List<Integer> out = new ArrayList<>();
    for (AgentSession s : registry.byApp(appId)) {
      out.add(s.instanceId());
    }
    return out;
  }

  /**
   * The warm-up start of an instance with a session: the server time its agent epoch was first
   * accepted (a reconnect of the same process keeps it), or -1 without a session.
   */
  public long warmupStart(int instanceId) {
    return registry
        .find(instanceId)
        .map(
            s -> {
              Start st = starts.get(instanceId);
              if (st == null || st.epoch != s.agentEpoch()) {
                st = new Start(s.agentEpoch(), s.acceptedAtMs());
                starts.put(instanceId, st);
              }
              return st.atMs;
            })
        .orElse(-1L);
  }

  /** Forgets the instances without a session (housekeeping). */
  public void evict() {
    starts.keySet().removeIf(id -> registry.find(id).isEmpty());
  }

  private record Start(long epoch, long atMs) {}
}
