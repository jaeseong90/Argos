/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.heavy.service;

import io.argos.sample.heavy.jpa.ProductReviewEntity;
import io.argos.sample.heavy.jpa.ProductReviewRepository;
import io.argos.sample.heavy.web.ReviewRequest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.validation.Validator;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.hibernate.envers.AuditReader;
import org.hibernate.envers.AuditReaderFactory;
import org.hibernate.envers.DefaultRevisionEntity;
import org.hibernate.envers.RevisionType;
import org.hibernate.envers.query.AuditEntity;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.ExampleMatcher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.convert.QueryByExamplePredicateBuilder;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Product reviews on JPA: a Spring Data JPA repository, Hibernate Envers revisions, the
 * second-level cache (Hibernate JCache on Caffeine) and Bean Validation (Hibernate Validator).
 * Create and change a review, read it (from the cache after the first read), list a product's
 * reviews, list a review's revisions.
 */
@Service
public class ReviewService {

  /** Revisions returned per review at most. */
  static final int MAX_REVISIONS = 50;

  /** Reviews per search page. */
  static final int PAGE_SIZE = 10;

  private final ProductReviewRepository repository;
  private final EntityManager entityManager;
  private final EntityManagerFactory entityManagerFactory;
  private final Validator validator;
  private final Clock clock;

  public ReviewService(
      ProductReviewRepository repository,
      EntityManager entityManager,
      EntityManagerFactory entityManagerFactory,
      Validator validator,
      Clock clock) {
    this.repository = repository;
    this.entityManager = entityManager;
    this.entityManagerFactory = entityManagerFactory;
    this.validator = validator;
    this.clock = clock;
  }

  @Transactional
  public Map<String, Object> create(long productId, int rating, String comment) {
    return repository
        .saveAndFlush(new ProductReviewEntity(productId, rating, comment, now()))
        .toMap();
  }

  @Transactional
  public Optional<Map<String, Object>> change(long id, int rating, String comment) {
    Optional<ProductReviewEntity> review = repository.findById(id);
    review.ifPresent(r -> r.change(rating, comment, now()));
    repository.flush();
    return review.map(ProductReviewEntity::toMap);
  }

  @Transactional(readOnly = true)
  public Optional<Map<String, Object>> find(long id) {
    boolean cachedBefore = entityManagerFactory.getCache().contains(ProductReviewEntity.class, id);
    ProductReviewEntity review = entityManager.find(ProductReviewEntity.class, id);
    if (review == null) {
      return Optional.empty();
    }
    Map<String, Object> result = review.toMap();
    result.put("fromSecondLevelCache", cachedBefore);
    return Optional.of(result);
  }

  /** The newest ten reviews of a product (derived query). */
  @Transactional(readOnly = true)
  public Map<String, Object> byProduct(long productId) {
    List<Map<String, Object>> reviews = new ArrayList<>();
    for (ProductReviewEntity review : repository.findTop10ByProductIdOrderByIdDesc(productId)) {
      reviews.add(review.toMap());
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("productId", productId);
    result.put("reviews", reviews);
    return result;
  }

  /**
   * Reviews whose comment contains {@code text} (query by example, ignoring case) with at least
   * {@code minRating} stars (specification), newest first, one page of {@value #PAGE_SIZE}.
   */
  @Transactional(readOnly = true)
  public Map<String, Object> search(String text, int minRating, int page) {
    if (page < 0 || page > 1000) {
      throw new IllegalArgumentException("page must be between 0 and 1000");
    }
    ProductReviewEntity probe = new ProductReviewEntity(0, 0, text, null);
    ExampleMatcher matcher =
        ExampleMatcher.matchingAll()
            .withIgnorePaths("id", "productId", "rating", "updatedAt", "version")
            .withMatcher("comment", ExampleMatcher.GenericPropertyMatchers.contains().ignoreCase());
    Specification<ProductReviewEntity> rated =
        (root, query, criteria) -> criteria.greaterThanOrEqualTo(root.get("rating"), minRating);
    Specification<ProductReviewEntity> matching =
        (root, query, criteria) ->
            QueryByExamplePredicateBuilder.getPredicate(root, criteria, Example.of(probe, matcher));
    Page<ProductReviewEntity> found =
        repository.findAll(
            matching.and(rated),
            PageRequest.of(page, PAGE_SIZE, Sort.by(Sort.Direction.DESC, "id")));
    List<Map<String, Object>> reviews = new ArrayList<>();
    for (ProductReviewEntity review : found) {
      reviews.add(review.toMap());
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("text", text);
    result.put("minRating", minRating);
    result.put("page", page);
    result.put("total", found.getTotalElements());
    result.put("reviews", reviews);
    return result;
  }

  /** Revisions of review {@code id}, oldest first (Envers audit query). */
  @Transactional(readOnly = true)
  public Map<String, Object> revisions(long id) {
    AuditReader audit = AuditReaderFactory.get(entityManager);
    List<?> rows =
        audit
            .createQuery()
            .forRevisionsOfEntity(ProductReviewEntity.class, false, true)
            .add(AuditEntity.id().eq(id))
            .addOrder(AuditEntity.revisionNumber().asc())
            .setMaxResults(MAX_REVISIONS)
            .getResultList();
    List<Map<String, Object>> revisions = new ArrayList<>();
    for (Object row : rows) {
      Object[] columns = (Object[]) row;
      ProductReviewEntity state = (ProductReviewEntity) columns[0];
      DefaultRevisionEntity revision = (DefaultRevisionEntity) columns[1];
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("revision", revision.getId());
      entry.put("type", ((RevisionType) columns[2]).name());
      entry.put("state", state.toMap());
      revisions.add(entry);
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", id);
    result.put("revisions", revisions);
    return result;
  }

  /**
   * Startup (read only): repository queries (derived, count, example + specification page), an
   * Envers audit query, a second-level cache lookup and the validation of a valid and an invalid
   * review request.
   */
  @Transactional(readOnly = true)
  public Map<String, Object> prepare() {
    Number revisions =
        (Number)
            AuditReaderFactory.get(entityManager)
                .createQuery()
                .forRevisionsOfEntity(ProductReviewEntity.class, false, true)
                .addProjection(AuditEntity.revisionNumber().count())
                .getSingleResult();
    int valid = validator.validate(new ReviewRequest(1, 5, "fine")).size();
    int invalid = validator.validate(new ReviewRequest(0, 9, " ")).size();
    if (valid != 0 || invalid != 3) {
      throw new IllegalStateException(
          "Unexpected validation result: " + valid + " and " + invalid + " violations");
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("reviews", repository.count());
    result.put("goodReviews", repository.countByRatingGreaterThanEqual(4));
    result.put("latestOfProduct1", repository.findTop10ByProductIdOrderByIdDesc(1L).size());
    result.put("searchTotal", search("fine", 4, 0).get("total"));
    result.put("revisions", revisions.longValue());
    result.put(
        "secondLevelCache",
        entityManagerFactory.getCache().contains(ProductReviewEntity.class, 0L));
    result.put("violationsOfInvalidRequest", invalid);
    return result;
  }

  private LocalDateTime now() {
    return LocalDateTime.now(clock);
  }
}
