/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.boot3.web;

import io.argos.sample.boot3.jpa.OrderQueries;
import io.argos.sample.common.db.ShopJdbc;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** GET /api/orders/{id} (JPA, 1 SQL), /api/orders?page= (3 SQL), /api/orders/{id}/items (N+1). */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

  private final OrderQueries orders;
  private final ShopJdbc jdbc;

  public OrderController(OrderQueries orders, ShopJdbc jdbc) {
    this.orders = orders;
    this.jdbc = jdbc;
  }

  @GetMapping("/{id}")
  public ResponseEntity<Map<String, Object>> order(@PathVariable("id") long id) {
    return ResponseEntity.of(orders.order(id));
  }

  @GetMapping
  public Map<String, Object> page(@RequestParam(name = "page", defaultValue = "0") int page) {
    return jdbc.listOrders(page);
  }

  @GetMapping("/{id}/items")
  public Map<String, Object> items(@PathVariable("id") long id) {
    return orders.items(id);
  }
}
