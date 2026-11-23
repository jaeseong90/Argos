/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.metric;

import io.argos.proto.v1.MetricBatch;
import io.argos.proto.v1.MetricKind;
import io.argos.proto.v1.MetricSample;
import io.argos.proto.v1.MetricSeries;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * The metric series of one agent (10 §11, 11 §4.4 {@code MetricBatch}, 13 §3.3, §4.6): a series is
 * an OpenMetrics name with its labels ({@code jvm_memory_used_bytes{area="heap"}}) and a kind,
 * gauge or counter. One collection records values ({@link #gauge}, {@link #counter}) and {@link
 * #drain} turns them into one batch: the series dictionary of the batch and one sample per series,
 * all at the collection's grid time.
 *
 * <p><b>Delta</b> (C-15, DSN-13-04, 13 §4.6): a counter sample carries its cumulative value and the
 * reset-corrected increase since the previous sample of the series: {@code value - previous}, or
 * {@code value} itself when the value went down (a reset: the counter restarted from 0), and {@code
 * value} for the first sample (the previous value of a new process is 0). A gauge's delta is 0.
 *
 * <p><b>Bounds</b>: at most {@code metrics.max-series} (≤ 500) series. {@value #SERIES_DROPPED} is
 * registered first, so it always has a place; a sample of any further new series is dropped and
 * counted in it (once per dropped sample, so its increase shows how many samples were lost per
 * interval). A series not recorded for {@value #EXPIRE_AFTER} collections (a pool or a connector
 * that is gone) gives its place back; when it comes back it starts again from a previous value of
 * 0. A name longer than {@value #MAX_NAME_BYTES} UTF-8 bytes (11 §4.4) is dropped and counted the
 * same way ({@link #name} cuts label values so that a name fits). Every structure is bounded by the
 * cap.
 *
 * <p>Not thread-safe: used by the metrics thread only.
 */
public final class MetricRegistry {
  /** The 11 §4.4 cap of a series name (UTF-8 bytes). */
  public static final int MAX_NAME_BYTES = 256;

  /** Samples of series over the cap or with a name over {@value #MAX_NAME_BYTES} bytes. */
  public static final String SERIES_DROPPED = "argos_agent_metric_series_dropped_total";

  /** Collections without a sample after which a series gives its place back. */
  static final int EXPIRE_AFTER = 120;

  private final int maxSeries;
  private final Map<String, Series> series;
  private final boolean[] usedIndexes;
  private final List<Series> recorded;
  private final Series dropped;
  private long droppedTotal;
  private long cycle;

  /** A registry of at most {@code maxSeries} series (at least 1: {@value #SERIES_DROPPED}). */
  public MetricRegistry(int maxSeries) {
    this.maxSeries = Math.max(1, maxSeries);
    this.series = new HashMap<String, Series>(this.maxSeries * 2);
    this.usedIndexes = new boolean[this.maxSeries];
    this.recorded = new ArrayList<Series>(this.maxSeries);
    this.dropped = add(SERIES_DROPPED, MetricKind.COUNTER);
  }

  /**
   * Records gauge {@code name} = {@code value} for this collection; NaN and infinities are skipped.
   */
  public void gauge(String name, double value) {
    record(name, MetricKind.GAUGE, value);
  }

  /**
   * Records the cumulative value of counter {@code name} for this collection; negative values, NaN
   * and infinities are skipped (a missing value, C-4).
   */
  public void counter(String name, double cumulative) {
    if (cumulative < 0) {
      return;
    }
    record(name, MetricKind.COUNTER, cumulative);
  }

  /** {@link #gauge(String, double)} of an integral value (exact up to 2^53). */
  public void gauge(String name, long value) {
    gauge(name, (double) value);
  }

  /** {@link #counter(String, double)} of an integral value (exact up to 2^53). */
  public void counter(String name, long cumulative) {
    counter(name, (double) cumulative);
  }

  private void record(String name, MetricKind kind, double value) {
    if (name == null || Double.isNaN(value) || Double.isInfinite(value)) {
      return;
    }
    Series s = series.get(name);
    if (s == null) {
      if (series.size() >= maxSeries || !fits(name)) {
        droppedTotal++;
        return;
      }
      s = add(name, kind);
    }
    if (s.kind != kind) {
      // One name, one kind: the first registration wins.
      return;
    }
    if (!s.pending) {
      s.pending = true;
      recorded.add(s);
    }
    s.value = value;
  }

  private Series add(String name, MetricKind kind) {
    int index = 0;
    while (usedIndexes[index]) {
      index++;
    }
    usedIndexes[index] = true;
    Series s = new Series(name, kind, index);
    series.put(name, s);
    return s;
  }

  /**
   * The batch of this collection (series dictionary and samples at {@code tsMs}), {@value
   * #SERIES_DROPPED} included; series silent for {@value #EXPIRE_AFTER} collections are released.
   * The next collection starts empty. Never null.
   */
  public MetricBatch drain(long tsMs) {
    cycle++;
    counter(SERIES_DROPPED, droppedTotal);
    MetricBatch.Builder batch = MetricBatch.newBuilder();
    for (Series s : recorded) {
      double delta;
      if (s.kind == MetricKind.COUNTER) {
        delta = !s.hasPrevious || s.value < s.previous ? s.value : s.value - s.previous;
        s.previous = s.value;
        s.hasPrevious = true;
      } else {
        delta = 0;
      }
      s.pending = false;
      s.lastCycle = cycle;
      batch.addSeries(
          MetricSeries.newBuilder().setIndex(s.index).setName(s.name).setKind(s.kind).build());
      batch.addSamples(
          MetricSample.newBuilder()
              .setSeriesIndex(s.index)
              .setTsMs(tsMs)
              .setValue(s.value)
              .setDelta(delta)
              .build());
    }
    recorded.clear();
    expire();
    return batch.build();
  }

  private void expire() {
    Iterator<Series> it = series.values().iterator();
    while (it.hasNext()) {
      Series s = it.next();
      if (s != dropped && cycle - s.lastCycle >= EXPIRE_AFTER) {
        usedIndexes[s.index] = false;
        it.remove();
      }
    }
  }

  /** Series registered now ({@value #SERIES_DROPPED} included). */
  public int size() {
    return series.size();
  }

  /** The cap of this registry. */
  public int maxSeries() {
    return maxSeries;
  }

  /** Samples dropped so far ({@value #SERIES_DROPPED}). */
  public long droppedTotal() {
    return droppedTotal;
  }

  static boolean fits(String name) {
    return name.length() <= MAX_NAME_BYTES
        || name.getBytes(StandardCharsets.UTF_8).length <= MAX_NAME_BYTES;
  }

  // --- names ----------------------------------------------------------------------------------

  /**
   * The OpenMetrics series name {@code base{k1="v1",k2="v2"}} of {@code base} and label pairs
   * ({@code key, value, key, value, ...}); a pair with a null value is left out. Values are escaped
   * ({@code \\}, {@code \"}, {@code \n}) and cut so that the name fits {@value #MAX_NAME_BYTES}
   * UTF-8 bytes (each value gets an equal share of what the base and keys leave; a cut never splits
   * an escape or a surrogate pair).
   */
  public static String name(String base, String... labels) {
    int pairs = 0;
    int fixed = utf8Length(base);
    for (int i = 0; i + 1 < labels.length; i += 2) {
      if (labels[i + 1] != null) {
        pairs++;
        // key="value" plus a comma or a brace.
        fixed += utf8Length(labels[i]) + 4;
      }
    }
    if (pairs == 0) {
      return base;
    }
    fixed += 1;
    int share = Math.max(0, (MAX_NAME_BYTES - fixed) / pairs);
    StringBuilder out = new StringBuilder(MAX_NAME_BYTES).append(base).append('{');
    boolean first = true;
    for (int i = 0; i + 1 < labels.length; i += 2) {
      if (labels[i + 1] == null) {
        continue;
      }
      if (!first) {
        out.append(',');
      }
      first = false;
      out.append(labels[i]).append("=\"");
      escape(out, labels[i + 1], share);
      out.append('"');
    }
    return out.append('}').toString();
  }

  /** Appends {@code value} escaped, at most {@code maxBytes} UTF-8 bytes of escaped text. */
  static void escape(StringBuilder out, String value, int maxBytes) {
    int used = 0;
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      String piece;
      int bytes;
      if (c == '\\') {
        piece = "\\\\";
        bytes = 2;
      } else if (c == '"') {
        piece = "\\\"";
        bytes = 2;
      } else if (c == '\n') {
        piece = "\\n";
        bytes = 2;
      } else if (Character.isHighSurrogate(c)
          && i + 1 < value.length()
          && Character.isLowSurrogate(value.charAt(i + 1))) {
        piece = value.substring(i, i + 2);
        bytes = 4;
      } else if (Character.isSurrogate(c)) {
        // A lone surrogate is not text: replaced.
        piece = "?";
        bytes = 1;
      } else {
        piece = null;
        bytes = c < 0x80 ? 1 : c < 0x800 ? 2 : 3;
      }
      if (used + bytes > maxBytes) {
        return;
      }
      used += bytes;
      if (piece == null) {
        out.append(c);
      } else {
        out.append(piece);
        if (piece.length() == 2 && Character.isHighSurrogate(c)) {
          i++;
        }
      }
    }
  }

  static int utf8Length(String s) {
    int bytes = 0;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c < 0x80) {
        bytes++;
      } else if (c < 0x800) {
        bytes += 2;
      } else if (Character.isHighSurrogate(c)) {
        bytes += 4;
        i++;
      } else {
        bytes += 3;
      }
    }
    return bytes;
  }

  /** One series: name, kind, its stable index and the counter's previous value. */
  private static final class Series {
    final String name;
    final MetricKind kind;
    final int index;
    double value;
    double previous;
    boolean hasPrevious;
    boolean pending;
    long lastCycle;

    Series(String name, MetricKind kind, int index) {
      this.name = name;
      this.kind = kind;
      this.index = index;
    }
  }
}
