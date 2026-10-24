/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.boot3.jpa;

import io.argos.sample.common.catalog.ShopCatalog;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * JPA (Hibernate 6) side of the order endpoints: GET /api/orders/{id} is one {@code
 * EntityManager.find} (1 SQL), GET /api/orders/{id}/items loads the 20 items with one query and
 * then touches each lazy product (20 more SQL): the real N+1 of 18 §4.2. Both answers carry {@code
 * sqlStatements}, the statements Hibernate prepared for the call ({@link SqlStatementCounter}).
 */
@Service
public class OrderQueries {

  private final EntityManager entityManager;

  public OrderQueries(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  @Transactional(readOnly = true)
  public Optional<Map<String, Object>> order(long id) {
    SqlStatementCounter.reset();
    OrderEntity order = entityManager.find(OrderEntity.class, id);
    if (order == null) {
      return Optional.empty();
    }
    Map<String, Object> result = order.toMap();
    result.put(ShopCatalog.SQL_STATEMENTS, SqlStatementCounter.count());
    return Optional.of(result);
  }

  @Transactional(readOnly = true)
  public Map<String, Object> items(long orderId) {
    SqlStatementCounter.reset();
    List<OrderItemEntity> items =
        entityManager
            .createQuery(
                "select i from OrderItemEntity i where i.orderId = :orderId order by i.id",
                OrderItemEntity.class)
            .setParameter("orderId", orderId)
            .getResultList();
    List<Map<String, Object>> lines = new ArrayList<>();
    for (OrderItemEntity item : items) {
      Map<String, Object> line = new LinkedHashMap<>();
      line.put("id", item.getId());
      line.put("quantity", item.getQuantity());
      ProductEntity product = item.getProduct();
      line.put("productId", product.getId());
      line.put("product", product.getName()); // initializes the lazy proxy: one SELECT per item
      line.put("price", product.getPrice());
      lines.add(line);
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("orderId", orderId);
    result.put("items", lines.size());
    result.put("lines", lines);
    result.put(ShopCatalog.SQL_STATEMENTS, SqlStatementCounter.count());
    return result;
  }
}
