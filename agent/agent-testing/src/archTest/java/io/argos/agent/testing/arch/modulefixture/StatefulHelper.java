/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.modulefixture;

/** Violating helper fixture: static mutable state (10 §5.4). */
public class StatefulHelper {
  /** A compile-time constant is allowed. */
  public static final String NAME = "stateful";

  static int calls;

  protected StatefulHelper() {}

  public static int count() {
    return ++calls;
  }
}
