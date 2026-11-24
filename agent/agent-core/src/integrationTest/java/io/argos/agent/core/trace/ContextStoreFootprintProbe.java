/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.trace;

import io.argos.agent.bootstrap.ContextCarrier;
import io.argos.agent.bootstrap.ContextStore;
import io.argos.agent.bootstrap.Kind;
import io.argos.agent.bootstrap.TraceContext;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.management.MBeanServer;
import javax.management.ObjectName;

/**
 * Forked JVM of {@link ContextStoreFootprintTest} (ADR-0010 C-120 (3)): fills the task store {@code
 * ContextStore.of(Runnable, TraceContext)} to its entry cap ({@link
 * ContextStore#DEFAULT_MAX_ENTRIES}) twice, once with keys that use the weak-map fallback ({@code
 * WeakContextStore}: JDK task types, classes loaded before the agent) and once with keys that carry
 * the injected field ({@code FieldBackedContextStore}, {@link ContextCarrier}). Keys and the one
 * shared value are allocated before the first {@code GC.class_histogram}, so the histogram growth
 * is the store's own memory. Prints {@code key=value} lines.
 */
final class ContextStoreFootprintProbe {
  private ContextStoreFootprintProbe() {}

  /** A task class without the injected field (the weak-map path). */
  static final class PlainTask implements Runnable {
    final int id;

    PlainTask(int id) {
      this.id = id;
    }

    @Override
    public void run() {}
  }

  /** A task class as the core defines it with the injected field (the field path). */
  static final class CarrierTask implements Runnable, ContextCarrier {
    final int id;
    private volatile TraceContext context;

    CarrierTask(int id) {
      this.id = id;
    }

    @Override
    public void run() {}

    @Override
    public TraceContext argos$getContext() {
      return context;
    }

    @Override
    public void argos$setContext(TraceContext ctx) {
      context = ctx;
    }
  }

  public static void main(String[] args) throws Exception {
    int entries = ContextStore.DEFAULT_MAX_ENTRIES;
    TraceContext shared =
        new TraceContext(1L, 0L, 0L, 0L, 0L, Kind.HTTP, 0L, 0L, null, false, -1L, -1L);
    PlainTask[] plain = new PlainTask[entries + 1];
    CarrierTask[] carriers = new CarrierTask[entries];
    for (int i = 0; i < plain.length; i++) {
      plain[i] = new PlainTask(i);
    }
    for (int i = 0; i < carriers.length; i++) {
      carriers[i] = new CarrierTask(i);
    }
    ContextStore<Runnable, TraceContext> store =
        ContextStore.of(Runnable.class, TraceContext.class);
    out(
        "store_is_field_backed",
        store.getClass().getSimpleName().equals("FieldBackedContextStore"));

    Histogram before = Histogram.take();
    for (int i = 0; i < entries; i++) {
      store.put(plain[i], shared);
    }
    Histogram weak = Histogram.take();
    out("weak_entries", store.size());
    out("weak_dropped", store.droppedEntries());
    long weakBytes = weak.total - before.total;
    out("weak_growth_bytes", weakBytes);
    out("weak_bytes_per_entry_x100", weakBytes * 100 / entries);
    for (String name :
        new String[] {
          "io.argos.agent.bootstrap.WeakContextStore$WeakKey",
          "java.util.concurrent.ConcurrentHashMap$Node",
          "[Ljava.util.concurrent.ConcurrentHashMap$Node;"
        }) {
      out(
          "weak_class_bytes." + name.replace('$', '_').replace("[L", "array.").replace(";", ""),
          weak.bytes(name) - before.bytes(name));
    }

    // One more weak-path key beyond the cap: dropped and counted, nothing stored.
    store.put(plain[entries], shared);
    out("weak_entries_after_cap", store.size());
    out("weak_dropped_after_cap", store.droppedEntries());

    Histogram beforeField = Histogram.take();
    for (int i = 0; i < entries; i++) {
      store.put(carriers[i], shared);
    }
    Histogram field = Histogram.take();
    out("field_growth_bytes", field.total - beforeField.total);
    out("field_bytes_per_entry_x100", (field.total - beforeField.total) * 100 / entries);
    out("field_store_size_after", store.size());
    long plainInstance = field.instanceBytes(PlainTask.class.getName());
    long carrierInstance = field.instanceBytes(CarrierTask.class.getName());
    out("plain_task_instance_bytes", plainInstance);
    out("carrier_task_instance_bytes", carrierInstance);
    out("field_key_extra_bytes", carrierInstance - plainInstance);

    int found = 0;
    for (int i = 0; i < entries; i++) {
      if (store.get(plain[i]) == shared) {
        found++;
      }
      if (store.get(carriers[i]) == shared) {
        found++;
      }
    }
    out("found", found);
    System.out.println("java_version_text=" + System.getProperty("java.version"));
  }

  private static void out(String key, long value) {
    System.out.println(String.format(Locale.ROOT, "%s=%d", key, value));
  }

  private static void out(String key, boolean value) {
    out(key, value ? 1L : 0L);
  }

  /** One {@code GC.class_histogram} (a full GC first): total bytes and per-class counts. */
  static final class Histogram {
    static final List<String> KEPT =
        Arrays.asList(
            "io.argos.agent.bootstrap.WeakContextStore$WeakKey",
            "java.util.concurrent.ConcurrentHashMap$Node",
            "[Ljava.util.concurrent.ConcurrentHashMap$Node;",
            PlainTask.class.getName(),
            CarrierTask.class.getName());

    final long total;
    final Map<String, long[]> classes;

    private Histogram(long total, Map<String, long[]> classes) {
      this.total = total;
      this.classes = classes;
    }

    static Histogram take() throws Exception {
      MBeanServer server = ManagementFactory.getPlatformMBeanServer();
      String text =
          (String)
              server.invoke(
                  new ObjectName("com.sun.management:type=DiagnosticCommand"),
                  "gcClassHistogram",
                  new Object[] {new String[0]},
                  new String[] {String[].class.getName()});
      long total = 0;
      // Only the classes of interest are kept: a map of every class would itself show up as
      // growth in the next histogram.
      Map<String, long[]> classes = new HashMap<String, long[]>();
      for (String line : text.split("\n", -1)) {
        String[] parts = line.trim().split("\\s+", -1);
        if (parts.length >= 3 && parts[0].equals("Total")) {
          total = Long.parseLong(parts[2]);
        } else if (parts.length >= 4 && parts[0].endsWith(":") && KEPT.contains(parts[3])) {
          classes.put(parts[3], new long[] {Long.parseLong(parts[1]), Long.parseLong(parts[2])});
        }
      }
      return new Histogram(total, classes);
    }

    long bytes(String className) {
      long[] counts = classes.get(className);
      return counts == null ? 0 : counts[1];
    }

    long instanceBytes(String className) {
      long[] counts = classes.get(className);
      return counts == null || counts[0] == 0 ? 0 : counts[1] / counts[0];
    }
  }
}
