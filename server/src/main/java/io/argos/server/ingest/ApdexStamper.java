/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ingest;

import io.argos.server.ingest.api.ApdexProvider;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

/**
 * The Apdex T stamp of D-17 (12 §3.5 (5)): {@code apps.apdex_t_ms} cached in memory and reloaded
 * every {@value #REFRESH_MS} ms by {@code argos-apdex-refresh}; an app not loaded yet gets the
 * {@code apps} default {@value #DEFAULT_APDEX_T_MS} ms and triggers a reload within a second. The
 * collector stamps the value at receipt into the session context and so into the WAL record ({@link
 * io.argos.server.wal.api.WalDataPayload}), so rows received after a change carry the new T and a
 * replay keeps the T of the receipt (forward only, T-SV-017). Service overrides and the retroactive
 * rebuild are WO-27's.
 */
@Component
public class ApdexStamper implements ApdexProvider, DisposableBean {

  /** {@code apps.apdex_t_ms} default (13 §12.2). */
  public static final int DEFAULT_APDEX_T_MS = 500;

  /** Reload period. */
  static final long REFRESH_MS = 5_000;

  private static final Logger LOG = LoggerFactory.getLogger(ApdexStamper.class);

  private final IngestStore store;
  private final Map<Integer, Integer> byApp = new ConcurrentHashMap<>();
  private volatile boolean reloadWanted = true;
  private volatile long lastRefreshNanos = System.nanoTime();
  private ScheduledExecutorService executor;

  public ApdexStamper(IngestStore store) {
    this.store = Objects.requireNonNull(store, "store");
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

  /**
   * Reloads every app's T now.
   *
   * @return {@code true} when the values were read
   */
  public boolean refresh() {
    lastRefreshNanos = System.nanoTime();
    try {
      Map<Integer, Integer> values = store.apdexByApp();
      byApp.putAll(values);
      byApp.keySet().retainAll(values.keySet());
      reloadWanted = false;
      return true;
    } catch (Exception e) {
      LOG.debug("Apdex T reload failed, the cached values stay: {}", e.toString());
      return false;
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
