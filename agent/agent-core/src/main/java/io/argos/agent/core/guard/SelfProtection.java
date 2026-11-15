/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.guard;

import io.argos.agent.bootstrap.AgentControl;
import io.argos.agent.bootstrap.ModuleHealth;
import io.argos.agent.core.log.AgentLogger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Self-protection of the instrumentation modules (10 §5.2 step 5, §14 "Advice 예외" and "변환 실패",
 * F-A15, T-AG-040, T-AG-041).
 *
 * <ul>
 *   <li>{@link #adviceError(int, Throwable)}: an advice or helper error, suppressed by the advice
 *       and reported through {@code Tracer.moduleError}. Counted per module ({@code
 *       argos_agent_module_errors_total{module}}); the first error of a module is logged with its
 *       exception class (WARN, the message only at DEBUG: SEC-26); at {@code
 *       instrumentation.error-threshold} (100) errors the module becomes {@link
 *       ModuleHealth#DISABLED_BY_ERRORS} with one WARN.
 *   <li>{@link #transformErrors(long, boolean)}: a transformation failure of a type that the
 *       modules of the bit set were transforming. Counted ({@code
 *       argos_agent_transform_errors_total{reason}}, {@code circularity} for a {@code
 *       ClassCircularityError}); at {@code instrumentation.transform-error-threshold} (10) failures
 *       a module becomes {@link ModuleHealth#DISABLED_BY_ERRORS} with one WARN.
 * </ul>
 *
 * <p>A disabled module keeps its transformations; its advice returns at once ({@code Argos.skip}).
 * Only {@link ModuleHealth#ENABLED} and the test-only {@link ModuleHealth#TEST_FAULT} modules move
 * to {@code DISABLED_BY_ERRORS}; a module that configuration or the server turned off stays as it
 * is. Every method may run on application threads: lock-free counters only, never blocking, never
 * throwing (N-05). Per-module counts are bounded by the 64 module ids.
 */
public final class SelfProtection {
  private final AgentControl control;
  private final AgentCounters counters;
  private final int adviceErrorThreshold;
  private final int transformErrorThreshold;
  private final AtomicIntegerArray adviceErrors = new AtomicIntegerArray(ModuleHealth.MAX_MODULES);
  private final AtomicIntegerArray transformErrors =
      new AtomicIntegerArray(ModuleHealth.MAX_MODULES);
  private final AtomicReferenceArray<String> names =
      new AtomicReferenceArray<String>(ModuleHealth.MAX_MODULES);

  /**
   * @param control the bootstrap capability that changes module states
   * @param counters the agent's counters
   * @param adviceErrorThreshold {@code instrumentation.error-threshold} (at least 1)
   * @param transformErrorThreshold {@code instrumentation.transform-error-threshold} (at least 1)
   */
  public SelfProtection(
      AgentControl control,
      AgentCounters counters,
      int adviceErrorThreshold,
      int transformErrorThreshold) {
    this.control = control;
    this.counters = counters;
    this.adviceErrorThreshold = Math.max(1, adviceErrorThreshold);
    this.transformErrorThreshold = Math.max(1, transformErrorThreshold);
  }

  /** Module names by module id (log messages); null entries are reported by id. */
  public void setModuleNames(String[] moduleNames) {
    for (int id = 0; id < ModuleHealth.MAX_MODULES; id++) {
      names.set(id, moduleNames != null && id < moduleNames.length ? moduleNames[id] : null);
    }
    counters.setModuleNames(moduleNames);
  }

  public int adviceErrorThreshold() {
    return adviceErrorThreshold;
  }

  public int transformErrorThreshold() {
    return transformErrorThreshold;
  }

  /** {@code Tracer.moduleError}: one suppressed advice or helper error of {@code moduleId}. */
  public void adviceError(int moduleId, Throwable error) {
    try {
      counters.incModuleError(moduleId);
      if (!valid(moduleId)) {
        return;
      }
      if (control != null) {
        control.incrementModuleErrors(moduleId);
      }
      int count = adviceErrors.incrementAndGet(moduleId);
      if (count == 1) {
        AgentLogger.warn(
            "advice error in module {}: {} (counted; the module is disabled after {} errors)",
            moduleId,
            error == null ? "unknown" : error.getClass().getName(),
            adviceErrorThreshold);
        if (AgentLogger.isDebugEnabled()) {
          AgentLogger.debug("advice error in module {}", moduleId, error);
        }
      }
      if (count >= adviceErrorThreshold && disable(moduleId)) {
        AgentLogger.warnOnce(
            "module-advice-disabled-" + moduleId,
            "module {} disabled after {} advice errors (instrumentation.error-threshold)",
            label(moduleId),
            count);
      }
    } catch (Throwable ignored) {
      // Called from the advice exception path: never throw.
    }
  }

  /**
   * One transformation failure of a type that the modules in {@code moduleBits} (bit = module id)
   * were transforming; {@code circularity} for a {@code ClassCircularityError}.
   */
  public void transformErrors(long moduleBits, boolean circularity) {
    try {
      counters.incTransformError(circularity ? "circularity" : "error");
      for (int id = 0; id < ModuleHealth.MAX_MODULES; id++) {
        if ((moduleBits & (1L << id)) == 0) {
          continue;
        }
        if (control != null) {
          control.incrementModuleErrors(id);
        }
        int count = transformErrors.incrementAndGet(id);
        if (count >= transformErrorThreshold && disable(id)) {
          AgentLogger.warnOnce(
              "module-transform-disabled-" + id,
              "module {} disabled after {} transformation errors"
                  + " (instrumentation.transform-error-threshold)",
              label(id),
              count);
        }
      }
    } catch (Throwable ignored) {
      // Called from ByteBuddy's listener: never throw.
    }
  }

  /** Advice errors counted for {@code moduleId}. */
  public int adviceErrors(int moduleId) {
    return valid(moduleId) ? adviceErrors.get(moduleId) : 0;
  }

  /** Transformation failures counted for {@code moduleId}. */
  public int transformErrorCount(int moduleId) {
    return valid(moduleId) ? transformErrors.get(moduleId) : 0;
  }

  /** Moves an enabled module to DISABLED_BY_ERRORS; true when this call changed the state. */
  private boolean disable(int moduleId) {
    if (control == null) {
      return false;
    }
    int state = ModuleHealth.state(moduleId);
    if (state != ModuleHealth.ENABLED && state != ModuleHealth.TEST_FAULT) {
      return false;
    }
    control.setModuleState(moduleId, ModuleHealth.DISABLED_BY_ERRORS);
    return true;
  }

  /** The module's name, or its id when unnamed (an identifier, never collected data). */
  private String label(int moduleId) {
    String name = names.get(moduleId);
    return name != null ? name : String.valueOf(moduleId);
  }

  private static boolean valid(int moduleId) {
    return moduleId >= 0 && moduleId < ModuleHealth.MAX_MODULES;
  }
}
