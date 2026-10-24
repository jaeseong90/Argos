/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.heavy.web;

import io.argos.sample.common.db.ShopJdbc;
import io.argos.sample.heavy.service.ReportRenderer;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;

/** Server-rendered page (Thymeleaf view): GET /heavy/orders/{id}. */
@Controller
public class HeavyPageController {

  private final ShopJdbc shop;

  public HeavyPageController(ShopJdbc shop) {
    this.shop = shop;
  }

  @GetMapping("/heavy/orders/{id}")
  public String order(@PathVariable("id") long id, Model model) {
    Optional<Map<String, Object>> order = shop.findOrder(id);
    if (order.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No order " + id);
    }
    model.addAttribute("order", order.get());
    return ReportRenderer.TEMPLATE;
  }
}
