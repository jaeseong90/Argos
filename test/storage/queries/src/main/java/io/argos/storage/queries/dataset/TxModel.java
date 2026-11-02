/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.queries.dataset;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * The transaction model of the CI dataset, with the distributions of 13 §15 at small scale: 10
 * applications (prod 7, stg 3), 5 instances each, 10 services per application (Zipf s = 1.1), 2,000
 * SQL statements and 20 outgoing hosts (Zipf), 3 versions switching over the time range, HTTP 97 %
 * / BATCH 3 %, error rate 1 % over 50 error groups, elapsed log-normal (sigma 1.0, median 80 ms) +
 * 1 % outliers of 2..30 s, user id 30 % (hashed), remote IP 10,000 values (masked), request id 50
 * %, 3 distinct SQL per transaction on average, clock offset +-2 s per instance and +60 s for two
 * instances, trace ids carrying their Unix second in bytes 0..3 (13 §2).
 *
 * <p>Identifiers stand in for the xxh3_64 dictionary keys of 13 §2 (a 64-bit mix of a namespace and
 * an index). The model is deterministic for a seed.
 */
public final class TxModel {

  /** tx_id epoch, 2026-01-01T00:00:00Z (13 §2, DSN-02-09). */
  public static final long TX_EPOCH_MS = 1_767_225_600_000L;

  /** Latency bucket upper bounds of 13 §2 (ms); the 15th bucket is above 30 s. */
  static final int[] BUCKET_BOUNDS = {
    1, 2, 5, 10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 30000
  };

  static final int APPS = 10;
  static final int INSTANCES_PER_APP = 5;
  static final int SERVICES_PER_APP = 10;
  static final int SQL_STATEMENTS = 2000;
  static final int HOSTS = 20;
  static final int ERROR_GROUPS = 50;
  static final int USERS_PER_APP = 5000;
  static final int REMOTE_IPS = 10_000;

  private static final String[] METHODS = {"GET", "POST", "PUT", "DELETE"};

  private final Zipf services = new Zipf(SERVICES_PER_APP, 1.1);
  private final Zipf statements = new Zipf(SQL_STATEMENTS, 1.0);
  private final Zipf hosts = new Zipf(HOSTS, 1.0);
  private final Zipf users = new Zipf(USERS_PER_APP, 1.0);
  private final Zipf ips = new Zipf(REMOTE_IPS, 1.0);
  private final Map<Integer, Integer> clockOffsetMs = new HashMap<>();
  private final Map<Long, Integer> sequence = new HashMap<>();
  private final long startMs;
  private final long spanMs;
  private final int sequenceBase;

  /**
   * @param seed seed of the per-instance clock offsets
   * @param startMs start of the time range (version switches happen at 1/3 and 2/3 of it)
   * @param spanMs length of the time range
   * @param sequenceBase first tx_id sequence number per (instance, ms); extra blocks use a
   *     different base so that their ids never collide with the dataset's
   */
  public TxModel(long seed, long startMs, long spanMs, int sequenceBase) {
    this.startMs = startMs;
    this.spanMs = spanMs;
    this.sequenceBase = sequenceBase;
    SplittableRandom random = new SplittableRandom(seed ^ 0x5DEECE66DL);
    for (int app = 1; app <= APPS; app++) {
      for (int k = 1; k <= INSTANCES_PER_APP; k++) {
        clockOffsetMs.put(instanceId(app, k), random.nextInt(-2000, 2001));
      }
    }
    // Two instances with a clock 60 s ahead (13 §15).
    clockOffsetMs.put(instanceId(1, 4), 60_000);
    clockOffsetMs.put(instanceId(7, 3), 60_000);
  }

  /** Instance id of the k-th instance (1-based) of an application. */
  public static int instanceId(int app, int k) {
    return app * 100 + k;
  }

  /** Environment of an application (prod 1..7, stg 8..10). */
  public static String env(int app) {
    return app <= 7 ? "prod" : "stg";
  }

  /** Clock offset of an instance (ms). */
  public int clockOffsetMs(int instance) {
    return clockOffsetMs.getOrDefault(instance, 0);
  }

  /** Instances whose clock is 60 s ahead. */
  public List<Integer> skewedInstances() {
    List<Integer> skewed = new ArrayList<>();
    clockOffsetMs.forEach(
        (instance, offset) -> {
          if (offset >= 60_000) {
            skewed.add(instance);
          }
        });
    skewed.sort(null);
    return skewed;
  }

  /** 64-bit mix (splitmix64 finalizer): the stand-in for xxh3_64 dictionary keys. */
  public static long key(long namespace, long index) {
    long z = namespace * 0x9E3779B97F4A7C15L + index;
    z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
    z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
    return z ^ (z >>> 31);
  }

  /** Dictionary key of a service. */
  public static long serviceId(int app, int index) {
    return key(1, app * 1000L + index);
  }

  /** Dictionary key of a SQL statement. */
  public static long sqlId(int rank) {
    return key(2, rank);
  }

  /** Dictionary key of the JDBC target of an application. */
  public static long dbId(int app) {
    return key(3, app);
  }

  /** Dictionary key of an outgoing host. */
  public static long hostId(int index) {
    return key(4, index);
  }

  /** Dictionary key of an error group. */
  public static long errorGroupId(int index) {
    return key(5, index);
  }

  /** Bucket index (0..14) of a latency in ms (value <= bound, 13 §2). */
  public static int bucket(long ms) {
    for (int i = 0; i < BUCKET_BOUNDS.length; i++) {
      if (ms <= BUCKET_BOUNDS[i]) {
        return i;
      }
    }
    return BUCKET_BOUNDS.length;
  }

  /**
   * A new transaction of a random application.
   *
   * @param random random source
   * @param trueMs true (server) time of the start; the stored ts adds the instance clock offset
   */
  public Tx next(SplittableRandom random, long trueMs) {
    int app = 1 + random.nextInt(APPS);
    return transaction(random, app, trueMs, null);
  }

  /** A callee transaction of a distributed call made by {@code caller}. */
  public Tx callee(SplittableRandom random, Tx caller) {
    int app = caller.app() % APPS + 1;
    long trueMs = caller.tsMs() - clockOffsetMs(caller.instance()) + 3 + random.nextInt(15);
    return transaction(random, app, trueMs, caller);
  }

  private Tx transaction(SplittableRandom random, int app, long trueMs, Tx caller) {
    int instance = instanceId(app, 1 + random.nextInt(INSTANCES_PER_APP));
    long tsMs = trueMs + clockOffsetMs(instance);
    long txId = txId(tsMs, instance);
    boolean batch = caller == null && random.nextInt(100) < 3;
    int serviceIndex = services.sample(random);
    long serviceId = serviceId(app, serviceIndex);
    int version = (int) Math.min(2, Math.max(0, (trueMs - startMs) * 3 / Math.max(1, spanMs)));
    long appVersionId = key(6, app * 10L + version);
    int elapsed =
        random.nextInt(100) == 0
            ? 2000 + random.nextInt(28_001)
            : (int) Math.max(1, Math.round(80 * Math.exp(gaussian(random))));
    if (caller != null) {
      elapsed = Math.max(1, Math.min(elapsed, Math.max(1, caller.elapsedMs() - 5)));
    }
    boolean error = random.nextInt(100) == 0;
    int group = error ? random.nextInt(ERROR_GROUPS) : -1;

    List<SqlExec> sqls = new ArrayList<>();
    LinkedHashSet<Long> distinct = new LinkedHashSet<>();
    int statementsUsed = distinctSqlCount(random, batch);
    long dbId = dbId(app);
    for (int i = 0; i < statementsUsed; i++) {
      long sqlId = sqlId(statements.sample(random));
      distinct.add(sqlId);
      int executions = 1 + random.nextInt(batch ? 5 : 2);
      for (int e = 0; e < executions; e++) {
        int ms = (int) Math.max(0, Math.round(5 * Math.exp(gaussian(random))));
        int execMs = ms * 3 / 4;
        sqls.add(
            new SqlExec(sqlId, dbId, ms, execMs, random.nextInt(200), random.nextInt(200) == 0));
      }
    }
    List<ExtCall> exts = new ArrayList<>();
    int calls = caller == null ? random.nextInt(3) : random.nextInt(2);
    for (int i = 0; i < calls; i++) {
      int outcome = random.nextInt(1000);
      exts.add(
          new ExtCall(
              hostId(hosts.sample(random)),
              (int) Math.max(1, Math.round(30 * Math.exp(0.8 * gaussian(random)))),
              outcome < 10,
              outcome >= 10 && outcome < 13,
              outcome >= 13 && outcome < 15));
    }
    int sqlTime = sqls.stream().mapToInt(SqlExec::ms).sum();
    int extTime = exts.stream().mapToInt(ExtCall::ms).sum();
    boolean virtualThread = random.nextInt(10) == 0;
    boolean hasUser = random.nextInt(10) < 3;
    String userId =
        hasUser
            ? String.format(Locale.ROOT, "%016x", key(7, app * 100_000L + users.sample(random)))
            : "";
    String requestId =
        random.nextBoolean()
            ? String.format(Locale.ROOT, "req-%016x", key(8, txId ^ random.nextLong()))
            : "";
    byte[] traceId = new byte[16];
    long spanId = random.nextLong() | 1L;
    if (caller != null) {
      System.arraycopy(caller.traceId(), 0, traceId, 0, 16);
    } else {
      // Argos-generated trace id: Unix second (big-endian) in bytes 0..3, random after (13 §2).
      ByteBuffer.wrap(traceId)
          .putInt((int) (tsMs / 1000))
          .putLong(random.nextLong())
          .putInt(random.nextInt());
    }
    boolean captured = error || elapsed >= 2000 || random.nextInt(100) < 10;
    long[] sqlIds = distinct.stream().mapToLong(Long::longValue).toArray();
    int ip = ips.sample(random);
    byte[] remote = new byte[16];
    remote[10] = (byte) 0xFF;
    remote[11] = (byte) 0xFF;
    remote[12] = 10;
    remote[13] = (byte) (ip / 256);
    remote[14] = (byte) (ip % 256);
    remote[15] = 0; // masked: last octet removed (13 §3.1 ip_mode)
    String method = batch ? "" : METHODS[random.nextInt(10) < 7 ? 0 : 1 + random.nextInt(3)];
    int status = batch ? (error ? 1 : 0) : (error ? 500 : 200);
    return new Tx(
        app,
        instance,
        tsMs,
        txId,
        batch ? 2 : 1,
        traceId,
        spanId,
        caller == null ? 0 : caller.spanId(),
        caller == null ? 0 : 1,
        caller == null ? 0 : caller.app(),
        serviceId,
        appVersionId,
        method,
        status,
        elapsed,
        error,
        error ? key(9, group % 20) : 0,
        error ? errorGroupId(group) : 0,
        serviceIndex % 3 == 0 ? 300 : (serviceIndex % 3 == 1 ? 500 : 1000),
        remote,
        userId,
        requestId,
        sqls,
        exts,
        sqlTime,
        extTime,
        random.nextInt(10),
        virtualThread ? -1 : elapsed / 3,
        virtualThread ? -1 : random.nextInt(1 << 20),
        key(10, random.nextInt(8)),
        captured,
        virtualThread ? 1 : 0,
        sqlIds);
  }

  private static int distinctSqlCount(SplittableRandom random, boolean batch) {
    // 0..8 statements, mean about 3 (13 §15 "sql_ids 3 on average").
    int[] weights = {8, 15, 20, 20, 15, 10, 6, 4, 2};
    int r = random.nextInt(100);
    int acc = 0;
    for (int i = 0; i < weights.length; i++) {
      acc += weights[i];
      if (r < acc) {
        return batch ? Math.min(8, i + 2) : i;
      }
    }
    return 3;
  }

  private long txId(long tsMs, int instance) {
    long node = instance & 0xFFFL;
    long slot = (tsMs << 12) | node;
    int seq = sequence.merge(slot, 1, Integer::sum) - 1 + sequenceBase;
    if (sequence.size() > 200_000) {
      sequence.clear(); // bounded: ms slots in the past are not reused by the generator
    }
    return ((tsMs - TX_EPOCH_MS) << 23) | (node << 11) | (seq & 0x7FFL);
  }

  private static double gaussian(SplittableRandom random) {
    double u1 = 1.0 - random.nextDouble();
    double u2 = random.nextDouble();
    return Math.sqrt(-2 * Math.log(u1)) * Math.cos(2 * Math.PI * u2);
  }

  /** One SQL execution of a transaction. */
  public record SqlExec(long sqlId, long dbId, int ms, int execMs, int rows, boolean error) {}

  /** One outgoing call of a transaction. */
  public record ExtCall(long hostId, int ms, boolean error, boolean timeout, boolean connectFail) {}

  /** A generated transaction (one xlog row and its SQL, outgoing calls and steps). */
  @SuppressWarnings("ArrayRecordComponent") // generated data, never compared by value
  public record Tx(
      int app,
      int instance,
      long tsMs,
      long txId,
      int kind,
      byte[] traceId,
      long spanId,
      long parentSpanId,
      int incomingTrace,
      int callerApp,
      long serviceId,
      long appVersionId,
      String httpMethod,
      int httpStatus,
      int elapsedMs,
      boolean error,
      long errorClassId,
      long errorGroupId,
      int apdexTms,
      byte[] remoteIp,
      String userId,
      String requestId,
      List<SqlExec> sqls,
      List<ExtCall> exts,
      int sqlTimeMs,
      int extTimeMs,
      int dbcWaitMs,
      int cpuTimeMs,
      long allocBytes,
      long threadNameId,
      boolean profileCaptured,
      int flags,
      long[] sqlIds) {

    /** Environment of the application. */
    public String env() {
      return TxModel.env(app);
    }
  }

  /** Zipf sampler over ranks 0..n-1 with exponent s (inverse CDF, binary search). */
  static final class Zipf {
    private final double[] cdf;

    Zipf(int n, double s) {
      cdf = new double[n];
      double sum = 0;
      for (int i = 0; i < n; i++) {
        sum += 1 / Math.pow(i + 1, s);
        cdf[i] = sum;
      }
      for (int i = 0; i < n; i++) {
        cdf[i] /= sum;
      }
    }

    int sample(SplittableRandom random) {
      double u = random.nextDouble();
      int lo = 0;
      int hi = cdf.length - 1;
      while (lo < hi) {
        int mid = (lo + hi) >>> 1;
        if (cdf[mid] < u) {
          lo = mid + 1;
        } else {
          hi = mid;
        }
      }
      return lo;
    }
  }
}
