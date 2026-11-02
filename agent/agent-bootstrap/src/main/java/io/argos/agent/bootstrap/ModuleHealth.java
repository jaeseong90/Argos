/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.bootstrap;

import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * Runtime state and error counter of every instrumentation module (10 §3, §5.2): advice of a module
 * that is not {@link #ENABLED} returns at once through {@link Argos#skip(int)}, while its
 * transformations stay installed. Module IDs are 0..{@value #MAX_MODULES}-1.
 */
public final class ModuleHealth {
  public static final int MAX_MODULES = 64;

  public static final int ENABLED = 0;
  public static final int DISABLED_BY_CONFIG = 1;
  public static final int DISABLED_BY_ERRORS = 2;
  public static final int DISABLED_BY_REMOTE = 3;

  /**
   * Test-only fault injection ({@code argos.test.faults=advice-throw:<module>}, 18 §5.x, T-AG-040):
   * {@link Argos#skip(int)} throws, as if the module's enter advice failed.
   */
  public static final int TEST_FAULT = 9;

  private static final String[] NAMES = {
    "ENABLED", "DISABLED_BY_CONFIG", "DISABLED_BY_ERRORS", "DISABLED_BY_REMOTE"
  };

  private static final AtomicIntegerArray STATES = new AtomicIntegerArray(MAX_MODULES);
  private static final AtomicIntegerArray ERRORS = new AtomicIntegerArray(MAX_MODULES);

  private ModuleHealth() {}

  /** True when advice of {@code moduleId} may run. */
  public static boolean enabled(int moduleId) {
    return valid(moduleId) && STATES.get(moduleId) == ENABLED;
  }

  /** The state of {@code moduleId} ({@link #ENABLED}, {@link #DISABLED_BY_CONFIG}, ...). */
  public static int state(int moduleId) {
    return valid(moduleId) ? STATES.get(moduleId) : DISABLED_BY_CONFIG;
  }

  /** Advice and transform errors counted for {@code moduleId}. */
  public static int errors(int moduleId) {
    return valid(moduleId) ? ERRORS.get(moduleId) : 0;
  }

  /** Name of a state constant. */
  public static String stateName(int state) {
    if (state == TEST_FAULT) {
      return "TEST_FAULT";
    }
    return state >= 0 && state < NAMES.length ? NAMES[state] : "UNKNOWN(" + state + ")";
  }

  /** {@link Argos#skip(int)}: true unless enabled; throws for an injected test fault. */
  static boolean skip(int moduleId) {
    if (!valid(moduleId)) {
      return true;
    }
    int state = STATES.get(moduleId);
    if (state == ENABLED) {
      return false;
    }
    if (state == TEST_FAULT) {
      throw new TestFault();
    }
    return true;
  }

  static void setState(int moduleId, int state) {
    if (valid(moduleId)) {
      STATES.set(moduleId, state);
    }
  }

  static int incrementErrors(int moduleId) {
    return valid(moduleId) ? ERRORS.incrementAndGet(moduleId) : 0;
  }

  private static boolean valid(int moduleId) {
    return moduleId >= 0 && moduleId < MAX_MODULES;
  }

  /** The injected advice failure (no stack trace: cheap to throw on every advice call). */
  static final class TestFault extends RuntimeException {
    private static final long serialVersionUID = 1L;

    TestFault() {
      super("argos.test.faults: injected advice failure", null, false, false);
    }
  }
}
