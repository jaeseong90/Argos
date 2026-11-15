/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.bootstrap;

/**
 * Dictionary kinds of {@link Tracer#dict(int, String)} (DSN-02-10). The order is the list of 02 §13
 * C-16 (10 §2 numbers 1-9, then DB_TARGET and APP_VERSION) plus POOL (ADR-0010 C-101). The protocol
 * enum {@code argos.v1.DictionaryKind} uses the same numbers (checked by the :proto contract test).
 */
public final class DictKind {
  public static final int SERVICE = 1;
  public static final int SQL = 2;
  public static final int CLASS = 3;
  public static final int METHOD = 4;
  public static final int THREAD = 5;
  public static final int HOST = 6;
  public static final int FRAME = 7;
  public static final int METRIC = 8;
  public static final int ERROR_GROUP = 9;
  public static final int DB_TARGET = 10;
  public static final int APP_VERSION = 11;

  /** Connection pool name of DBC steps (10 §6.3, ADR-0010 C-101). */
  public static final int POOL = 12;

  /** Number of kinds plus one (kinds are 1-based). */
  public static final int COUNT = 13;

  private static final String[] NAMES = {
    "UNSPECIFIED",
    "SERVICE",
    "SQL",
    "CLASS",
    "METHOD",
    "THREAD",
    "HOST",
    "FRAME",
    "METRIC",
    "ERROR_GROUP",
    "DB_TARGET",
    "APP_VERSION",
    "POOL"
  };

  private DictKind() {}

  /** The constant name of {@code kind}, or {@code UNKNOWN(<kind>)}. */
  public static String name(int kind) {
    return kind >= 0 && kind < NAMES.length ? NAMES[kind] : "UNKNOWN(" + kind + ")";
  }
}
