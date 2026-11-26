-- SPDX-License-Identifier: Apache-2.0
-- Sample app schema for TmaxTibero (SAMPLE_DB=tibero, WO-50; trusted runners only, not checked by a
-- run yet). Plain statements: ShopSchema runs each one only when the JDBC metadata does not list
-- the object yet (DbKind.createsMissingObjectsOnly). audit_log ids come from audit_log_seq
-- (DbKind.auditInsertSql). Statements end with ';' at the end of a line.
CREATE TABLE customers (
  id NUMBER(19) PRIMARY KEY,
  name VARCHAR2(100) NOT NULL,
  email VARCHAR2(200) NOT NULL
);
CREATE TABLE products (
  id NUMBER(19) PRIMARY KEY,
  name VARCHAR2(100) NOT NULL,
  price NUMBER(12, 2) NOT NULL
);
CREATE TABLE orders (
  id NUMBER(19) PRIMARY KEY,
  customer_id NUMBER(19) NOT NULL,
  status VARCHAR2(20) NOT NULL,
  amount NUMBER(12, 2) NOT NULL,
  created_at TIMESTAMP NOT NULL
);
CREATE TABLE order_items (
  id NUMBER(19) PRIMARY KEY,
  order_id NUMBER(19) NOT NULL,
  product_id NUMBER(19) NOT NULL,
  quantity NUMBER(10) NOT NULL
);
CREATE INDEX order_items_order_idx ON order_items (order_id);
CREATE TABLE audit_log (
  id NUMBER(19) PRIMARY KEY,
  message VARCHAR2(500) NOT NULL,
  created_at TIMESTAMP NOT NULL
);
CREATE SEQUENCE audit_log_seq START WITH 1 INCREMENT BY 1;
