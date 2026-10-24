/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.heavy.service;

import com.querydsl.core.types.dsl.NumberPath;
import com.querydsl.core.types.dsl.PathBuilder;
import com.querydsl.core.types.dsl.StringPath;
import com.querydsl.jpa.impl.JPAQueryFactory;
import io.argos.sample.boot3.jpa.OrderEntity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Order search with QueryDSL JPA over shop-boot3's {@link OrderEntity} (dynamic paths, no generated
 * Q classes): a count and one page, two SQL statements.
 */
@Service
public class OrderSearch {

  /** Rows per page. */
  public static final int PAGE_SIZE = 20;

  private static final PathBuilder<OrderEntity> ORDER = new PathBuilder<>(OrderEntity.class, "o");
  private static final StringPath STATUS = ORDER.getString("status");
  private static final NumberPath<Long> ID = ORDER.getNumber("id", Long.class);

  private final JPAQueryFactory queries;

  public OrderSearch(JPAQueryFactory queries) {
    this.queries = queries;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> byStatus(String status, int page) {
    if (page < 0 || page > 1000) {
      throw new IllegalArgumentException("page must be between 0 and 1000");
    }
    Long total = queries.select(ORDER.count()).from(ORDER).where(STATUS.eq(status)).fetchOne();
    List<OrderEntity> rows =
        queries
            .selectFrom(ORDER)
            .where(STATUS.eq(status))
            .orderBy(ID.asc())
            .offset((long) page * PAGE_SIZE)
            .limit(PAGE_SIZE)
            .fetch();
    List<Map<String, Object>> orders = new ArrayList<>();
    for (OrderEntity row : rows) {
      orders.add(row.toMap());
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("status", status);
    result.put("page", page);
    result.put("total", total);
    result.put("rows", orders.size());
    result.put("orders", orders);
    return result;
  }
}
