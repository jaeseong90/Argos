/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.load;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.sample.common.catalog.CatalogEntry;
import io.argos.sample.common.catalog.DemoCatalog;
import io.argos.sample.common.catalog.ShopCatalog;
import io.argos.sample.common.db.ShopSchema;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The request mixes: 18 §7.2 / 01 §7.3 item 3 (bench, also in bench.js), 17 §3.9 over the shop apps
 * (default) and 17 §3.9 over the demo app (demo, WO-41), checked against the shop and demo catalogs
 * and against the table in bench.js.
 */
class MixTest {

  /** One row of the bench.js MIX table. */
  private record JsRoute(
      String route, String service, int weight, int expect, String pathTemplate) {}

  private static final Pattern JS_ROW =
      Pattern.compile(
          "\\{ route: '([^']+)', service: '([^']+)', weight: (\\d+), expect: (\\d+),"
              + " path: \\(o?\\) => `([^`]+)` },");

  private static final Pattern JS_EXPRESSION = Pattern.compile("\\$\\{([^}]+)}");

  private static Map<String, Integer> weights(Mix mix) {
    Map<String, Integer> weights = new LinkedHashMap<>();
    mix.routes().forEach(r -> weights.put(r.key(), r.weight()));
    return weights;
  }

  @Test
  @DisplayName("bench: DB 1-3 SQL 60% (40 + 20), external HTTP 20%, N+1 10%, slow 5%, error 5%")
  void benchMixMatchesTheBenchmarkProtocol() {
    Mix bench = Mix.bench();
    assertThat(bench.name()).isEqualTo("bench");
    assertThat(weights(bench))
        .containsExactly(
            Map.entry("orders.get", 40),
            Map.entry("orders.page", 20),
            Map.entry("pay", 20),
            Map.entry("orders.items", 10),
            Map.entry("slow", 5),
            Map.entry("error-500", 5));
    Map<String, Mix.Route> routes = new LinkedHashMap<>();
    bench.routes().forEach(r -> routes.put(r.key(), r));
    assertThat(routes.get("slow").path().apply(7)).isEqualTo("/api/slow?ms=300");
    assertThat(routes.get("error-500").expectedStatus()).isEqualTo(500);
  }

  @Test
  @DisplayName("default: the demo profile of 17 §3.9 (normal 70, slow 10, error 5, N+1 5, call 10)")
  void demoMixMatchesTheDemoProfile() {
    Mix demo = Mix.byName("default");
    Map<String, Integer> w = weights(demo);
    assertThat(w.get("orders.get") + w.get("orders.page")).isEqualTo(70);
    assertThat(w.get("slow")).isEqualTo(10);
    assertThat(w.get("error-500") + w.get("send-error")).isEqualTo(5);
    assertThat(w.get("orders.items")).isEqualTo(5);
    assertThat(w.get("pay")).isEqualTo(10);
    Mix.Route slow =
        demo.routes().stream().filter(r -> r.key().equals("slow")).findFirst().orElseThrow();
    Set<Long> sleeps = new HashSet<>();
    for (long ordinal = 0; ordinal < 1000; ordinal++) {
      String path = slow.path().apply(ordinal);
      long ms = Long.parseLong(path.substring("/api/slow?ms=".length()));
      assertThat(ms).isBetween(300L, 3000L);
      sleeps.add(ms);
    }
    assertThat(sleeps).contains(300L, 3000L).hasSize(28);
  }

  @Test
  @DisplayName("Every route of the default and bench mixes is a shop catalog endpoint, same status")
  void routesAreCatalogEndpoints() {
    List<CatalogEntry> catalog = ShopCatalog.entries();
    for (String name : List.of(Mix.DEFAULT, Mix.BENCH)) {
      for (Mix.Route route : Mix.byName(name).routes()) {
        assertThat(catalog)
            .as("%s/%s: catalog entry for %s", name, route.key(), route.service())
            .anySatisfy(
                entry -> {
                  assertThat(entry.method() + " " + entry.route()).isEqualTo(route.service());
                  assertThat(entry.expectedStatus()).isEqualTo(route.expectedStatus());
                });
      }
    }
  }

  @Test
  @DisplayName("Order ids stay in the sample data and cover all of it; pages too")
  void parametersStayInTheSampleData() {
    assertThat(Mix.ORDERS).isEqualTo(ShopSchema.ORDERS);
    assertThat(Mix.PAGES).isEqualTo(ShopSchema.ORDERS / ShopSchema.PAGE_SIZE);
    Set<Long> ids = new HashSet<>();
    for (long ordinal = 0; ordinal < Mix.ORDERS; ordinal++) {
      ids.add(Mix.orderId(ordinal));
    }
    assertThat(ids).hasSize(Mix.ORDERS).allMatch(id -> id >= 1 && id <= Mix.ORDERS);
    for (long ordinal = 0; ordinal < 100; ordinal++) {
      assertThat(Mix.page(ordinal)).isBetween(0L, (long) Mix.PAGES - 1);
    }
  }

  @Test
  @DisplayName(
      "T-NFR-16b demo: 17 §3.9 over the demo app (normal 70, slow 10, error 5, N+1 5, call 10)")
  void demoAppMixMatchesTheDemoProfile() {
    Mix demo = Mix.byName("demo");
    assertThat(demo.name()).isEqualTo("demo");
    assertThat(weights(demo))
        .containsExactly(
            Map.entry("orders.get", 45),
            Map.entry("orders.page", 25),
            Map.entry("orders.items", 5),
            Map.entry("slow", 10),
            Map.entry("error-500", 3),
            Map.entry("send-error", 2),
            Map.entry("checkout", 8),
            Map.entry("checkout.declined", 2));
    Map<String, Integer> w = weights(demo);
    assertThat(w.get("orders.get") + w.get("orders.page")).as("normal").isEqualTo(70);
    assertThat(w.get("error-500") + w.get("send-error")).as("error").isEqualTo(5);
    assertThat(w.get("checkout") + w.get("checkout.declined")).as("distributed").isEqualTo(10);
    Mix.Route slow = route(demo, "slow");
    Set<Long> sleeps = new HashSet<>();
    for (long ordinal = 0; ordinal < 1000; ordinal++) {
      long ms = Long.parseLong(slow.path().apply(ordinal).substring("/api/slow?ms=".length()));
      assertThat(ms).isBetween(DemoCatalog.SLOW_MIN_MS, DemoCatalog.SLOW_MAX_MS);
      sleeps.add(ms);
    }
    assertThat(sleeps).contains(DemoCatalog.SLOW_MIN_MS, DemoCatalog.SLOW_MAX_MS).hasSize(28);
  }

  @Test
  @DisplayName(
      "T-NFR-16b demo: every route is a DemoCatalog endpoint with the same expected status")
  void demoRoutesAreDemoCatalogEndpoints() {
    Map<String, CatalogEntry> catalog = new LinkedHashMap<>();
    DemoCatalog.entries().forEach(e -> catalog.put(e.id(), e));
    for (Mix.Route route : Mix.demo().routes()) {
      CatalogEntry entry = catalog.get(route.key());
      assertThat(entry).as("DemoCatalog entry %s", route.key()).isNotNull();
      assertThat(entry.method() + " " + entry.route()).isEqualTo(route.service());
      assertThat(entry.expectedStatus()).as(route.key()).isEqualTo(route.expectedStatus());
      for (long ordinal = 0; ordinal < 50; ordinal++) {
        String path = route.path().apply(ordinal);
        String withoutQuery = path.indexOf('?') < 0 ? path : path.substring(0, path.indexOf('?'));
        assertThat(withoutQuery).matches(entry.route().replace("{id}", "[1-9][0-9]*"));
      }
    }
    // Every request endpoint order-api serves (all but the health check) gets load.
    Set<String> loaded = new HashSet<>();
    Mix.demo().routes().forEach(r -> loaded.add(r.key()));
    for (CatalogEntry entry : catalog.values()) {
      boolean orderApi =
          entry.requires() == null || entry.requires().equals("role:" + DemoCatalog.ROLE_ORDER);
      if (orderApi && !entry.id().equals("health")) {
        assertThat(loaded).as("load on %s", entry.id()).contains(entry.id());
      }
    }
  }

  @Test
  @DisplayName("T-NFR-16b demo: checkout ids follow payment-api's decline rule; all ids are used")
  void demoCheckoutIdsMatchThePaymentOutcome() {
    assertThat(Mix.DECLINE_EVERY).isEqualTo(DemoCatalog.DECLINE_EVERY);
    assertThat(Mix.ORDERS).isEqualTo(DemoCatalog.ORDERS);
    Set<Long> paid = new HashSet<>();
    Set<Long> declined = new HashSet<>();
    for (long ordinal = 0; ordinal < 2 * Mix.ORDERS; ordinal++) {
      long p = Mix.paidOrderId(ordinal);
      long d = Mix.declinedOrderId(ordinal);
      assertThat(p).isBetween(1L, (long) Mix.ORDERS);
      assertThat(d).isBetween(1L, (long) Mix.ORDERS);
      assertThat(DemoCatalog.paymentDeclined(p)).as("paid id %d", p).isFalse();
      assertThat(DemoCatalog.paymentDeclined(d)).as("declined id %d", d).isTrue();
      paid.add(p);
      declined.add(d);
    }
    assertThat(paid).hasSize(Mix.ORDERS - Mix.ORDERS / DemoCatalog.DECLINE_EVERY);
    assertThat(declined).hasSize(Mix.ORDERS / DemoCatalog.DECLINE_EVERY);
    // Seen from order-api: error routes 3 + 2 plus the declined checkouts 2 = 7% 5xx answers.
    int errors =
        Mix.demo().routes().stream()
            .filter(r -> r.expectedStatus() >= 500)
            .mapToInt(Mix.Route::weight)
            .sum();
    assertThat(errors).isEqualTo(7);
  }

  @Test
  @DisplayName("T-NFR-16b demo: every 20 requests hold each route weight/5 times (within 1)")
  void demoScheduleInterleaves() {
    Mix demo = Mix.demo();
    WeightedSchedule schedule = demo.schedule();
    assertThat(schedule.cycleLength()).isEqualTo(100);
    for (int window = 0; window < 10; window++) {
      Map<String, Integer> counts = new LinkedHashMap<>();
      for (long n = window * 20L; n < window * 20L + 20; n++) {
        counts.merge(demo.routes().get(schedule.routeIndex(n)).key(), 1, Integer::sum);
      }
      for (Mix.Route r : demo.routes()) {
        double expected = r.weight() / 5.0;
        assertThat((double) counts.getOrDefault(r.key(), 0))
            .as("window %d, route %s", window, r.key())
            .isBetween(Math.floor(expected) - 1, Math.ceil(expected) + 1);
      }
    }
  }

  private static Mix.Route route(Mix mix, String key) {
    return mix.routes().stream().filter(r -> r.key().equals(key)).findFirst().orElseThrow();
  }

  @Test
  @DisplayName("MIX names: default (also empty, unset), demo and bench; anything else is rejected")
  void names() {
    assertThat(Mix.names()).containsExactly("default", "demo", "bench");
    assertThat(Mix.byName(null).name()).isEqualTo("default");
    assertThat(Mix.byName("").name()).isEqualTo("default");
    assertThat(Mix.byName(" Demo ").name()).isEqualTo("demo");
    assertThat(Mix.byName("BENCH").name()).isEqualTo("bench");
    assertThatThrownBy(() -> Mix.byName("all"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unknown MIX 'all'");
  }

  @Test
  @DisplayName("bench.js has the same table as Mix.bench(): routes, weights, statuses, paths")
  void benchScriptMatchesTheJavaMix() throws IOException {
    String script = benchScript();
    List<JsRoute> js = jsTable(script);
    List<Mix.Route> java = Mix.bench().routes();
    assertThat(js).hasSameSizeAs(java);
    for (int i = 0; i < java.size(); i++) {
      Mix.Route expected = java.get(i);
      JsRoute actual = js.get(i);
      assertThat(actual.route()).isEqualTo(expected.key());
      assertThat(actual.service()).isEqualTo(expected.service());
      assertThat(actual.weight()).isEqualTo(expected.weight());
      assertThat(actual.expect()).isEqualTo(expected.expectedStatus());
      for (long ordinal = 0; ordinal < 2 * Mix.ORDERS; ordinal++) {
        assertThat(render(actual.pathTemplate(), ordinal))
            .as("bench.js path of %s, ordinal %d", actual.route(), ordinal)
            .isEqualTo(expected.path().apply(ordinal));
      }
    }
    // Same data size, same order id walk, same schedule algorithm and tie rule as the Java side.
    assertThat(script)
        .contains("const ORDERS = " + Mix.ORDERS + ";")
        .contains("const PAGES = " + Mix.PAGES + ";")
        .contains("return 1 + ((ordinal * 7919) % ORDERS);")
        .contains("if (current[i] > current[best]) {")
        .contains("current[best] -= length;")
        .contains("executor: 'constant-arrival-rate'");
  }

  private static String benchScript() throws IOException {
    String location = System.getProperty("argos.load.benchScript");
    assertThat(location).as("system property argos.load.benchScript (set by Gradle)").isNotNull();
    return Files.readString(Path.of(location), StandardCharsets.UTF_8);
  }

  private static List<JsRoute> jsTable(String script) {
    int begin = script.indexOf("// mix:begin");
    int end = script.indexOf("// mix:end");
    assertThat(begin).as("// mix:begin marker").isNotNegative();
    assertThat(end).as("// mix:end marker").isGreaterThan(begin);
    List<JsRoute> rows = new ArrayList<>();
    for (String line : script.substring(begin, end).lines().toList()) {
      String trimmed = line.trim();
      if (!trimmed.startsWith("{")) {
        continue;
      }
      Matcher m = JS_ROW.matcher(trimmed);
      assertThat(m.matches()).as("bench.js MIX row has the expected shape: %s", trimmed).isTrue();
      rows.add(
          new JsRoute(
              m.group(1),
              m.group(2),
              Integer.parseInt(m.group(3)),
              Integer.parseInt(m.group(4)),
              m.group(5)));
    }
    return rows;
  }

  /** Evaluates the template expressions bench.js uses; an unknown one fails the test. */
  private static String render(String template, long ordinal) {
    Matcher m = JS_EXPRESSION.matcher(template);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      String value =
          switch (m.group(1).trim()) {
            case "orderId(o)" -> Long.toString(Mix.orderId(ordinal));
            case "o % PAGES" -> Long.toString(Mix.page(ordinal));
            default ->
                throw new AssertionError("unknown bench.js expression ${" + m.group(1) + "}");
          };
      m.appendReplacement(out, Matcher.quoteReplacement(value));
    }
    m.appendTail(out);
    return out.toString();
  }
}
