/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.common.db;

import io.argos.sample.common.async.NamedThreadFactory;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * GET /api/pool/exhaust?hold= (18 §4.2, S-10): real pool exhaustion. {@code maxPoolSize} holder
 * threads take every connection of the pool and keep it for {@code hold} ms; then the request
 * thread asks for one more connection (maxPoolSize + 1 in total) and waits until a holder returns
 * one. The wait is a DBC wait step for the agent and shows up in the pool metrics (active = max,
 * pending &gt; 0). One exhaustion runs at a time. The answer says how many holders really took a
 * connection ({@code held}, {@code failedHolders}); {@code allHeld} is true only when every holder
 * did.
 */
public final class PoolExhauster {

  /** Upper bound of {@code hold} (pool wait timeouts are 30 s). */
  public static final long MAX_HOLD_MS = 20_000;

  private final JdbcAccess access;
  private final int maxPoolSize;
  private final Semaphore running = new Semaphore(1);

  /** The statement after the wait, found on first use ({@link #pingSql}). */
  private volatile String pingSql;

  public PoolExhauster(JdbcAccess access, int maxPoolSize) {
    this.access = access;
    this.maxPoolSize = maxPoolSize;
  }

  /**
   * Exhausts the pool. Returns null when another exhaustion is running (the web layer answers 409).
   */
  public Map<String, Object> exhaust(long holdMs) throws InterruptedException {
    if (holdMs < 1 || holdMs > MAX_HOLD_MS) {
      throw new IllegalArgumentException("hold must be between 1 and " + MAX_HOLD_MS + " ms");
    }
    if (!running.tryAcquire()) {
      return null;
    }
    ExecutorService holders =
        Executors.newFixedThreadPool(maxPoolSize, new NamedThreadFactory("pool-holder-", true));
    try {
      CountDownLatch acquired = new CountDownLatch(maxPoolSize);
      AtomicInteger held = new AtomicInteger();
      AtomicInteger failed = new AtomicInteger();
      for (int i = 0; i < maxPoolSize; i++) {
        holders.execute(() -> hold(acquired, held, failed, holdMs));
      }
      boolean allDone = acquired.await(30, TimeUnit.SECONDS);
      int heldCount = held.get();
      int failedCount = failed.get();
      long start = System.nanoTime();
      Connection connection;
      try {
        connection = access.acquire();
        // A lazy connection proxy (TransactionAwareDataSourceProxy in the SAMPLE_ROUTING_DS chain)
        // takes the pooled connection on first use: the pool wait happens here.
        connection.getAutoCommit();
      } catch (SQLException e) {
        throw new ShopDataException("Pool wait failed: " + e.getMessage(), e);
      }
      long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
      try (Statement st = connection.createStatement()) {
        st.execute(pingSql(connection));
      } catch (SQLException e) {
        throw new ShopDataException(e.getMessage(), e);
      } finally {
        release(connection);
      }
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("maxPoolSize", maxPoolSize);
      result.put("connectionsRequested", maxPoolSize + 1);
      result.put("allHeld", allDone && heldCount == maxPoolSize);
      result.put("held", heldCount);
      result.put("failedHolders", failedCount);
      result.put("holdMs", holdMs);
      result.put("waitedMs", waitedMs);
      return result;
    } finally {
      try {
        holders.shutdown();
        if (!holders.awaitTermination(holdMs + 30_000, TimeUnit.MILLISECONDS)) {
          holders.shutdownNow();
        }
      } finally {
        running.release();
      }
    }
  }

  /**
   * {@code SELECT 1}, or {@code SELECT 1 FROM DUAL} on Oracle (SAMPLE_DB=oracle), decided once from
   * the database product name: the exhauster is built without the {@link DbKind}.
   */
  private String pingSql(Connection connection) throws SQLException {
    String known = pingSql;
    if (known == null) {
      java.sql.DatabaseMetaData metaData = connection.getMetaData();
      String product = metaData == null ? null : metaData.getDatabaseProductName();
      boolean oracle =
          product != null && product.toLowerCase(java.util.Locale.ROOT).contains("oracle");
      known = (oracle ? DbKind.ORACLE : DbKind.H2).pingSql();
      pingSql = known;
    }
    return known;
  }

  private void hold(
      CountDownLatch acquired, AtomicInteger held, AtomicInteger failed, long holdMs) {
    Connection connection = null;
    try {
      connection = access.acquire();
      connection.getAutoCommit(); // takes the pooled connection behind a lazy proxy
      held.incrementAndGet();
      acquired.countDown();
      Thread.sleep(holdMs);
    } catch (SQLException e) {
      failed.incrementAndGet();
      acquired.countDown();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      if (connection != null) {
        release(connection);
      }
    }
  }

  private void release(Connection connection) {
    try {
      access.release(connection);
    } catch (SQLException ignored) {
      // The pool reclaims the connection; nothing else to do in a sample app.
    }
  }
}
