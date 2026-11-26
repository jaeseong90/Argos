/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.demo;

import io.argos.sample.common.catalog.DemoCatalog;
import io.argos.sample.common.db.ShopDataException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

/**
 * SQL of the demo app beyond the shop reads (common: ShopJdbc): the payment of payment-api and the
 * settlement batch. Statement counts are those of DemoCatalog.
 */
@Component
public class PaymentJdbc {

  /** Thrown by payment-api for a declined order (unhandled: answers 500, an error in Argos). */
  public static final class PaymentDeclinedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    PaymentDeclinedException(long orderId) {
      super("payment declined by the card gateway for order " + orderId);
    }
  }

  private final DataSource dataSource;

  public PaymentJdbc(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  /**
   * GET /api/payments/{id}: looks the order up (1 SQL); a declined order fails here; otherwise
   * marks it paid (2nd SQL). Empty when the order does not exist.
   *
   * @throws PaymentDeclinedException for {@link DemoCatalog#paymentDeclined(long)} orders
   */
  public Optional<Map<String, Object>> pay(long orderId) {
    try (Connection c = dataSource.getConnection()) {
      BigDecimal amount;
      try (PreparedStatement ps = c.prepareStatement("SELECT amount FROM orders WHERE id = ?")) {
        ps.setLong(1, orderId);
        try (ResultSet rs = ps.executeQuery()) {
          if (!rs.next()) {
            return Optional.empty();
          }
          amount = rs.getBigDecimal(1);
        }
      }
      if (DemoCatalog.paymentDeclined(orderId)) {
        throw new PaymentDeclinedException(orderId);
      }
      try (PreparedStatement ps =
          c.prepareStatement("UPDATE orders SET status = 'PAID' WHERE id = ?")) {
        ps.setLong(1, orderId);
        ps.executeUpdate();
      }
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("orderId", orderId);
      result.put("status", "PAID");
      result.put("amount", amount);
      return Optional.of(result);
    } catch (SQLException e) {
      throw new ShopDataException(e.getMessage(), e);
    }
  }

  /**
   * One settlement run ({@value DemoCatalog#BATCH_SQL} SQL in one database transaction): counts the
   * paid orders, selects a window of {@value DemoCatalog#BATCH_ORDERS_PER_RUN} orders that moves on
   * with every run, and ships each of them.
   *
   * @return the number of orders shipped
   */
  public int settle(long run) {
    long first =
        Math.floorMod(run * DemoCatalog.BATCH_ORDERS_PER_RUN, (long) DemoCatalog.ORDERS) + 1;
    long last = first + DemoCatalog.BATCH_ORDERS_PER_RUN - 1;
    try (Connection c = dataSource.getConnection()) {
      boolean autoCommit = c.getAutoCommit();
      c.setAutoCommit(false);
      try {
        try (PreparedStatement ps =
                c.prepareStatement("SELECT COUNT(*) FROM orders WHERE status = 'PAID'");
            ResultSet rs = ps.executeQuery()) {
          rs.next();
        }
        List<Long> ids = new ArrayList<>();
        try (PreparedStatement ps =
            c.prepareStatement("SELECT id FROM orders WHERE id BETWEEN ? AND ? ORDER BY id")) {
          ps.setLong(1, first);
          ps.setLong(2, last);
          try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
              ids.add(rs.getLong(1));
            }
          }
        }
        for (long id : ids) {
          try (PreparedStatement ps =
              c.prepareStatement("UPDATE orders SET status = 'SHIPPED' WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
          }
        }
        c.commit();
        return ids.size();
      } catch (SQLException | RuntimeException e) {
        c.rollback();
        throw e;
      } finally {
        c.setAutoCommit(autoCommit);
      }
    } catch (SQLException e) {
      throw new ShopDataException(e.getMessage(), e);
    }
  }
}
