/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert.metric;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.alert.rule.RuleSpec.Kind;
import java.lang.management.ManagementFactory;
import java.lang.ref.Reference;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.management.ObjectName;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The heap of the alert windows at 12 §11's scale (12 open item 3, ADR-0010 C-244 (1)): 30
 * applications x 500 services within 400 MiB, measured, not estimated.
 *
 * <p>Model: N-06's ceiling of 5,000 transactions per second on one node spread over 30 x 500
 * services. One application carries 1/30 of it (5,000 / 30 tx/s) spread uniformly over its 500
 * services (uniform is the worst case for the number of occupied one-second slots: a skewed
 * distribution puts several transactions into the same slot), 4 instances, 20 outgoing call hosts
 * reported by every instance every 10 s, elapsed times log-normal (median 80 ms, sigma 1) with 1 %
 * outliers of 2 to 30 s (the WO-29 generator's shape), 1 % errors. The windows run for 960 s, so
 * every ring has wrapped (15-minute retention, steady state). The retained heap of the one
 * application is the difference of the live heap (full GC class histograms of this JVM's
 * DiagnosticCommand) with and without the provider; applications are independent series, so 30
 * applications are 30 times that.
 */
@Tag("req:DSN-12-07")
class WindowHeapBudgetTest {

  static final int APPS = 30;
  static final int SERVICES = WindowProvider.MAX_SERVICES_PER_APP;
  static final int INSTANCES = 4;
  static final int HOSTS = 20;
  static final double NODE_TPS = 5_000;
  static final int SECONDS = 960;
  static final long BUDGET_BYTES = 400L * 1024 * 1024;

  private static final Pattern TOTAL = Pattern.compile("(?m)^Total\\s+(\\d+)\\s+(\\d+)\\s*$");
  private static final Pattern ROW =
      Pattern.compile("(?m)^\\s*\\d+:\\s+(\\d+)\\s+(\\d+)\\s+(\\S+)(?:\\s+\\(.*\\))?\\s*$");

  @Test
  @DisplayName(
      "Alert window heap (12 open item 3, C-244 (1), no catalogued ID): one application at 1/30 of"
          + " N-06 (166.7 tx/s over 500 services, 4 instances, 20 hosts) for a full 15-minute"
          + " retention retains at most 400 MiB / 30, so 30 apps x 500 services stay within"
          + " 400 MiB")
  void thirtyAppsTimesFiveHundredServicesWithinBudget() throws Exception {
    Map<String, long[]> before = classHistogram();
    WindowProvider w = fill();
    Map<String, long[]> after;
    try {
      after = classHistogram();
    } finally {
      Reference.reachabilityFence(w);
    }
    long perApp = after.get("Total")[1] - before.get("Total")[1];
    long thirty = perApp * APPS;
    List<String> top = topGrowth(before, after, 8);
    System.out.printf(
        Locale.ROOT,
        "[c244-alert-window-heap] one app %d bytes (%.2f MiB), 30 x 500 services %.1f MiB"
            + " (budget 400 MiB), provider estimate %d bytes; top %s%n",
        perApp,
        perApp / 1024.0 / 1024.0,
        thirty / 1024.0 / 1024.0,
        w.estimatedFootprintBytes(),
        top);
    assertThat(thirty).isLessThanOrEqualTo(BUDGET_BYTES);
  }

  /** The windows of one application after {@link #SECONDS} of its share of N-06. */
  static WindowProvider fill() {
    WindowProvider w = new WindowProvider(Duration.ofMinutes(15));
    SplittableRandom random = new SplittableRandom(42);
    long start = 1_800_000_000_000L;
    double perSecond = NODE_TPS / APPS;
    double carry = 0;
    List<Integer> buckets = new ArrayList<>();
    for (int s = 0; s < SECONDS; s++) {
      long secMs = start + s * 1_000L;
      carry += perSecond;
      int n = (int) carry;
      carry -= n;
      for (int i = 0; i < n; i++) {
        long now = secMs + random.nextInt(1_000);
        w.recordTransaction(
            1,
            1 + random.nextInt(INSTANCES),
            Kind.HTTP,
            1_000L + random.nextInt(SERVICES),
            elapsed(random),
            random.nextInt(100) == 0,
            500,
            now);
      }
      if (s % 10 == 0) {
        for (int instance = 0; instance < INSTANCES; instance++) {
          for (int host = 0; host < HOSTS; host++) {
            buckets.clear();
            long count = 0;
            for (int b = 0; b < 15; b++) {
              int c = b < 8 ? random.nextInt(4) : 0;
              buckets.add(c);
              count += c;
            }
            w.recordExt(1, 9_000L + host, count, count / 20, 0, buckets, 900, secMs + instance);
          }
        }
      }
    }
    return w;
  }

  static int elapsed(SplittableRandom random) {
    if (random.nextInt(100) == 0) {
      return 2_000 + random.nextInt(28_000);
    }
    // Box-Muller: log-normal with median 80 ms, sigma 1.0
    double u1 = Math.max(1e-12, random.nextDouble());
    double u2 = random.nextDouble();
    double z = Math.sqrt(-2 * Math.log(u1)) * Math.cos(2 * Math.PI * u2);
    return (int) Math.max(1, Math.min(60_000, Math.round(80 * Math.exp(z))));
  }

  /** Live heap after a full GC: class name to {instances, bytes}, plus "Total". */
  static Map<String, long[]> classHistogram() throws Exception {
    Object out =
        ManagementFactory.getPlatformMBeanServer()
            .invoke(
                new ObjectName("com.sun.management:type=DiagnosticCommand"),
                "gcClassHistogram",
                new Object[] {new String[0]},
                new String[] {String[].class.getName()});
    String text = String.valueOf(out);
    Map<String, long[]> rows = new HashMap<>();
    Matcher m = ROW.matcher(text);
    while (m.find()) {
      rows.merge(
          m.group(3),
          new long[] {Long.parseLong(m.group(1)), Long.parseLong(m.group(2))},
          (a, b) -> new long[] {a[0] + b[0], a[1] + b[1]});
    }
    Matcher t = TOTAL.matcher(text);
    assertThat(t.find()).as("class histogram total line").isTrue();
    rows.put("Total", new long[] {Long.parseLong(t.group(1)), Long.parseLong(t.group(2))});
    return rows;
  }

  static List<String> topGrowth(Map<String, long[]> before, Map<String, long[]> after, int n) {
    List<Map.Entry<String, Long>> growth = new ArrayList<>();
    for (Map.Entry<String, long[]> e : after.entrySet()) {
      if (e.getKey().equals("Total")) {
        continue;
      }
      long[] b = before.getOrDefault(e.getKey(), new long[2]);
      growth.add(Map.entry(e.getKey(), e.getValue()[1] - b[1]));
    }
    growth.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
    List<String> out = new ArrayList<>();
    for (int i = 0; i < Math.min(n, growth.size()); i++) {
      out.add(growth.get(i).getKey() + "=" + growth.get(i).getValue());
    }
    return out;
  }
}
