/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.common.db;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * JDBC operations of the shop catalog with the exact SQL counts of 18 §4.2. Every method issues the
 * documented statements and nothing else, so agent tests can assert step counts (D-24, S-03).
 * Results are plain maps and lists that the web layers serialize as JSON.
 */
public final class ShopJdbc {

  /** Characters allowed in GET /api/stmt?q= (inlined as a literal, never trusted as SQL). */
  private static final Pattern SEARCH_TEXT = Pattern.compile("[\\p{L}\\p{N} @._-]{0,100}");

  private final JdbcAccess access;
  private final DbKind db;
  private final Clock clock;

  public ShopJdbc(JdbcAccess access, DbKind db, Clock clock) {
    this.access = access;
    this.db = db;
    this.clock = clock;
  }

  public DbKind db() {
    return db;
  }

  /**
   * GET /api/orders/{id} (JDBC variant, served by shop-batch and used by shop-heavy's own
   * endpoints; the shop apps' /api/orders/{id} uses JPA): 1 PreparedStatement.
   */
  public Optional<Map<String, Object>> findOrder(long id) {
    return run(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "SELECT id, customer_id, status, amount, created_at FROM orders WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
              return rs.next() ? Optional.of(order(rs)) : Optional.<Map<String, Object>>empty();
            }
          }
        });
  }

  /**
   * GET /api/orders?page=: 3 statements (count, page of {@value ShopSchema#PAGE_SIZE} rows with
   * fetch size 100, page total).
   */
  public Map<String, Object> listOrders(int page) {
    if (page < 0 || page > 1_000_000) {
      throw new IllegalArgumentException("page must be between 0 and 1000000");
    }
    return run(
        c -> {
          long total;
          try (Statement st = c.createStatement();
              ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM orders")) {
            total = rs.next() ? rs.getLong(1) : 0;
          }
          List<Map<String, Object>> orders = new ArrayList<>();
          try (PreparedStatement ps =
              c.prepareStatement(
                  "SELECT id, customer_id, status, amount, created_at FROM orders"
                      + " ORDER BY id"
                      + db.pageClause())) {
            ps.setFetchSize(ShopSchema.PAGE_SIZE);
            long offset = (long) page * ShopSchema.PAGE_SIZE;
            if (db.offsetFirst()) {
              ps.setLong(1, offset);
              ps.setInt(2, ShopSchema.PAGE_SIZE);
            } else {
              ps.setInt(1, ShopSchema.PAGE_SIZE);
              ps.setLong(2, offset);
            }
            try (ResultSet rs = ps.executeQuery()) {
              while (rs.next()) {
                orders.add(order(rs));
              }
            }
          }
          BigDecimal pageAmount = BigDecimal.ZERO;
          if (!orders.isEmpty()) {
            try (PreparedStatement ps =
                c.prepareStatement(
                    "SELECT COALESCE(SUM(amount), 0) FROM orders WHERE id BETWEEN ? AND ?")) {
              ps.setLong(1, (Long) orders.get(0).get("id"));
              ps.setLong(2, (Long) orders.get(orders.size() - 1).get("id"));
              try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                  pageAmount = rs.getBigDecimal(1);
                }
              }
            }
          }
          Map<String, Object> result = new LinkedHashMap<>();
          result.put("page", page);
          result.put("total", total);
          result.put("rows", orders.size());
          result.put("pageAmount", pageAmount);
          result.put("orders", orders);
          return result;
        });
  }

  /**
   * JDBC fixture of the N+1 of GET /api/orders/{id}/items: one query for the 20 items and one query
   * per item for its product (1 + 20 statements). No app endpoint calls it: the shop apps serve
   * /api/orders/{id}/items through JPA (their OrderQueries, counted with a Hibernate
   * StatementInspector). Kept for JDBC-level tests (ShopJdbcTest, SampleDataSourcesTest, agent
   * tests that need the 1 + 20 statement shape without a JPA provider).
   */
  public Map<String, Object> orderItemsNPlusOne(long orderId) {
    return run(
        c -> {
          List<long[]> rows = new ArrayList<>();
          try (PreparedStatement ps =
              c.prepareStatement(
                  "SELECT id, product_id, quantity FROM order_items WHERE order_id = ? ORDER BY id")) {
            ps.setLong(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
              while (rs.next()) {
                rows.add(new long[] {rs.getLong(1), rs.getLong(2), rs.getLong(3)});
              }
            }
          }
          List<Map<String, Object>> items = new ArrayList<>();
          for (long[] row : rows) {
            try (PreparedStatement ps =
                c.prepareStatement("SELECT id, name, price FROM products WHERE id = ?")) {
              ps.setLong(1, row[1]);
              try (ResultSet rs = ps.executeQuery()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", row[0]);
                item.put("quantity", row[2]);
                if (rs.next()) {
                  item.put("productId", rs.getLong(1));
                  item.put("product", rs.getString(2));
                  item.put("price", rs.getBigDecimal(3));
                }
                items.add(item);
              }
            }
          }
          Map<String, Object> result = new LinkedHashMap<>();
          result.put("orderId", orderId);
          result.put("items", items.size());
          result.put("lines", items);
          return result;
        });
  }

  /**
   * GET /api/stmt?q=: {@code Statement.executeQuery} with the search text inlined as a literal
   * (normalization and SEC-35 tests). Only letters, digits, space and {@code @._-} are accepted.
   */
  public Map<String, Object> statementSearch(String q) {
    if (q == null || !SEARCH_TEXT.matcher(q).matches()) {
      throw new IllegalArgumentException(
          "q must be at most 100 letters, digits, spaces or the characters @._-");
    }
    String sql =
        "SELECT id, name, email FROM customers WHERE name LIKE "
            + literal("%" + q + "%")
            + " ORDER BY id";
    return run(
        c -> {
          List<Map<String, Object>> customers = new ArrayList<>();
          try (Statement st = c.createStatement();
              ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
              Map<String, Object> customer = new LinkedHashMap<>();
              customer.put("id", rs.getLong(1));
              customer.put("name", rs.getString(2));
              customer.put("email", rs.getString(3));
              customers.add(customer);
            }
          }
          Map<String, Object> result = new LinkedHashMap<>();
          result.put("q", q);
          result.put("matches", customers.size());
          result.put("customers", customers);
          return result;
        });
  }

  /** GET /api/batch-insert: {@code addBatch} x count + one {@code executeBatch}. */
  public Map<String, Object> batchInsert(int count) {
    if (count < 1 || count > 1000) {
      throw new IllegalArgumentException("count must be between 1 and 1000");
    }
    return run(
        c -> {
          int inserted = 0;
          try (PreparedStatement ps =
              c.prepareStatement("INSERT INTO audit_log (message, created_at) VALUES (?, ?)")) {
            Timestamp now = now();
            for (int i = 0; i < count; i++) {
              ps.setString(1, "batch-insert " + i);
              ps.setTimestamp(2, now);
              ps.addBatch();
            }
            for (int updated : ps.executeBatch()) {
              inserted += updated == Statement.SUCCESS_NO_INFO ? 1 : updated;
            }
          }
          Map<String, Object> result = new LinkedHashMap<>();
          result.put("batchSize", count);
          result.put("inserted", inserted);
          return result;
        });
  }

  /**
   * Inserts one audit row and returns its id (POST /api/tx/commit and /rollback). Runs inside the
   * caller's transaction when the {@link JdbcAccess} is transaction-aware.
   */
  public long insertAudit(String message) {
    return run(
        c -> {
          String insert = "INSERT INTO audit_log (message, created_at) VALUES (?, ?)";
          String[] keyColumns = db.generatedKeyColumns();
          try (PreparedStatement ps =
              keyColumns == null
                  ? c.prepareStatement(insert, Statement.RETURN_GENERATED_KEYS)
                  : c.prepareStatement(insert, keyColumns)) {
            ps.setString(1, message);
            ps.setTimestamp(2, now());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
              if (!keys.next()) {
                throw new SQLException("No generated key for audit_log");
              }
              return keys.getLong(1);
            }
          }
        });
  }

  /** Whether the audit row exists (checks a rollback). 1 statement. */
  public boolean auditExists(long id) {
    return run(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement("SELECT COUNT(*) FROM audit_log WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
              return rs.next() && rs.getLong(1) > 0;
            }
          }
        });
  }

  /** GET /api/slow?sql=1: sleeps in the database (pg_sleep, SLEEP, H2 alias). 1 statement. */
  public void sleepInDatabase(long millis) {
    run(
        c -> {
          try (PreparedStatement ps = c.prepareStatement(db.sleepSql())) {
            ps.setLong(1, millis);
            ps.execute();
          }
          return null;
        });
  }

  /** {@code SELECT 1}. */
  public void ping() {
    run(
        c -> {
          try (Statement st = c.createStatement();
              ResultSet rs = st.executeQuery(db.pingSql())) {
            return rs.next();
          }
        });
  }

  /**
   * Seed query (18 §4.3): the value as a bind parameter and as an inlined Statement literal of the
   * same query (2 statements).
   */
  public Map<String, Object> seedBindAndLiteral(String value) {
    return run(
        c -> {
          long bound;
          try (PreparedStatement ps =
              c.prepareStatement("SELECT COUNT(*) FROM customers WHERE email = ?")) {
            ps.setString(1, value);
            try (ResultSet rs = ps.executeQuery()) {
              bound = rs.next() ? rs.getLong(1) : 0;
            }
          }
          long inlined;
          try (Statement st = c.createStatement();
              ResultSet rs =
                  st.executeQuery(
                      "SELECT COUNT(*) FROM customers WHERE email = " + literal(value))) {
            inlined = rs.next() ? rs.getLong(1) : 0;
          }
          Map<String, Object> result = new LinkedHashMap<>();
          result.put("value", value);
          result.put("bindMatches", bound);
          result.put("literalMatches", inlined);
          return result;
        });
  }

  /**
   * Seed query with SQL literals written exactly as given (Korean text, doubled quote, dollar
   * quoting: 18 §4.3), one Statement per literal. Dollar-quoted literals are skipped on MySQL.
   */
  public Map<String, Object> seedRawLiterals(List<String> sqlLiterals) {
    return run(
        c -> {
          List<String> executed = new ArrayList<>();
          for (String sqlLiteral : sqlLiterals) {
            if (sqlLiteral.startsWith("$$") && !db.supportsDollarQuotes()) {
              continue;
            }
            try (Statement st = c.createStatement();
                ResultSet rs =
                    st.executeQuery("SELECT COUNT(*) FROM customers WHERE name = " + sqlLiteral)) {
              rs.next();
            }
            executed.add(sqlLiteral);
          }
          Map<String, Object> result = new LinkedHashMap<>();
          result.put("literals", executed);
          return result;
        });
  }

  /** SQL string literal of {@code value} for this database (quotes doubled, MySQL backslashes). */
  public String literal(String value) {
    String escaped = value.replace("'", "''");
    if (db.backslashEscapes()) {
      escaped = escaped.replace("\\", "\\\\");
    }
    return "'" + escaped + "'";
  }

  private Timestamp now() {
    return Timestamp.valueOf(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
  }

  private static Map<String, Object> order(ResultSet rs) throws SQLException {
    Map<String, Object> order = new LinkedHashMap<>();
    order.put("id", rs.getLong(1));
    order.put("customerId", rs.getLong(2));
    order.put("status", rs.getString(3));
    order.put("amount", rs.getBigDecimal(4));
    Timestamp created = rs.getTimestamp(5);
    order.put("createdAt", created == null ? null : created.toLocalDateTime().toString());
    return order;
  }

  /** Work on a connection. */
  private interface SqlWork<T> {
    T apply(Connection connection) throws SQLException;
  }

  private <T> T run(SqlWork<T> work) {
    Connection connection;
    try {
      connection = access.acquire();
    } catch (SQLException e) {
      throw new ShopDataException("Cannot get a connection: " + e.getMessage(), e);
    }
    try {
      return work.apply(connection);
    } catch (SQLException e) {
      throw new ShopDataException(e.getMessage(), e);
    } finally {
      try {
        access.release(connection);
      } catch (SQLException ignored) {
        // A failed release must not hide the result or the original failure of the work above.
      }
    }
  }
}
