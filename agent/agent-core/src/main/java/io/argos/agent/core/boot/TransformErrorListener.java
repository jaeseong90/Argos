/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.boot;

import io.argos.agent.bootstrap.AgentControl;
import io.argos.agent.bootstrap.ModuleHealth;
import io.argos.agent.core.log.AgentLogger;
import java.util.concurrent.atomic.AtomicLong;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.utility.JavaModule;

/**
 * Transformation failures (10 §14 "변환 실패", F-A15): the class stays unchanged (ByteBuddy returns the
 * original bytes), the failure is counted against the modules that were transforming the type, and
 * a module reaching {@code instrumentation.transform-error-threshold} (10) is disabled. {@code
 * ClassCircularityError} is counted separately ({@code reason=circularity}).
 */
final class TransformErrorListener extends AgentBuilder.Listener.Adapter {
  private static final int LOGGED_ERRORS = 20;

  private final AgentControl control;
  private final int threshold;
  private final String[] moduleNames;
  private final TransformRecorder recorder;
  private final AtomicLong errors = new AtomicLong();
  private final AtomicLong circularity = new AtomicLong();

  /**
   * @param recorder receives every failure for {@code debug.transform-log} (T-AG-005)
   */
  TransformErrorListener(
      AgentControl control, int threshold, String[] moduleNames, TransformRecorder recorder) {
    this.control = control;
    this.threshold = threshold;
    this.moduleNames = moduleNames.clone();
    this.recorder = recorder;
  }

  @Override
  public void onError(
      String typeName,
      ClassLoader classLoader,
      JavaModule module,
      boolean loaded,
      Throwable throwable) {
    long bits = TransformRecorder.takeModules();
    long count = errors.incrementAndGet();
    if (throwable instanceof ClassCircularityError) {
      circularity.incrementAndGet();
    }
    recorder.recordError(typeName, classLoader, bits, throwable);
    if (count <= LOGGED_ERRORS) {
      AgentLogger.warn(
          "transformation of {} ({}) failed, class left unchanged: {}",
          typeName,
          TransformRecorder.loaderName(classLoader),
          String.valueOf(throwable));
      if (AgentLogger.isDebugEnabled()) {
        AgentLogger.debug("transformation failure of {}", typeName, throwable);
      }
    }
    for (int id = 0; id < 64; id++) {
      if ((bits & (1L << id)) == 0) {
        continue;
      }
      int moduleErrors = control.incrementModuleErrors(id);
      if (moduleErrors >= threshold && ModuleHealth.state(id) == ModuleHealth.ENABLED) {
        control.setModuleState(id, ModuleHealth.DISABLED_BY_ERRORS);
        AgentLogger.warnOnce(
            "module-transform-disabled-" + id,
            "module {} disabled after {} transformation errors"
                + " (instrumentation.transform-error-threshold)",
            id < moduleNames.length && moduleNames[id] != null ? moduleNames[id] : id,
            moduleErrors);
      }
    }
  }

  long errors() {
    return errors.get();
  }

  long circularityErrors() {
    return circularity.get();
  }
}
