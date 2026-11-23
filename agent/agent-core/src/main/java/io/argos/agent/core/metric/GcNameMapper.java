/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.metric;

import io.argos.agent.core.log.AgentLogger;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The 10 §11 GC name table (D-25, F-A09): a {@code GarbageCollectorMXBean} name to the {@code gc}
 * and {@code gc_type} labels and whether its time is pause time ({@code jvm_gc_pause_seconds_total}
 * holds pauses only, so a concurrent cycle is never read as a stop of the application).
 *
 * <table>
 *   <caption>GC name mapping</caption>
 *   <tr><th>MXBean name</th><th>gc</th><th>gc_type</th><th>pause</th></tr>
 *   <tr><td>G1 Young Generation / G1 Old Generation / G1 Concurrent GC</td><td>g1</td>
 *       <td>young / old / concurrent</td><td>young, old</td></tr>
 *   <tr><td>PS Scavenge / PS MarkSweep</td><td>parallel</td><td>young / old</td><td>both</td></tr>
 *   <tr><td>Copy / MarkSweepCompact</td><td>serial</td><td>young / old</td><td>both</td></tr>
 *   <tr><td>ParNew / ConcurrentMarkSweep</td><td>cms</td><td>young / old</td><td>young</td></tr>
 *   <tr><td>ZGC Pauses / ZGC Cycles / ZGC Major|Minor Pauses / ZGC Major|Minor Cycles</td>
 *       <td>zgc</td><td>pause / cycle</td><td>Pauses</td></tr>
 *   <tr><td>Shenandoah Pauses / Shenandoah Cycles</td><td>shenandoah</td><td>pause / cycle</td>
 *       <td>Pauses</td></tr>
 *   <tr><td>any other</td><td>the MXBean name</td><td>unknown</td><td>never (one WARN)</td></tr>
 * </table>
 *
 * <p>Several beans can map to one label pair (generational ZGC's major and minor beans): the
 * collector adds their values into one series.
 */
public final class GcNameMapper {
  /** {@code gc_type} of a name outside the table. */
  public static final String UNKNOWN = "unknown";

  /** The WARN of the first unknown name (tests count it). */
  interface Warning {
    void unknownCollector();
  }

  private static final Warning LOG =
      new Warning() {
        @Override
        public void unknownCollector() {
          AgentLogger.warnOnce(
              "metric.gc.unknown",
              "a garbage collector MXBean has a name outside the GC name table: its series carry"
                  + " gc_type=\"unknown\" with the MXBean name as gc and are left out of"
                  + " jvm_gc_pause_seconds_total");
        }
      };

  private final Warning warning;
  private final AtomicBoolean warned = new AtomicBoolean();

  /** A mapper that logs the first unknown name once. */
  public GcNameMapper() {
    this(LOG);
  }

  GcNameMapper(Warning warning) {
    this.warning = warning;
  }

  /** The labels of {@code mxbeanName}; an unknown name WARNs once per mapper. Never null. */
  public Mapping map(String mxbeanName) {
    Mapping m = lookup(mxbeanName);
    if (!m.known() && warned.compareAndSet(false, true)) {
      warning.unknownCollector();
    }
    return m;
  }

  /** The table lookup without the WARN. Never null. */
  public static Mapping lookup(String name) {
    String n = name == null ? "" : name;
    switch (n) {
      case "G1 Young Generation":
        return new Mapping("g1", "young", true, true);
      case "G1 Old Generation":
        return new Mapping("g1", "old", true, true);
      case "G1 Concurrent GC":
        return new Mapping("g1", "concurrent", false, true);
      case "PS Scavenge":
        return new Mapping("parallel", "young", true, true);
      case "PS MarkSweep":
        return new Mapping("parallel", "old", true, true);
      case "Copy":
        return new Mapping("serial", "young", true, true);
      case "MarkSweepCompact":
        return new Mapping("serial", "old", true, true);
      case "ParNew":
        return new Mapping("cms", "young", true, true);
      case "ConcurrentMarkSweep":
        return new Mapping("cms", "old", false, true);
      case "ZGC Pauses":
      case "ZGC Major Pauses":
      case "ZGC Minor Pauses":
        return new Mapping("zgc", "pause", true, true);
      case "ZGC Cycles":
      case "ZGC Major Cycles":
      case "ZGC Minor Cycles":
        return new Mapping("zgc", "cycle", false, true);
      case "Shenandoah Pauses":
        return new Mapping("shenandoah", "pause", true, true);
      case "Shenandoah Cycles":
        return new Mapping("shenandoah", "cycle", false, true);
      default:
        return new Mapping(n, UNKNOWN, false, false);
    }
  }

  /** The labels of one MXBean. */
  public static final class Mapping {
    private final String gc;
    private final String gcType;
    private final boolean pause;
    private final boolean known;

    Mapping(String gc, String gcType, boolean pause, boolean known) {
      this.gc = gc;
      this.gcType = gcType;
      this.pause = pause;
      this.known = known;
    }

    /** The {@code gc} label: g1, parallel, ..., or the MXBean name. */
    public String gc() {
      return gc;
    }

    /** The {@code gc_type} label: young, old, concurrent, pause, cycle or unknown. */
    public String gcType() {
      return gcType;
    }

    /** True when the bean's time counts in {@code jvm_gc_pause_seconds_total}. */
    public boolean pause() {
      return pause;
    }

    /** False for a name outside the table. */
    public boolean known() {
      return known;
    }

    @Override
    public String toString() {
      return gc + "/" + gcType + (pause ? "/pause" : "");
    }
  }
}
