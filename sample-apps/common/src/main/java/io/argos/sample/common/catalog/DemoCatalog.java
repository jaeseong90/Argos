/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.common.catalog;

import io.argos.sample.common.db.ShopSchema;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The endpoint catalog of the public demo app {@code argos-demo-app} (17 §3.9, ADR-0004 C-43,
 * WO-41): one app, two roles. {@code order} (order-api) serves the shop reads and the checkout,
 * which calls {@code payment} (payment-api) over HTTP; {@code payment} serves the payment endpoint.
 * Both roles answer the shop reads and run the settlement batch.
 *
 * <p>The demo load generator ({@code sample-apps/load}, {@code MIX=demo}) uses exactly these routes
 * and statuses (its MixTest compares them), and the demo app's integration test checks every entry
 * and its SQL statement count ({@link #sqlStatements(String)}).
 *
 * <p>Payment outcome: deterministic by order id ({@link #paymentDeclined(long)}), so a checkout of
 * the same order always ends the same way and the load generator knows the expected status. With
 * uniformly drawn order ids about 20% of the payments fail; order-api answers those checkouts 502.
 */
public final class DemoCatalog {

  /** {@code DEMO_ROLE} of order-api (receives the load, calls payment-api). */
  public static final String ROLE_ORDER = "order";

  /** {@code DEMO_ROLE} of payment-api (called by order-api). */
  public static final String ROLE_PAYMENT = "payment";

  /** Orders seeded per role (ids 1..ORDERS), the shop data of {@link ShopSchema}. */
  public static final int ORDERS = ShopSchema.ORDERS;

  /** Every {@value #DECLINE_EVERY}th order id is declined by payment-api (about 20%). */
  public static final int DECLINE_EVERY = 5;

  /** Lowest and highest sleep of {@code GET /api/slow?ms=} (17 §3.9: 300 ms to 3 s). */
  public static final long SLOW_MIN_MS = 300;

  public static final long SLOW_MAX_MS = 3000;

  /** Upper bound the app accepts for {@code ms} (requests above it answer 400). */
  public static final long SLOW_LIMIT_MS = 10_000;

  /** Message of the unhandled exception of {@code GET /api/error-500}. */
  public static final String ERROR_500_MESSAGE =
      "order lookup failed: inventory service unavailable";

  /** Status of {@code GET /api/send-error} ({@code response.sendError}). */
  public static final int SEND_ERROR_STATUS = 503;

  /** Status order-api answers when payment-api fails or cannot be reached. */
  public static final int CHECKOUT_FAILED_STATUS = 502;

  /** Orders the settlement batch touches per run (one UPDATE each). */
  public static final int BATCH_ORDERS_PER_RUN = 25;

  /** SQL statements of one settlement batch run: count, window select, one update per order. */
  public static final int BATCH_SQL = 2 + BATCH_ORDERS_PER_RUN;

  private DemoCatalog() {}

  /** Whether payment-api declines (fails with a 5xx) the payment of {@code orderId}. */
  public static boolean paymentDeclined(long orderId) {
    return orderId % DECLINE_EVERY == 0;
  }

  /**
   * SQL statements the app issues for the catalog entry {@code id} (the agent's sql_count of the
   * transaction), or -1 for an entry without SQL work of its own.
   */
  public static int sqlStatements(String id) {
    switch (id) {
      case "orders.get":
      case "checkout":
      case "checkout.declined":
      case "payments.declined":
        return 1;
      case "payments.get":
        return 2;
      case "orders.page":
        return 3;
      case "orders.items":
        return 1 + ShopSchema.ITEMS_PER_ORDER;
      case "health":
      case "slow":
      case "error-500":
      case "send-error":
        return 0;
      default:
        return -1;
    }
  }

  /** Every entry, in smoke order. {@link CatalogEntry#requires()} names the role ("role:order"). */
  public static List<CatalogEntry> entries() {
    List<CatalogEntry> e = new ArrayList<>();
    e.add(
        CatalogEntry.get("health", "/healthz")
            .check(Check.bodyContains("UP"))
            .purpose("Container health check: 200 once the schema and data are ready, 503 before")
            .build());
    e.add(
        CatalogEntry.get("orders.get", "/api/orders/1")
            .route("/api/orders/{id}")
            .check(Check.jsonEquals("id", 1))
            .purpose("Normal request with 1 SQL (PreparedStatement)")
            .build());
    e.add(
        CatalogEntry.get("orders.page", "/api/orders?page=0")
            .check(Check.jsonEquals("rows", ShopSchema.PAGE_SIZE))
            .purpose("Normal request with 3 SQL (count, page with fetch size, page total)")
            .build());
    e.add(
        CatalogEntry.get("orders.items", "/api/orders/1/items")
            .route("/api/orders/{id}/items")
            .check(Check.jsonEquals("items", ShopSchema.ITEMS_PER_ORDER))
            .purpose("N+1: 1 query for the items and 1 per item for its product (21 SQL)")
            .build());
    e.add(
        CatalogEntry.get("slow", "/api/slow?ms=" + SLOW_MIN_MS)
            .check(Check.minElapsedMs(SLOW_MIN_MS))
            .purpose("Thread.sleep 300 ms to 3 s: slow transactions in XLog")
            .build());
    e.add(
        CatalogEntry.get("error-500", "/api/error-500")
            .status(500)
            .purpose("Unhandled IllegalStateException(\"" + ERROR_500_MESSAGE + "\")")
            .build());
    e.add(
        CatalogEntry.get("send-error", "/api/send-error")
            .status(SEND_ERROR_STATUS)
            .purpose("response.sendError(503)")
            .build());
    e.add(
        CatalogEntry.get("checkout", "/api/orders/1/checkout")
            .route("/api/orders/{id}/checkout")
            .check(Check.jsonEquals("payment", "PAID"))
            .requires("role:" + ROLE_ORDER)
            .purpose("Distributed call: 1 SQL, then GET /api/payments/{id} on payment-api")
            .build());
    e.add(
        CatalogEntry.get("checkout.declined", "/api/orders/" + DECLINE_EVERY + "/checkout")
            .route("/api/orders/{id}/checkout")
            .status(CHECKOUT_FAILED_STATUS)
            .check(Check.jsonEquals("payment", "FAILED"))
            .requires("role:" + ROLE_ORDER)
            .purpose("Distributed call whose payment fails: payment-api 500, order-api 502")
            .build());
    e.add(
        CatalogEntry.get("payments.get", "/api/payments/1")
            .route("/api/payments/{id}")
            .check(Check.jsonEquals("status", "PAID"))
            .requires("role:" + ROLE_PAYMENT)
            .purpose("Payment: 2 SQL (order lookup, status update)")
            .build());
    e.add(
        CatalogEntry.get("payments.declined", "/api/payments/" + DECLINE_EVERY)
            .route("/api/payments/{id}")
            .status(500)
            .requires("role:" + ROLE_PAYMENT)
            .purpose("Declined payment: 1 SQL, then an unhandled exception (about 20% of orders)")
            .build());
    return Collections.unmodifiableList(e);
  }
}
