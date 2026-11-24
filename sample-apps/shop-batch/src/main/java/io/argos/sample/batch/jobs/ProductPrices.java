/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.batch.jobs;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Component;

/**
 * Product prices for the chunk step's processor. Default: read with one query on first use (the 500
 * products of the shop data never change) and then held in memory, bounded by the product table.
 * With {@code SAMPLE_BATCH_PER_ITEM_SQL=true} every item is priced with its own query (one SQL per
 * item: 20,000 statements in one step execution, the N+1 shape of real batch jobs that drives the
 * agent's step summary mode, 10 §6.8, §9.1).
 */
@Component
public class ProductPrices {

  /** The per-item query of {@code SAMPLE_BATCH_PER_ITEM_SQL=true}. */
  public static final String PRICE_QUERY = "SELECT price FROM products WHERE id = ?";

  private static final ResultSetExtractor<BigDecimal> PRICE_EXTRACTOR =
      rs -> rs.next() ? rs.getBigDecimal("price") : null;

  private final JdbcTemplate jdbc;
  private final boolean perItemSql;
  private volatile Map<Long, BigDecimal> prices;

  public ProductPrices(
      JdbcTemplate jdbc, @Value("${sample.batch.per-item-sql:false}") boolean perItemSql) {
    this.jdbc = jdbc;
    this.perItemSql = perItemSql;
  }

  /** Whether every item is priced with its own query ({@code SAMPLE_BATCH_PER_ITEM_SQL}). */
  public boolean perItemSql() {
    return perItemSql;
  }

  public BigDecimal priceOf(long productId) {
    BigDecimal price =
        perItemSql ? jdbc.query(PRICE_QUERY, PRICE_EXTRACTOR, productId) : load().get(productId);
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
