/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.boot4.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/** Row of {@code orders} (schema: common ShopSchema). Read-only in the app. */
@Entity
@Table(name = "orders")
public class OrderEntity {

  @Id private Long id;

  @Column(name = "customer_id")
  private long customerId;

  private String status;

  private BigDecimal amount;

  @Column(name = "created_at")
  private LocalDateTime createdAt;

  protected OrderEntity() {}

  /** Same JSON shape as the JDBC variant (ShopJdbc.findOrder). */
  public Map<String, Object> toMap() {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("id", id);
    map.put("customerId", customerId);
    map.put("status", status);
    map.put("amount", amount);
    map.put("createdAt", createdAt == null ? null : createdAt.toString());
    return map;
  }
}
