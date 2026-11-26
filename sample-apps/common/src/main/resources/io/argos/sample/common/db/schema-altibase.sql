-- SPDX-License-Identifier: Apache-2.0
-- Sample app schema for Altibase (SAMPLE_DB=altibase, WO-50; trusted runners only, not checked by a
-- run yet). Plain statements: ShopSchema runs each one only when the JDBC metadata does not list
-- the object yet (DbKind.createsMissingObjectsOnly). Altibase has no identity column: audit_log ids
-- come from audit_log_seq (DbKind.auditInsertSql). Statements end with ';' at the end of a line.
CREATE TABLE customers (
  id BIGINT PRIMARY KEY,
  name VARCHAR(100) NOT NULL,
  email VARCHAR(200) NOT NULL
);
CREATE TABLE products (
  id BIGINT PRIMARY KEY,
  name VARCHAR(100) NOT NULL,
  price NUMERIC(12, 2) NOT NULL
);
CREATE TABLE orders (
  id BIGINT PRIMARY KEY,
  customer_id BIGINT NOT NULL,
  status VARCHAR(20) NOT NULL,
  amount NUMERIC(12, 2) NOT NULL,
  created_at DATE NOT NULL
);
CREATE TABLE order_items (
  id BIGINT PRIMARY KEY,
  order_id BIGINT NOT NULL,
  product_id BIGINT NOT NULL,
  quantity INTEGER NOT NULL
);
CREATE INDEX order_items_order_idx ON order_items (order_id);
CREATE TABLE audit_log (
  id BIGINT PRIMARY KEY,
  message VARCHAR(500) NOT NULL,
  created_at DATE NOT NULL
);
CREATE SEQUENCE audit_log_seq START WITH 1 INCREMENT BY 1;
