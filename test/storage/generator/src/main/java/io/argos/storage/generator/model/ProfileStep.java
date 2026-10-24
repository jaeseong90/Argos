/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.generator.model;

import io.argos.storage.generator.rows.Row;
import io.argos.storage.generator.rows.RowBinaryWriter;

/** One profile_step row of a captured transaction (13 §3.2, step types of C-1). */
public final class ProfileStep implements Row {

  /** Step type values (C-1, 02 §7): SQL=1 ... SUMMARY=11. */
  public static final int SQL = 1;

  public static final int DBC = 2;
  public static final int COMMIT = 3;
  public static final int ROLLBACK = 4;
  public static final int HTTP = 5;
  public static final int METHOD = 6;
  public static final int ASYNC = 7;
  public static final int ERROR = 8;
  public static final int STACK = 9;
  public static final int MESSAGE = 10;
  public static final int SUMMARY = 11;

  private static final long[] NO_FRAMES = new long[0];

  public final Transaction tx;
  public int seq;
  public int parentSeq;
  public final int type;
  public final int startOffsetMs;
  public int elapsedMs;
  public int execMs;
  public int fetchMs;
  public final long nameId;
  public final long targetId;
  public long rows;
  public int status;
  public boolean error;
  public int repeatCount = 1;
  public int batchSize;
  public String detail = "";
  public long[] stack = NO_FRAMES;

  ProfileStep(
      Transaction tx, int type, int startOffsetMs, int elapsedMs, long nameId, long targetId) {
    this.tx = tx;
    this.type = type;
    this.startOffsetMs = startOffsetMs;
    this.elapsedMs = elapsedMs;
    this.nameId = nameId;
    this.targetId = targetId;
  }

  @Override
  public long tsMs() {
    return tx.tsMs;
  }

  @Override
  public void write(RowBinaryWriter w) {
    w.i32(tx.instance.app.id);
    w.string(tx.instance.app.env);
    w.i32(tx.instance.id);
    w.i64(tx.txId);
    w.i64(tx.tsMs);
    w.u8(tx.kind);
    w.u16(seq);
    w.u16(parentSeq);
    w.u8(type);
    w.i32(Math.max(0, startOffsetMs));
    w.i32(Math.max(0, elapsedMs));
    w.i32(Math.max(0, execMs));
    w.i32(Math.max(0, fetchMs));
    w.i64(nameId);
    w.i64(targetId);
    w.i32((int) Math.min(rows, 0xFFFFFFFFL));
    w.u16(status);
    w.u8(error ? 1 : 0);
    w.u16(Math.min(repeatCount, 0xFFFF));
    w.i32(batchSize);
    w.string(detail);
    w.string(""); // detail_enc: 'full' bind parameter mode is off (DSN-16-09)
    w.u64Array(stack, stack.length);
  }
}
