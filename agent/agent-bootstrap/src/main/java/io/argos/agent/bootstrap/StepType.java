/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.bootstrap;

/**
 * Step types (02 §7 "step 타입 열거", C-1): the same numbers in the agent, the protocol and the
 * ClickHouse {@code Enum8}.
 */
public final class StepType {
  public static final int SQL = 1;
  public static final int DBC = 2;
  public static final int COMMIT = 3;
  public static final int ROLLBACK = 4;
  public static final int HTTP = 5;
  public static final int METHOD = 6;
  public static final int ASYNC = 7;
  public static final int ERROR = 8;
  public static final int STACK = 9;
  public static final int MESSAGE = 10;

  /** Aggregated step of the summary mode (10 §9.1); never passed to {@code stepStart}. */
  public static final int SUMMARY = 11;

  private static final String[] NAMES = {
    "UNSPECIFIED",
    "SQL",
    "DBC",
    "COMMIT",
    "ROLLBACK",
    "HTTP",
    "METHOD",
    "ASYNC",
    "ERROR",
    "STACK",
    "MESSAGE",
    "SUMMARY"
  };

  private StepType() {}

  /** The constant name of {@code type}, or {@code UNKNOWN(<type>)}. */
  public static String name(int type) {
    return type >= 0 && type < NAMES.length ? NAMES[type] : "UNKNOWN(" + type + ")";
  }
}
