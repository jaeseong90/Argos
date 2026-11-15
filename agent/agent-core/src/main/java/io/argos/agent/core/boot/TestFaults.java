/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.boot;

import io.argos.agent.core.config.AgentConfig;
import io.argos.agent.core.log.AgentLogger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Test-only fault injection ({@code -Dargos.test.faults=<spec>[,<spec>...]}, 18 §5.7, 10 §19,
 * T-AG-040/041). Release builds never see the setting ({@code AgentConfig}, T-BLD-010); without it
 * every set is empty and nothing changes.
 *
 * <ul>
 *   <li>{@code advice-throw:<module>}: the module's advice throws on every call (the module state
 *       {@code TEST_FAULT}, {@code Argos.skip} throws); the inlined exception handler counts it.
 *   <li>{@code transform-error:<module>}: every transformation by the module fails (the class stays
 *       unchanged); {@code TransformErrorListener} counts it and disables the module at {@code
 *       instrumentation.transform-error-threshold}.
 *   <li>{@code helper-nocdef:<module>}: the module's helper classes are not injected, so its advice
 *       meets a {@code NoClassDefFoundError} at run time (suppressed and counted like any advice
 *       error).
 * </ul>
 *
 * <p>The unit is the module: every advice of the module is affected. A point-level spec ({@code
 * advice-throw:servlet.enter}, {@code advice-throw:jdbc.exit}, the 18 §5.7 examples) is rejected
 * with one WARN, because the shared {@code Argos.skip} guard cannot tell an enter advice from an
 * exit advice. A spec whose module is not installed (unknown id, or turned off by {@code
 * instrumentation.disabled}) is reported with one WARN once the modules are known ({@link
 * #reportUnmatched}), so that a fault test can never pass without its fault.
 */
final class TestFaults {
  static final String ADVICE_THROW = "advice-throw:";
  static final String TRANSFORM_ERROR = "transform-error:";
  static final String HELPER_NOCDEF = "helper-nocdef:";

  /** Suffixes of the point-level specs of 18 §5.7, which this implementation does not support. */
  static final String[] POINT_SUFFIXES = {".enter", ".exit"};

  /** No fault (release builds, unit tests, normal runs). */
  static final TestFaults NONE =
      new TestFaults(
          Collections.<String>emptySet(),
          Collections.<String>emptySet(),
          Collections.<String>emptySet());

  private final Set<String> adviceThrow;
  private final Set<String> transformError;
  private final Set<String> helperNocdef;

  private TestFaults(
      Set<String> adviceThrow, Set<String> transformError, Set<String> helperNocdef) {
    this.adviceThrow = adviceThrow;
    this.transformError = transformError;
    this.helperNocdef = helperNocdef;
  }

  /**
   * Parses {@code test.faults}; unsupported and point-level specs are reported with one WARN each
   * and ignored.
   */
  static TestFaults from(AgentConfig config) {
    List<String> warnings = new ArrayList<String>();
    TestFaults faults = parse(config.testFaults(), warnings);
    for (String warning : warnings) {
      AgentLogger.warn("{}", warning);
    }
    return faults;
  }

  /** {@link #from} without logging: the WARN messages go to {@code warnings} (unit tests). */
  static TestFaults parse(List<String> specs, List<String> warnings) {
    Set<String> adviceThrow = new LinkedHashSet<String>();
    Set<String> transformError = new LinkedHashSet<String>();
    Set<String> helperNocdef = new LinkedHashSet<String>();
    for (String fault : specs) {
      String kind = kindOf(fault);
      if (kind == null) {
        warnings.add(
            "argos.test.faults: unsupported fault '"
                + fault
                + "' (advice-throw:<module>, transform-error:<module>, helper-nocdef:<module>)");
        continue;
      }
      String target = fault.substring(kind.length());
      if (isPointLevel(target)) {
        warnings.add(
            "argos.test.faults: point-level fault '"
                + fault
                + "' is not supported; use "
                + kind
                + "<module>");
        continue;
      }
      if (kind.equals(ADVICE_THROW)) {
        adviceThrow.add(target);
      } else if (kind.equals(TRANSFORM_ERROR)) {
        transformError.add(target);
      } else {
        helperNocdef.add(target);
      }
    }
    if (adviceThrow.isEmpty() && transformError.isEmpty() && helperNocdef.isEmpty()) {
      return NONE;
    }
    return new TestFaults(adviceThrow, transformError, helperNocdef);
  }

  private static String kindOf(String fault) {
    for (String kind : new String[] {ADVICE_THROW, TRANSFORM_ERROR, HELPER_NOCDEF}) {
      if (fault.startsWith(kind)) {
        return kind;
      }
    }
    return null;
  }

  private static boolean isPointLevel(String target) {
    for (String suffix : POINT_SUFFIXES) {
      if (target.endsWith(suffix)) {
        return true;
      }
    }
    return false;
  }

  boolean adviceThrows(String moduleId) {
    return adviceThrow.contains(moduleId);
  }

  boolean transformFails(String moduleId) {
    return transformError.contains(moduleId);
  }

  boolean helpersMissing(String moduleId) {
    return helperNocdef.contains(moduleId);
  }

  /** Logs one WARN per injected fault of {@code moduleId} (called once per installed module). */
  void announce(String moduleId) {
    if (adviceThrows(moduleId)) {
      AgentLogger.warn("argos.test.faults: advice of module {} throws (test fault)", moduleId);
    }
    if (transformFails(moduleId)) {
      AgentLogger.warn(
          "argos.test.faults: transformations of module {} fail (test fault)", moduleId);
    }
    if (helpersMissing(moduleId)) {
      AgentLogger.warn(
          "argos.test.faults: helper classes of module {} are not injected (test fault)", moduleId);
    }
  }

  /**
   * Logs one WARN per spec that injects nothing: its module is turned off by {@code
   * instrumentation.disabled} or is not installed at all (called once, after module discovery).
   */
  void reportUnmatched(Collection<String> installed, Collection<String> disabledByConfig) {
    for (String warning : unmatched(installed, disabledByConfig)) {
      AgentLogger.warn("{}", warning);
    }
  }

  /** The WARN messages of {@link #reportUnmatched} (unit tests). */
  List<String> unmatched(Collection<String> installed, Collection<String> disabledByConfig) {
    List<String> warnings = new ArrayList<String>();
    unmatched(ADVICE_THROW, adviceThrow, installed, disabledByConfig, warnings);
    unmatched(TRANSFORM_ERROR, transformError, installed, disabledByConfig, warnings);
    unmatched(HELPER_NOCDEF, helperNocdef, installed, disabledByConfig, warnings);
    return warnings;
  }

  private static void unmatched(
      String kind,
      Set<String> targets,
      Collection<String> installed,
      Collection<String> disabledByConfig,
      List<String> warnings) {
    for (String target : targets) {
      if (installed.contains(target)) {
        continue;
      }
      if (disabledByConfig.contains(target)) {
        warnings.add(
            "argos.test.faults: '"
                + kind
                + target
                + "' names module "
                + target
                + ", which instrumentation.disabled turns off: no fault is injected");
      } else {
        warnings.add(
            "argos.test.faults: '"
                + kind
                + target
                + "' names no installed module (installed: "
                + new TreeSet<String>(installed)
                + ")");
      }
    }
  }

  /** The exception of an injected transformation failure. */
  static IllegalStateException transformFailure(String moduleId) {
    return new IllegalStateException(
        "argos.test.faults: injected transformation failure of module " + moduleId);
  }
}
