/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.trace;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * {@code Thread.isVirtual()} on JDK 21+, false before (10 §8, F-A30). The method handle is looked
 * up once (the agent targets Java 8).
 */
public final class VirtualThreads {
  private static final MethodHandle IS_VIRTUAL = lookup();

  private VirtualThreads() {}

  private static MethodHandle lookup() {
    try {
      return MethodHandles.publicLookup()
          .findVirtual(Thread.class, "isVirtual", MethodType.methodType(boolean.class));
    } catch (NoSuchMethodException | IllegalAccessException e) {
      return null;
    }
  }

  public static boolean isVirtual(Thread thread) {
    if (IS_VIRTUAL == null || thread == null) {
      return false;
    }
    try {
      return (boolean) IS_VIRTUAL.invoke(thread);
    } catch (Throwable e) {
      return false;
    }
  }
}
