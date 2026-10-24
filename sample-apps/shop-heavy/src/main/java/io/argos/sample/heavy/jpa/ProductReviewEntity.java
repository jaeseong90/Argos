/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.heavy.jpa;

import jakarta.persistence.Cacheable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.hibernate.annotations.Cache;
import org.hibernate.annotations.CacheConcurrencyStrategy;
import org.hibernate.envers.Audited;

/**
 * A product review: audited by Hibernate Envers (every change is a revision in {@code
 * product_reviews_aud}) and held in the second-level cache (Hibernate JCache on Caffeine). The
 * table belongs to shop-heavy only; Hibernate creates it (HeavySchemaFilter).
 */
@Entity
@Table(name = "product_reviews")
@Audited
@Cacheable
@Cache(usage = CacheConcurrencyStrategy.READ_WRITE, region = "productReviews")
public class ProductReviewEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "product_id", nullable = false)
  private long productId;

  @Column(nullable = false)
  private int rating;

  @Column(length = 500, nullable = false)
  private String comment;

  @Column(name = "updated_at", nullable = false)
  private LocalDateTime updatedAt;

  @Version private long version;

  protected ProductReviewEntity() {}

  public ProductReviewEntity(long productId, int rating, String comment, LocalDateTime now) {
    this.productId = productId;
    this.rating = rating;
    this.comment = comment;
    this.updatedAt = now;
  }

  public Long getId() {
    return id;
  }

  public void change(int newRating, String newComment, LocalDateTime now) {
    this.rating = newRating;
    this.comment = newComment;
    this.updatedAt = now;
  }

  public Map<String, Object> toMap() {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("id", id);
    map.put("productId", productId);
    map.put("rating", rating);
    map.put("comment", comment);
    map.put("updatedAt", updatedAt == null ? null : updatedAt.toString());
    map.put("version", version);
    return map;
  }
}
