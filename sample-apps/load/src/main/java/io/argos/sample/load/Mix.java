/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.load;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.LongFunction;

/**
 * Named request mixes over the shop endpoint catalog (18 §4.2). Weights are percentages; request
 * parameters are deterministic functions of the route ordinal ({@link WeightedSchedule#ordinal}).
 *
 * <ul>
 *   <li>{@code bench}: 18 §7.2 / 01 §7.3 item 3, identical to {@code bench.js} (MixTest compares
 *       them): DB 1-3 SQL 60% (orders/{id} 40%, orders?page 20%), external HTTP 20%, N+1 10%, slow
 *       300 ms 5%, error 5%.
 *   <li>{@code default}: the demo profile of 17 §3.9: normal 70%, slow 300 ms to 3 s 10%, error
 *       (5xx and exception) 5%, N+1 5%, distributed call order to payment 10%.
 * </ul>
 */
final class Mix {

  static final String BENCH = "bench";
  static final String DEFAULT = "default";

  /** Orders in the sample data (common: ShopSchema.ORDERS); ids are 1..ORDERS. */
  static final int ORDERS = 1000;

  /** Pages of GET /api/orders?page= (ORDERS / ShopSchema.PAGE_SIZE). */
  static final int PAGES = 10;

  /**
   * One weighted route. {@code key} names it in reports, {@code service} is the expected service
   * name pattern ({@code GET /api/orders/{id}}), {@code expectedStatus} the answer that counts as
   * expected (the error routes answer 500 or 503 on purpose).
   */
  record Route(
      String key, String service, int weight, int expectedStatus, LongFunction<String> path) {}

  private final String name;
  private final List<Route> routes;
  private final WeightedSchedule schedule;

  private Mix(String name, Route... routes) {
    this.name = name;
    this.routes = Collections.unmodifiableList(Arrays.asList(routes));
    int[] weights = new int[routes.length];
    int sum = 0;
    for (int i = 0; i < routes.length; i++) {
      weights[i] = routes[i].weight();
      sum += weights[i];
    }
    if (sum != 100) {
      throw new IllegalStateException(name + ": weights must add up to 100, got " + sum);
    }
    this.schedule = new WeightedSchedule(weights);
  }

  static List<String> names() {
    return List.of(DEFAULT, BENCH);
  }

  static Mix byName(String name) {
    String key = name == null ? DEFAULT : name.trim().toLowerCase(Locale.ROOT);
    return switch (key) {
      case "", DEFAULT, "demo" -> demo();
      case BENCH -> bench();
      default ->
          throw new IllegalArgumentException("unknown MIX '" + name + "' (one of " + names() + ")");
    };
  }

  /** 18 §7.2: the overhead benchmark mix (same as bench.js). */
  static Mix bench() {
    return new Mix(
        BENCH,
        new Route("orders.get", "GET /api/orders/{id}", 40, 200, o -> "/api/orders/" + orderId(o)),
        new Route("orders.page", "GET /api/orders", 20, 200, o -> "/api/orders?page=" + page(o)),
        new Route("pay", "GET /api/pay/{id}", 20, 200, o -> "/api/pay/" + orderId(o)),
        new Route(
            "orders.items",
            "GET /api/orders/{id}/items",
            10,
            200,
            o -> "/api/orders/" + orderId(o) + "/items"),
        new Route("slow", "GET /api/slow", 5, 200, o -> "/api/slow?ms=300"),
        new Route("error-500", "GET /api/error-500", 5, 500, o -> "/api/error-500"));
  }

  /** 17 §3.9: the demo profile mix. */
  static Mix demo() {
    return new Mix(
        DEFAULT,
        new Route("orders.get", "GET /api/orders/{id}", 45, 200, o -> "/api/orders/" + orderId(o)),
        new Route("orders.page", "GET /api/orders", 25, 200, o -> "/api/orders?page=" + page(o)),
        new Route("pay", "GET /api/pay/{id}", 10, 200, o -> "/api/pay/" + orderId(o)),
        new Route(
            "orders.items",
            "GET /api/orders/{id}/items",
            5,
            200,
            o -> "/api/orders/" + orderId(o) + "/items"),
        new Route("slow", "GET /api/slow", 10, 200, o -> "/api/slow?ms=" + demoSlowMillis(o)),
        new Route("error-500", "GET /api/error-500", 3, 500, o -> "/api/error-500"),
        new Route("send-error", "GET /api/send-error", 2, 503, o -> "/api/send-error"));
  }

  /** Order id 1..ORDERS; 7919 is coprime with 1000, so consecutive ordinals visit every order. */
  static long orderId(long ordinal) {
    return 1 + Math.floorMod(ordinal * 7919L, (long) ORDERS);
  }

  static long page(long ordinal) {
    return Math.floorMod(ordinal, (long) PAGES);
  }

  /**
   * 300 ms to 3 s in 100 ms steps (17 §3.9 "300ms~3s"); 11 is coprime with the 28 steps, so every
   * 28 consecutive slow requests use each step once.
   */
  static long demoSlowMillis(long ordinal) {
    return 300 + Math.floorMod(ordinal * 11L, 28L) * 100;
  }

  String name() {
    return name;
  }

  List<Route> routes() {
    return routes;
  }

  WeightedSchedule schedule() {
    return schedule;
  }
}
