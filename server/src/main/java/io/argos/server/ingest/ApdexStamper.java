/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ingest;

import io.argos.server.ingest.api.ApdexProvider;
import io.argos.server.session.api.ApdexOverrides;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The Apdex T stamp of D-17 (12 §3.5 (5)): {@code apps.apdex_t_ms} and the service overrides of
 * {@code service_apdex_overrides} (WO-27) cached in memory and reloaded every {@value #REFRESH_MS}
 * ms by {@code argos-apdex-refresh}, and at once after a change ({@link ApdexChangeService}); an
 * app not loaded yet gets the {@code apps} default {@value #DEFAULT_APDEX_T_MS} ms and no override
 * and triggers a reload within a second. The collector stamps the values at receipt into the
 * session context and so into the WAL record ({@link io.argos.server.wal.api.WalDataPayload}), so
 * rows received after a change carry the new T and a replay keeps the T of the receipt (forward
 * only, T-SV-017, T-ACC-17).
 *
 * <p>An app with more than {@link ApdexOverrides#MAX_PER_APP} overrides in the table (only possible
 * by writing the table directly; {@link ApdexChangeService} refuses the 1,001st) gets none of them:
 * the set is refused and counted in {@code argos_ingest_errors_total{stage="apdex_overrides"}} at
 * every reload, with one WARN line per app.
 */
@Component
public class ApdexStamper implements ApdexProvider, DisposableBean {

  /** {@code apps.apdex_t_ms} default (13 §12.2). */
  public static final int DEFAULT_APDEX_T_MS = 500;

  /** Reload period. */
  static final long REFRESH_MS = 5_000;

  private static final Logger LOG = LoggerFactory.getLogger(ApdexStamper.class);

  private final IngestStore store;
  private final @Nullable IngestMetrics metrics;
  private final Map<Integer, Integer> byApp = new ConcurrentHashMap<>();
  private final Map<Integer, ApdexOverrides> overridesByApp = new ConcurrentHashMap<>();
  private final Map<Integer, Boolean> warnedApps = new ConcurrentHashMap<>();
  private volatile boolean reloadWanted = true;
  private volatile long lastRefreshNanos = System.nanoTime();
  private ScheduledExecutorService executor;

  /** A stamper without meters (tests). */
  public ApdexStamper(IngestStore store) {
    this(store, null);
  }

  /**
   * @param store the PostgreSQL side
   * @param metrics the ingest meters ({@code null}: refused override sets are only logged)
   */
  @Autowired
  public ApdexStamper(IngestStore store, @Nullable IngestMetrics metrics) {
    this.store = Objects.requireNonNull(store, "store");
    this.metrics = metrics;
  }

  /** Loads the values once and starts the reload thread. */
  public synchronized void start() {
    if (executor != null) {
      return;
    }
    refresh();
    executor =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "argos-apdex-refresh");
              t.setDaemon(true);
              return t;
            });
    var unused = executor.scheduleWithFixedDelay(this::tick, 1_000, 1_000, TimeUnit.MILLISECONDS);
  }

  private void tick() {
    if (reloadWanted
        || System.nanoTime() - lastRefreshNanos >= TimeUnit.MILLISECONDS.toNanos(REFRESH_MS)) {
      refresh();
    }
  }

  @Override
  public int apdexTMs(int appId) {
    Integer value = byApp.get(appId);
    if (value == null) {
      reloadWanted = true;
      return DEFAULT_APDEX_T_MS;
    }
    return value;
  }

  @Override
  public ApdexOverrides overrides(int appId) {
    return overridesByApp.getOrDefault(appId, ApdexOverrides.NONE);
  }

  /**
   * Reloads every app's T and service overrides now.
   *
   * @return {@code true} when the values were read
   */
  public synchronized boolean refresh() {
    lastRefreshNanos = System.nanoTime();
    try {
      Map<Integer, Integer> values = store.apdexByApp();
      Map<Integer, Map<Long, Integer>> overrides =
          store.serviceApdexOverrides(ApdexOverrides.MAX_PER_APP);
      Map<Integer, ApdexOverrides> snapshots = new HashMap<>();
      for (Map.Entry<Integer, Map<Long, Integer>> e : overrides.entrySet()) {
        ApdexOverrides snapshot = snapshot(e.getKey(), e.getValue());
        if (!snapshot.isEmpty()) {
          snapshots.put(e.getKey(), snapshot);
        }
      }
      byApp.putAll(values);
      byApp.keySet().retainAll(values.keySet());
      overridesByApp.putAll(snapshots);
      overridesByApp.keySet().retainAll(snapshots.keySet());
      reloadWanted = false;
      return true;
    } catch (Exception e) {
      LOG.debug("Apdex T reload failed, the cached values stay: {}", e.toString());
      return false;
    }
  }

  /** The snapshot of one app's rows; a set above the limit or with a bad T is refused. */
  private ApdexOverrides snapshot(int appId, Map<Long, Integer> rows) {
    try {
      ApdexOverrides snapshot = ApdexOverrides.of(rows);
      warnedApps.remove(appId);
      return snapshot;
    } catch (IllegalArgumentException e) {
      if (metrics != null) {
        metrics.error("apdex_overrides");
      }
      if (warnedApps.putIfAbsent(appId, Boolean.TRUE) == null) {
        LOG.warn(
            "The service Apdex overrides of app {} are not used: more than {} rows or a T outside"
                + " [1, 65535] in service_apdex_overrides; its transactions get the app's T."
                + " Counted in argos_ingest_errors_total{stage=\"apdex_overrides\"}",
            appId,
            ApdexOverrides.MAX_PER_APP);
      }
      return ApdexOverrides.NONE;
    }
  }

  @Override
  public synchronized void destroy() {
    if (executor != null) {
      executor.shutdownNow();
      executor = null;
    }
  }
}
