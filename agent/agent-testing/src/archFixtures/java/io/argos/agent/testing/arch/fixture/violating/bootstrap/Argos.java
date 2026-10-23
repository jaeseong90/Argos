/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.violating.bootstrap;

/** Same API as the compliant bootstrap: the violating classes break other rules only. */
public final class Argos {
  private static volatile boolean enabled = true;

  private Argos() {}

  public static boolean skip(int moduleId) {
    return !enabled || moduleId < 0;
  }

  public static Object tracer() {
    return enabled ? Argos.class : null;
  }

  static void disable() {
    enabled = false;
  }
}
