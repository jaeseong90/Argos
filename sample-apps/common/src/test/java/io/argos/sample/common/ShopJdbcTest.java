/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.sample.common.datasource.SampleDataSource;
import io.argos.sample.common.datasource.SampleDataSources;
import io.argos.sample.common.db.PoolExhauster;
import io.argos.sample.common.db.ShopJdbc;
import io.argos.sample.common.db.ShopSchema;
import io.argos.sample.common.seed.Seeds;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The exact SQL counts of the catalog endpoints (18 §4.2) on H2. */
class ShopJdbcTest {

  private static SampleDataSource dataSource;
  private static CountingDataSource counting;
  private static ShopJdbc jdbc;

  @BeforeAll
  static void createDatabase() {
    SampleConfig config =
        SampleConfig.from(
            Collections.singletonMap("SAMPLE_POOL_MAX", "3"),
            new Properties(),
            new BuildInfo("shop-jdbc-test", "test"));
    dataSource = SampleDataSources.create(config);
    counting = new CountingDataSource(dataSource.dataSource());
    jdbc =
        new ShopJdbc(
            counting,
            config.db(),
            Clock.fixed(Instant.parse("2026-01-15T00:00:00Z"), ZoneOffset.UTC));
  }

  @AfterAll
  static void closeDatabase() {
    dataSource.close();
  }

  @BeforeEach
  void resetCounter() {
    counting.reset();
  }

  @Test
  @DisplayName(
      "JDBC variant (shop-batch/shop-heavy own endpoints): GET /api/orders/{id}: one"
          + " PreparedStatement")
  void findOrder() {
    Map<String, Object> order = jdbc.findOrder(1).orElseThrow(IllegalStateException::new);
    assertThat(order).containsEntry("id", 1L).containsEntry("customerId", 1L);
    assertThat(counting.executed()).hasSize(1);
    assertThat(jdbc.findOrder(ShopSchema.ORDERS + 1)).isEmpty();
  }

  @Test
  @DisplayName("GET /api/orders?page=: three statements, 100 rows")
  void listOrders() {
    Map<String, Object> page = jdbc.listOrders(0);
    assertThat(page).containsEntry("rows", 100).containsEntry("total", 1000L);
    assertThat(counting.executed()).hasSize(3);
    assertThat(jdbc.listOrders(9)).containsEntry("rows", 100);
    assertThat(jdbc.listOrders(10)).containsEntry("rows", 0);
  }

  @Test
  @DisplayName(
      "JDBC fixture (no app endpoint; the shop apps serve /api/orders/{id}/items with JPA): N+1 ="
          + " 1 + 20 statements, 20 distinct products")
  void itemsNPlusOne() {
    Map<String, Object> items = jdbc.orderItemsNPlusOne(7);
    assertThat(items).containsEntry("items", 20);
    List<String> executed = counting.executed();
    assertThat(executed).hasSize(21);
    assertThat(executed.subList(1, 21)).allMatch(sql -> sql.contains("FROM products"));
    assertThat(
            java.util.stream.IntStream.range(0, 20)
                .mapToLong(i -> ShopSchema.productIdOf(7, i))
                .distinct()
                .count())
        .isEqualTo(20);
  }

  @Test
  @DisplayName("GET /api/stmt: one Statement with the search text inlined")
  void statementSearch() {
    Map<String, Object> result = jdbc.statementSearch("kim");
    assertThat(result).containsEntry("matches", ShopSchema.CUSTOMERS / 5);
    assertThat(counting.executed()).singleElement().asString().contains("LIKE '%kim%'");
    assertThatThrownBy(() -> jdbc.statementSearch("x' OR '1'='1"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("GET /api/batch-insert: addBatch x 50, one executeBatch")
  void batchInsert() {
    assertThat(jdbc.batchInsert(50)).containsEntry("inserted", 50);
    assertThat(counting.executed()).hasSize(1);
  }

  @Test
  @DisplayName("Audit insert and lookup; a rolled back row is gone")
  void auditRows() throws Exception {
    long id = jdbc.insertAudit("test");
    assertThat(jdbc.auditExists(id)).isTrue();
    assertThat(jdbc.auditExists(id + 1_000_000)).isFalse();
  }

  @Test
  @DisplayName("GET /api/slow?sql=1: sleeps inside H2")
  void sleepInDatabase() {
    long start = System.nanoTime();
    jdbc.sleepInDatabase(120);
    assertThat((System.nanoTime() - start) / 1_000_000).isGreaterThanOrEqualTo(110);
    assertThat(counting.executed()).hasSize(1);
  }

  @Test
  @DisplayName("Seeds: bind and literal statements carry the value; raw literals are valid SQL")
  void seeds() {
    Map<String, Object> result = jdbc.seedBindAndLiteral(Seeds.XSS.get(0));
    assertThat(result.get("value").toString()).contains(Seeds.SENTINEL);
    assertThat(counting.executed()).hasSize(2);
    assertThat(counting.executed().get(1)).contains(Seeds.SENTINEL);
    counting.reset();
    assertThat(jdbc.seedRawLiterals(Seeds.SQL_LITERALS).get("literals").toString())
        .contains(Seeds.SENTINEL);
    assertThat(counting.executed()).hasSize(Seeds.SQL_LITERALS.size());
    assertThat(jdbc.literal("O'Brien")).isEqualTo("'O''Brien'");
  }

  @Test
  @DisplayName(
      "GET /api/pool/exhaust: maxPoolSize + 1 connections, the last one waits for a holder")
  void poolExhaustion() throws Exception {
    PoolExhauster exhauster = new PoolExhauster(counting, dataSource.maxPoolSize());
    Map<String, Object> result = exhauster.exhaust(400);
    assertThat(result)
        .containsEntry("maxPoolSize", 3)
        .containsEntry("connectionsRequested", 4)
        .containsEntry("allHeld", true)
        .containsEntry("held", 3)
        .containsEntry("failedHolders", 0);
    assertThat((Long) result.get("waitedMs")).isGreaterThanOrEqualTo(300L);
    assertThatThrownBy(() -> exhauster.exhaust(PoolExhauster.MAX_HOLD_MS + 1))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
