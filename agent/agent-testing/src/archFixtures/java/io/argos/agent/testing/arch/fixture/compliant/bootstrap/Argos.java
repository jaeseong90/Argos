/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.compliant.bootstrap;

/** Static holder used by advice code (10 §3). */
public final class Argos {
  private static volatile boolean enabled = true;
  private static volatile Tracer tracer = Tracer.NOOP;

  private Argos() {}

  public static Tracer tracer() {
    return tracer;
  }

  /** First statement of every advice method (10 §5.3 A-2). */
  public static boolean skip(int moduleId) {
    return !enabled || moduleId < 0 || ArgosThread.isAgentThread();
  }

  static void install(Tracer value) {
    tracer = value;
  }

  static void disable() {
    enabled = false;
  }
}
