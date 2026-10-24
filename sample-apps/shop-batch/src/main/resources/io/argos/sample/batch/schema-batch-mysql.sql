-- SPDX-License-Identifier: Apache-2.0
-- shop-batch tables for MySQL 8 (SAMPLE_DB=mysql), next to the shop schema of common. Spring Batch
-- and Quartz create their own tables in the same database.
CREATE TABLE IF NOT EXISTS settlement_lines (
  job_execution_id BIGINT NOT NULL,
  item_id BIGINT NOT NULL,
  order_id BIGINT NOT NULL,
  amount DECIMAL(14, 2) NOT NULL,
  PRIMARY KEY (job_execution_id, item_id)
);
CREATE TABLE IF NOT EXISTS order_reports (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  reported_at TIMESTAMP NOT NULL,
  status VARCHAR(20) NOT NULL,
  order_count BIGINT NOT NULL,
  amount DECIMAL(16, 2) NOT NULL
);
