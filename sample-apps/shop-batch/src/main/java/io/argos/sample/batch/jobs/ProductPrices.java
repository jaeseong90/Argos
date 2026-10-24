/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.batch.jobs;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Product prices for the chunk step's processor, read with one query on first use (the 500 products
 * of the shop data never change) and then held in memory: bounded by the product table.
 */
@Component
public class ProductPrices {

  private final JdbcTemplate jdbc;
  private volatile Map<Long, BigDecimal> prices;

  public ProductPrices(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public BigDecimal priceOf(long productId) {
    BigDecimal price = load().get(productId);
    if (price == null) {
      throw new IllegalStateException("Unknown product " + productId);
    }
    return price;
  }

  private Map<Long, BigDecimal> load() {
    Map<Long, BigDecimal> current = prices;
    if (current == null) {
      Map<Long, BigDecimal> loaded = new HashMap<>();
      jdbc.query(
          "SELECT id, price FROM products",
          rs -> {
            loaded.put(rs.getLong("id"), rs.getBigDecimal("price"));
          });
      current = Map.copyOf(loaded);
      prices = current;
    }
    return current;
  }
}
