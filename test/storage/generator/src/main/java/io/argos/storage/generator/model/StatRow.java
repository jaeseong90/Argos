/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.generator.model;

import io.argos.storage.generator.model.Topology.Instance;
import io.argos.storage.generator.rows.Row;
import io.argos.storage.generator.rows.RowBinaryWriter;

/**
 * One 10 s complete statistics row (13 §3.4 sql_stat_raw or §3.5 ext_stat_raw): the agent's window
 * aggregate of one (sql_id, db_id, kind) or (host_id, kind) key with the 15-bucket histogram of 13
 * §2.
 */
public final class StatRow implements Row {

  /** Upper bounds (ms, inclusive) of the first 14 latency buckets; the 15th is above 30 s. */
  private static final int[] BUCKET_BOUNDS = {
    1, 2, 5, 10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 30000
  };

  /** Number of buckets (13 §2). */
  public static final int BUCKETS = BUCKET_BOUNDS.length + 1;

  private final boolean sql;
  public final Instance instance;
  public final int kind;
  public final long keyId;
  public final long dbId;
  public long windowAgentMs;
  public long tsMs;
  public boolean clamped;
  public long count;
  public long errorCount;
  public long timeoutCount;
  public long connectFailCount;
  public long sumMs;
  public long sumExecMs;
  public long sumFetchMs;
  public long maxMs;
  public long fetchRows;
  public final long[] buckets = new long[BUCKETS];

  private StatRow(boolean sql, Instance instance, int kind, long keyId, long dbId) {
    this.sql = sql;
    this.instance = instance;
    this.kind = kind;
    this.keyId = keyId;
    this.dbId = dbId;
  }

  /** An empty sql_stat_raw aggregate. */
  public static StatRow sql(Instance instance, int kind, long sqlId, long dbId) {
    return new StatRow(true, instance, kind, sqlId, dbId);
  }

  /** An empty ext_stat_raw aggregate. */
  public static StatRow ext(Instance instance, int kind, long hostId) {
    return new StatRow(false, instance, kind, hostId, 0);
  }

  /** True for sql_stat_raw, false for ext_stat_raw. */
  public boolean sql() {
    return sql;
  }

  /** Upper bound (ms, inclusive) of bucket {@code i}; {@code Long.MAX_VALUE} for the last. */
  public static long bucketUpperBound(int i) {
    return i < BUCKET_BOUNDS.length ? BUCKET_BOUNDS[i] : Long.MAX_VALUE;
  }

  /** Bucket index of a duration (13 §2: upper bounds inclusive). */
  public static int bucket(long ms) {
    for (int i = 0; i < BUCKET_BOUNDS.length; i++) {
      if (ms <= BUCKET_BOUNDS[i]) {
        return i;
      }
    }
    return BUCKET_BOUNDS.length;
  }

  /** Adds one SQL execution. */
  public void addSql(long ms, long execMs, long rows, boolean error) {
    count++;
    errorCount += error ? 1 : 0;
    sumMs += ms;
    sumExecMs += execMs;
    sumFetchMs += ms - execMs;
    maxMs = Math.max(maxMs, ms);
    fetchRows += rows;
    buckets[bucket(ms)]++;
  }

  /** Adds one outgoing call. */
  public void addExt(long ms, boolean error, boolean timeout, boolean connectFail) {
    count++;
    errorCount += error ? 1 : 0;
    timeoutCount += timeout ? 1 : 0;
    connectFailCount += connectFail ? 1 : 0;
    sumMs += ms;
    maxMs = Math.max(maxMs, ms);
    buckets[bucket(ms)]++;
  }

  @Override
  public long tsMs() {
    return tsMs;
  }

  @Override
  public void write(RowBinaryWriter w) {
    w.i32(instance.app.id);
    w.string(instance.app.env);
    w.i32(instance.id);
    w.i32((int) (tsMs / 1000));
    w.u8(kind);
    w.i64(keyId);
    if (sql) {
      w.i64(dbId);
      w.i32((int) count);
      w.i32((int) errorCount);
      w.i64(sumMs);
      w.i64(sumExecMs);
      w.i64(sumFetchMs);
      w.i32((int) maxMs);
      w.i64(fetchRows);
    } else {
      w.i32((int) count);
      w.i32((int) errorCount);
      w.i32((int) timeoutCount);
      w.i32((int) connectFailCount);
      w.i64(sumMs);
      w.i32((int) maxMs);
    }
    w.u32Array(buckets);
  }
}
