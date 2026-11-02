/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.bootstrap;

/**
 * Static entry of advice and helper code (10 §3). Every advice method starts with {@code if
 * (Argos.skip(MODULE_ID)) return;} (10 §5.3 A-2).
 */
public final class Argos {
  private static volatile Tracer tracer = NoopTracer.INSTANCE;

  private Argos() {}

  /** The installed tracer; a no-op tracer until the core has started. */
  public static Tracer tracer() {
    return tracer;
  }

  /** Kill switch, first stage (F-A15). */
  public static boolean enabled() {
    return KillSwitch.ENABLED;
  }

  /**
   * True when advice of {@code moduleId} must do nothing: the kill switch is off, the module is not
   * enabled, or the current thread is an agent thread (re-entrance, 10 §14).
   */
  public static boolean skip(int moduleId) {
    return !KillSwitch.ENABLED
        || ModuleHealth.skip(moduleId)
        || Thread.currentThread() instanceof ArgosThread;
  }

  /**
   * Exception handler of suppressed advice exceptions: the core inlines a call to this method into
   * every advice ({@code Advice.withExceptionHandler}), so an exception thrown by advice code is
   * counted against its module (10 §5.2, §14) instead of being silently dropped. Never throws.
   */
  public static void adviceError(Throwable error, int moduleId) {
    try {
      tracer.moduleError(moduleId, error);
    } catch (Throwable ignored) {
      // The handler runs inside application code and must not fail.
    }
  }

  static void install(Tracer value) {
    tracer = value == null ? NoopTracer.INSTANCE : value;
  }
}
