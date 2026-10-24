/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.heavy.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Product sales summaries (one aggregate SQL per product) behind Spring's cache abstraction on
 * Caffeine (cache {@value #CACHE}, bounded by spring.cache.caffeine.spec): the second call for a
 * product runs no SQL.
 */
@Service
public class ProductSummaries {

  /** Cache name (application.properties: spring.cache.cache-names). */
  public static final String CACHE = "productSummary";

  private final JdbcTemplate jdbc;

  public ProductSummaries(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Cacheable(cacheNames = CACHE, key = "#p0")
  public Optional<Map<String, Object>> summary(long productId) {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT p.id, p.name, p.price, COUNT(i.id) AS order_lines,"
                + " COALESCE(SUM(i.quantity), 0) AS quantity"
                + " FROM products p LEFT JOIN order_items i ON i.product_id = p.id"
                + " WHERE p.id = ? GROUP BY p.id, p.name, p.price",
            productId);
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    Map<String, Object> row = rows.get(0);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("productId", productId);
    result.put("name", row.get("name"));
    result.put("price", row.get("price"));
    result.put("orderLines", ((Number) row.get("order_lines")).longValue());
    result.put("quantity", ((Number) row.get("quantity")).longValue());
    return Optional.of(result);
  }
}
