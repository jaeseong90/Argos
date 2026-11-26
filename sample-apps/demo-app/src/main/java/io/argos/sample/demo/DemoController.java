/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.demo;

import io.argos.sample.common.catalog.DemoCatalog;
import io.argos.sample.common.db.ShopJdbc;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The endpoints of the demo catalog (common: DemoCatalog; 17 §3.9). Normal reads with 1 and 3 SQL,
 * the N+1 read (21 SQL), a slow endpoint, two kinds of server error, the checkout (order-api calls
 * payment-api) and the payment, plus the health check.
 */
@RestController
public class DemoController {

  /** Timeout of the checkout call to payment-api. */
  static final Duration PEER_TIMEOUT = Duration.ofSeconds(5);

  private final DemoSettings settings;
  private final DemoDatabase database;
  private final ShopJdbc shop;
  private final PaymentJdbc payments;
  private final SettlementBatch batch;
  private final HttpClient peer;

  public DemoController(
      DemoSettings settings,
      DemoDatabase database,
      ShopJdbc shop,
      PaymentJdbc payments,
      SettlementBatch batch,
      HttpClient peer) {
    this.settings = settings;
    this.database = database;
    this.shop = shop;
    this.payments = payments;
    this.batch = batch;
    this.peer = peer;
  }

  /** Container health check: 200 when the role's data is ready, 503 before. */
  @GetMapping("/healthz")
  public ResponseEntity<Map<String, Object>> health() {
    Map<String, Object> body = new LinkedHashMap<>();
    boolean ready = database.isReady();
    body.put("status", ready ? "UP" : "STARTING");
    body.put("role", settings.role());
    body.put("batchRuns", batch.runs());
    return ResponseEntity.status(ready ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(body);
  }

  /** 1 SQL. */
  @GetMapping("/api/orders/{id}")
  public Map<String, Object> order(@PathVariable("id") long id) {
    return shop.findOrder(id).orElseThrow(() -> notFound("order " + id));
  }

  /** 3 SQL. */
  @GetMapping("/api/orders")
  public Map<String, Object> orders(@RequestParam(name = "page", defaultValue = "0") int page) {
    if (page < 0 || page > 1_000_000) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page must be 0 to 1000000");
    }
    return shop.listOrders(page);
  }

  /** N+1: 1 + 20 SQL. */
  @GetMapping("/api/orders/{id}/items")
  public Map<String, Object> items(@PathVariable("id") long id) {
    return shop.orderItemsNPlusOne(id);
  }

  @GetMapping("/api/slow")
  public Map<String, Object> slow(@RequestParam(name = "ms", defaultValue = "300") long ms)
      throws InterruptedException {
    if (ms < 0 || ms > DemoCatalog.SLOW_LIMIT_MS) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "ms must be 0 to " + DemoCatalog.SLOW_LIMIT_MS);
    }
    Thread.sleep(ms);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("sleptMs", ms);
    return body;
  }

  /** Unhandled exception: 500 through Spring Boot's error handling. */
  @GetMapping("/api/error-500")
  public Map<String, Object> error500() {
    throw new IllegalStateException(DemoCatalog.ERROR_500_MESSAGE);
  }

  @GetMapping("/api/send-error")
  public void sendError(HttpServletResponse response) throws IOException {
    response.sendError(DemoCatalog.SEND_ERROR_STATUS);
  }

  /**
   * order-api: looks the order up (1 SQL) and pays it on payment-api ({@code GET
   * /api/payments/{id}}). 200 when payment-api accepts, 502 when it fails or cannot be reached.
   */
  @GetMapping("/api/orders/{id}/checkout")
  public ResponseEntity<Map<String, Object>> checkout(@PathVariable("id") long id)
      throws InterruptedException {
    if (!settings.isOrder()) {
      throw notFound("checkout is served by order-api");
    }
    Map<String, Object> order = shop.findOrder(id).orElseThrow(() -> notFound("order " + id));
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("orderId", id);
    body.put("amount", order.get("amount"));
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(settings.peerUrl() + "/api/payments/" + id))
            .timeout(PEER_TIMEOUT)
            .header("Accept", "application/json")
            .GET()
            .build();
    int status;
    try {
      status = peer.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    } catch (IOException e) {
      body.put("payment", "UNREACHABLE");
      body.put("error", e.getClass().getSimpleName());
      return ResponseEntity.status(DemoCatalog.CHECKOUT_FAILED_STATUS).body(body);
    }
    body.put("peerStatus", status);
    if (status == 200) {
      body.put("payment", "PAID");
      return ResponseEntity.ok(body);
    }
    body.put("payment", "FAILED");
    return ResponseEntity.status(DemoCatalog.CHECKOUT_FAILED_STATUS).body(body);
  }

  /**
   * payment-api: 2 SQL for an accepted payment; a declined one (about 20% of the orders) fails
   * after 1 SQL with an unhandled exception (500).
   */
  @GetMapping("/api/payments/{id}")
  public Map<String, Object> payment(@PathVariable("id") long id) {
    if (settings.isOrder()) {
      throw notFound("payments are served by payment-api");
    }
    return payments.pay(id).orElseThrow(() -> notFound("order " + id));
  }

  private static ResponseStatusException notFound(String what) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, what + " not found");
  }
}
