/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.boot4.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** Row of {@code order_items}; {@link #getProduct()} is a lazy association (one query each). */
@Entity
@Table(name = "order_items")
public class OrderItemEntity {

  @Id private Long id;

  @Column(name = "order_id")
  private long orderId;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "product_id")
  private ProductEntity product;

  private int quantity;

  protected OrderItemEntity() {}

  public Long getId() {
    return id;
  }

  public long getOrderId() {
    return orderId;
  }

  public ProductEntity getProduct() {
    return product;
  }

  public int getQuantity() {
    return quantity;
  }
}
