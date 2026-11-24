/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.scheduler;

import java.lang.reflect.Method;
import org.springframework.scheduling.support.ScheduledMethodRunnable;

/**
 * Spring {@code @Scheduled} names (10 §6.8): {@code BATCH <user class>#<method>} of a {@code
 * ScheduledMethodRunnable}, read through its public getters ({@code getTarget()}, {@code
 * getMethod()}, the same in Spring 5.3 to 7.0). The class is the user class of the target ({@link
 * SchedulerHelper#userClassName}, WO-18 assumption A-1).
 */
public final class ScheduledHelper {
  private ScheduledHelper() {}

  /** The service name of {@code runnable}, or null when it has no method. */
  public static String serviceName(Object runnable) {
    ScheduledMethodRunnable scheduled = (ScheduledMethodRunnable) runnable;
    Method method = scheduled.getMethod();
    if (method == null) {
      return null;
    }
    Object target = scheduled.getTarget();
    String className =
        SchedulerHelper.userClassName(
            target == null ? null : target.getClass().getName(),
            method.getDeclaringClass().getName());
    return SchedulerHelper.PREFIX + className + "#" + method.getName();
  }
}
