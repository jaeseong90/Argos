/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.trace;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.bootstrap.ContextStore;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * ADR-0010 C-120 (3): bytes per {@code ContextStore} entry (10 §6.6, §14). The store keeps its
 * entry-count cap ({@link ContextStore#DEFAULT_MAX_ENTRIES} per store); this measures what one
 * entry costs so that 10 §14 can account for it. In a forked JVM ({@link
 * ContextStoreFootprintProbe}, the test JDK, {@code -Xmx512m}), {@code GC.class_histogram} growth
 * after filling the task store to its cap:
 *
 * <ul>
 *   <li>weak-map path ({@code WeakContextStore}: weak key + {@code ConcurrentHashMap} node + table
 *       slot), every entry stored up to the cap, the next one dropped and counted;
 *   <li>field path ({@code FieldBackedContextStore}): no heap growth per entry; the cost is the
 *       injected reference field in each task object (instance size of a carrier class minus the
 *       same class without the field).
 * </ul>
 *
 * The values are printed as a {@code [c120-contextstore]} line (reference values of this machine's
 * JVM layout: compressed oops under 32 GB heaps).
 */
@Tag("slow")
@Tag("req:N-02")
class ContextStoreFootprintTest {
  /** Sanity bound of one weak-map entry (weak key 32 + node 32 + table slots on 64-bit JVMs). */
  private static final long WEAK_ENTRY_MAX_BYTES = 128;

  @Test
  @DisplayName(
      "C-120 (3) [c120-contextstore] ContextStore bytes per entry: weak-map path within 128 bytes"
          + " and bounded by the entry cap (next entry dropped and counted), field path no heap"
          + " growth (one field per task object)")
  void bytesPerEntry() throws Exception {
    Map<String, String> values = runProbe();
    int cap = ContextStore.DEFAULT_MAX_ENTRIES;
    double weakPerEntry = number(values, "weak_bytes_per_entry_x100") / 100.0;
    double fieldPerEntry = number(values, "field_bytes_per_entry_x100") / 100.0;
    long weakAtCap = number(values, "weak_growth_bytes");
    System.out.println(
        String.format(
            Locale.ROOT,
            "[c120-contextstore] jdk=%s cap=%d weak_bytes_per_entry=%.1f weak_bytes_at_cap=%d"
                + " (%.2f MiB per store) weak_key=%d node=%d table=%d field_path_heap_per_entry=%.2f"
                + " field_key_extra_bytes=%d (plain task %d, carrier task %d bytes per instance)"
                + " (reference values, this machine's JVM layout)",
            values.get("java_version_text"),
            cap,
            weakPerEntry,
            weakAtCap,
            weakAtCap / (1024.0 * 1024.0),
            number(values, "weak_class_bytes.io.argos.agent.bootstrap.WeakContextStore_WeakKey"),
            number(values, "weak_class_bytes.java.util.concurrent.ConcurrentHashMap_Node"),
            number(values, "weak_class_bytes.array.java.util.concurrent.ConcurrentHashMap_Node"),
            fieldPerEntry,
            number(values, "field_key_extra_bytes"),
            number(values, "plain_task_instance_bytes"),
            number(values, "carrier_task_instance_bytes")));

    assertThat(number(values, "store_is_field_backed"))
        .as("the task store is field backed with a weak-map fallback")
        .isEqualTo(1);
    assertThat(number(values, "weak_entries")).isEqualTo(cap);
    assertThat(number(values, "weak_dropped")).isZero();
    assertThat(number(values, "weak_entries_after_cap"))
        .as("entry cap: nothing stored beyond it")
        .isEqualTo(cap);
    assertThat(number(values, "weak_dropped_after_cap"))
        .as("the entry beyond the cap is counted as dropped")
        .isEqualTo(1);
    assertThat(weakPerEntry)
        .as("weak-map path bytes per entry")
        .isGreaterThan(0)
        .isLessThanOrEqualTo(WEAK_ENTRY_MAX_BYTES);
    assertThat(fieldPerEntry)
        .as("field path: no map entry, no heap growth per entry")
        .isLessThanOrEqualTo(1.0);
    assertThat(number(values, "field_store_size_after"))
        .as("field-path entries are not map entries")
        .isEqualTo(cap);
    assertThat(number(values, "field_key_extra_bytes"))
        .as("one reference field per task object (alignment: 0 or 8 bytes)")
        .isBetween(0L, 8L);
    assertThat(number(values, "found")).as("every entry readable").isEqualTo(2L * cap);
  }

  private static long number(Map<String, String> values, String key) {
    String value = values.get(key);
    assertThat(value).as("probe value %s in %s", key, values).isNotNull();
    return Long.parseLong(value);
  }

  private static Map<String, String> runProbe() throws Exception {
    String java =
        System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
    List<String> command = new ArrayList<String>();
    command.add(java);
    command.add("-Xmx512m");
    command.add("-Xms512m");
    command.add("-cp");
    command.add(System.getProperty("java.class.path"));
    command.add(ContextStoreFootprintProbe.class.getName());
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    Map<String, String> values = new HashMap<String, String>();
    StringBuilder output = new StringBuilder();
    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        output.append(line).append('\n');
        int equals = line.indexOf('=');
        if (equals > 0) {
          values.put(line.substring(0, equals), line.substring(equals + 1));
        }
      }
    }
    assertThat(process.waitFor(5, TimeUnit.MINUTES)).isTrue();
    assertThat(process.exitValue()).as(output.toString()).isZero();
    assertThat(values).as(output.toString()).containsKey("found");
    return values;
  }
}
