/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.common.db;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;

/**
 * Schema and data of the shop catalog. The data is deterministic (no random numbers, no clock): 100
 * customers, 500 products, 1,000 orders with exactly 20 items each, every item of an order pointing
 * to a different product (so the N+1 endpoint issues 20 product queries, 18 §4.2).
 */
public final class ShopSchema {

  public static final int CUSTOMERS = 100;
  public static final int PRODUCTS = 500;
  public static final int ORDERS = 1000;
  public static final int ITEMS_PER_ORDER = 20;
  public static final int PAGE_SIZE = 100;

  private static final String[] NAMES = {"kim", "lee", "park", "choi", "jung"};
  private static final String[] STATUSES = {"NEW", "PAID", "SHIPPED", "CANCELLED"};
  private static final LocalDateTime DATA_EPOCH = LocalDateTime.of(2026, 1, 1, 0, 0);
  private static final int BATCH = 1000;

  private ShopSchema() {}

  /** Creates the tables (idempotent) and inserts the data when the tables are empty. */
  public static void initialize(DataSource dataSource, DbKind db) {
    try (Connection connection = dataSource.getConnection()) {
      boolean autoCommit = connection.getAutoCommit();
      connection.setAutoCommit(false);
      try {
        try (Statement statement = connection.createStatement()) {
          for (String sql : statements(db)) {
            statement.execute(sql);
          }
        }
        connection.commit();
        if (isEmpty(connection)) {
          insertData(connection);
          connection.commit();
        }
      } catch (SQLException | RuntimeException e) {
        connection.rollback();
        throw e;
      } finally {
        connection.setAutoCommit(autoCommit);
      }
    } catch (SQLException e) {
      throw new ShopDataException("Schema initialization failed for SAMPLE_DB=" + db.id(), e);
    }
  }

  /** Product id of item {@code index} (0-based) of order {@code orderId}. */
  public static long productIdOf(long orderId, int index) {
    return ((orderId - 1) * ITEMS_PER_ORDER + index) % PRODUCTS + 1;
  }

  /** Email of customer {@code id}, e.g. kim1@example.test. */
  public static String emailOf(long id) {
    return NAMES[(int) ((id - 1) % NAMES.length)] + id + "@example.test";
  }

  /**
   * The statements of the schema script of {@code db}: comment lines ({@code --}) and blank lines
   * are skipped; a statement ends with {@code ;} at the end of a line (the {@code ;} is dropped). A
   * PL/SQL block (Oracle: a statement starting with {@code BEGIN} or {@code DECLARE}) ends with a
   * line holding only {@code /}, as in SQL*Plus, and keeps its own semicolons.
   */
  static List<String> statements(DbKind db) {
    String script = readResource(db.schemaResource());
    List<String> result = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    boolean block = false;
    for (String line : script.split("\n", -1)) {
      String trimmed = line.trim();
      if (block) {
        if (trimmed.equals("/")) {
          result.add(current.toString().trim());
          current.setLength(0);
          block = false;
        } else {
          current.append(line).append('\n');
        }
        continue;
      }
      if (trimmed.isEmpty() || trimmed.startsWith("--")) {
        continue;
      }
      if (current.length() == 0 && startsBlock(trimmed)) {
        block = true;
        current.append(line).append('\n');
        continue;
      }
      current.append(line).append('\n');
      if (trimmed.endsWith(";")) {
        String sql = current.toString().trim();
        result.add(sql.substring(0, sql.length() - 1));
        current.setLength(0);
      }
    }
    if (block) {
      throw new IllegalStateException(db.schemaResource() + ": PL/SQL block without a '/' line");
    }
    if (current.toString().trim().length() > 0) {
      throw new IllegalStateException(db.schemaResource() + ": last statement has no ';'");
    }
    return result;
  }

  private static boolean startsBlock(String line) {
    String upper = line.toUpperCase(java.util.Locale.ROOT);
    return upper.equals("BEGIN")
        || upper.startsWith("BEGIN ")
        || upper.equals("DECLARE")
        || upper.startsWith("DECLARE ");
  }

  private static String readResource(String name) {
    ClassLoader loader = ShopSchema.class.getClassLoader();
    try (InputStream in = loader.getResourceAsStream(name)) {
      if (in == null) {
        throw new IllegalStateException("Missing classpath resource " + name);
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      int read;
      while ((read = in.read(buffer)) != -1) {
        out.write(buffer, 0, read);
      }
      return new String(out.toByteArray(), StandardCharsets.UTF_8).replace("\r", "");
    } catch (IOException e) {
      throw new IllegalStateException("Cannot read " + name, e);
    }
  }

  private static boolean isEmpty(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM customers")) {
      return rs.next() && rs.getLong(1) == 0;
    }
  }

  private static void insertData(Connection connection) throws SQLException {
    try (PreparedStatement ps =
        connection.prepareStatement("INSERT INTO customers (id, name, email) VALUES (?, ?, ?)")) {
      for (long id = 1; id <= CUSTOMERS; id++) {
        ps.setLong(1, id);
        ps.setString(2, NAMES[(int) ((id - 1) % NAMES.length)] + " " + id);
        ps.setString(3, emailOf(id));
        ps.addBatch();
      }
      ps.executeBatch();
    }
    try (PreparedStatement ps =
        connection.prepareStatement("INSERT INTO products (id, name, price) VALUES (?, ?, ?)")) {
      for (long id = 1; id <= PRODUCTS; id++) {
        ps.setLong(1, id);
        ps.setString(2, "product-" + id);
        ps.setBigDecimal(3, BigDecimal.valueOf(1000 + (id * 37) % 9000, 2));
        ps.addBatch();
      }
      ps.executeBatch();
    }
    try (PreparedStatement ps =
        connection.prepareStatement(
            "INSERT INTO orders (id, customer_id, status, amount, created_at) VALUES (?, ?, ?, ?, ?)")) {
      for (long id = 1; id <= ORDERS; id++) {
        ps.setLong(1, id);
        ps.setLong(2, (id - 1) % CUSTOMERS + 1);
        ps.setString(3, STATUSES[(int) (id % STATUSES.length)]);
        ps.setBigDecimal(4, BigDecimal.valueOf(10_000 + (id * 131) % 90_000, 2));
        ps.setTimestamp(5, Timestamp.valueOf(DATA_EPOCH.plusMinutes(id)));
        ps.addBatch();
      }
      ps.executeBatch();
    }
    try (PreparedStatement ps =
        connection.prepareStatement(
            "INSERT INTO order_items (id, order_id, product_id, quantity) VALUES (?, ?, ?, ?)")) {
      int pending = 0;
      for (long orderId = 1; orderId <= ORDERS; orderId++) {
        for (int index = 0; index < ITEMS_PER_ORDER; index++) {
          long id = (orderId - 1) * ITEMS_PER_ORDER + index + 1;
          ps.setLong(1, id);
          ps.setLong(2, orderId);
          ps.setLong(3, productIdOf(orderId, index));
          ps.setInt(4, (int) (id % 5) + 1);
          ps.addBatch();
          if (++pending == BATCH) {
            ps.executeBatch();
            pending = 0;
          }
        }
      }
      if (pending > 0) {
        ps.executeBatch();
      }
    }
  }
}
