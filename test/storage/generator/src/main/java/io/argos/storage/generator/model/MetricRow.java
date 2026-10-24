/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.generator.model;

import io.argos.storage.generator.model.Topology.Instance;
import io.argos.storage.generator.rows.Row;
import io.argos.storage.generator.rows.RowBinaryWriter;

/** One metric sample on the 5 s grid (13 §3.3). */
public final class MetricRow implements Row {

  public final Instance instance;
  public final String name;
  public final int kind;
  public final long tsMs;
  public final double value;
  public final double delta;
  public final boolean clamped;

  public MetricRow(
      Instance instance,
      String name,
      int kind,
      long tsMs,
      double value,
      double delta,
      boolean clamped) {
    this.instance = instance;
    this.name = name;
    this.kind = kind;
    this.tsMs = tsMs;
    this.value = value;
    this.delta = delta;
    this.clamped = clamped;
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
    w.string(name);
    w.u8(kind);
    w.i32((int) (tsMs / 1000));
    w.f64(value);
    w.f64(delta);
  }
}
