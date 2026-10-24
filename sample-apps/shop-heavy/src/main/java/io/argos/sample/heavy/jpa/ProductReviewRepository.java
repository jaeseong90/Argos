/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.heavy.jpa;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Spring Data JPA repository of the reviews (created at startup): derived queries, query by example
 * and specifications (JPA Criteria).
 */
public interface ProductReviewRepository
    extends JpaRepository<ProductReviewEntity, Long>,
        JpaSpecificationExecutor<ProductReviewEntity> {

  /** The newest reviews of a product. */
  List<ProductReviewEntity> findTop10ByProductIdOrderByIdDesc(long productId);

  /** Reviews with at least {@code rating} stars. */
  long countByRatingGreaterThanEqual(int rating);
}
