/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.boot4.jpa;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/** Row of {@code products}; loaded lazily per order item (the N+1 of 18 §4.2). */
@Entity
@Table(name = "products")
public class ProductEntity {

  @Id private Long id;

  private String name;

  private BigDecimal price;

  protected ProductEntity() {}

  public Long getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public BigDecimal getPrice() {
    return price;
  }
}
