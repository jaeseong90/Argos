/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.heavy.service;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.Context;

/**
 * HTML order reports from the Thymeleaf template {@code templates/heavy/order.html}, the same
 * template the page GET /heavy/orders/{id} renders; also the body of the report mail.
 */
@Service
public class ReportRenderer {

  /** Template name (classpath:templates/heavy/order.html). */
  public static final String TEMPLATE = "heavy/order";

  private final ITemplateEngine templates;

  public ReportRenderer(ITemplateEngine templates) {
    this.templates = templates;
  }

  public String render(Map<String, Object> order) {
    Context context = new Context(Locale.ROOT);
    context.setVariable("order", order);
    return templates.process(TEMPLATE, context);
  }

  /** Startup: the template parsed, cached and rendered once. */
  public Map<String, Object> prepare() {
    Map<String, Object> order = new LinkedHashMap<>();
    order.put("id", 0);
    order.put("customerId", 0);
    order.put("status", "NEW");
    order.put("amount", "0.00");
    order.put("createdAt", "2026-01-15T00:00");
    String html = render(order);
    if (!html.contains("<table")) {
      throw new IllegalStateException("Template did not render a table");
    }
    return Map.of("template", TEMPLATE, "chars", html.length());
  }
}
