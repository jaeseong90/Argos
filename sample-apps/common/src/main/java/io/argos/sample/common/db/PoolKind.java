/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.common.db;

import java.util.Locale;

/** Connection pools selected with SAMPLE_POOL (18 §4.1, §6.1 T-MX-100~108). */
public enum PoolKind {
  /** HikariCP (default of Spring Boot). */
  HIKARI("hikari"),
  /** Tomcat JDBC pool (org.apache.tomcat:tomcat-jdbc). */
  TOMCAT("tomcat"),
  /** Apache Commons DBCP2. */
  DBCP2("dbcp2");

  private final String id;

  PoolKind(String id) {
    this.id = id;
  }

  public static PoolKind parse(String value) {
    String normalized = value.trim().toLowerCase(Locale.ROOT);
    for (PoolKind kind : values()) {
      if (kind.id.equals(normalized)) {
        return kind;
      }
    }
    throw new IllegalArgumentException(
        "SAMPLE_POOL must be one of hikari, tomcat, dbcp2, got '" + value + "'");
  }

  /** The SAMPLE_POOL value. */
  public String id() {
    return id;
  }
}
