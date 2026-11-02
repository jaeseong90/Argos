/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.bootstrap;

/** Transaction kinds (DSN-02-15, 10 §3): the protocol {@code TransactionKind} numbers. */
public final class Kind {
  public static final int HTTP = 1;
  public static final int BATCH = 2;
  public static final int MESSAGE = 3;

  private static final String[] NAMES = {"UNSPECIFIED", "HTTP", "BATCH", "MESSAGE"};

  private Kind() {}

  /** The constant name of {@code kind}, or {@code UNKNOWN(<kind>)}. */
  public static String name(int kind) {
    return kind >= 0 && kind < NAMES.length ? NAMES[kind] : "UNKNOWN(" + kind + ")";
  }
}
