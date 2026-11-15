/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.context;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * {@code Thread.isVirtual()} on JDK 21+, false before (10 §8 "가상 스레드", F-A30, DSN-10-13). The agent
 * targets Java 8, so the method is looked up once through a {@link MethodHandle} when this class is
 * initialised and cached in a static final field; a JVM without the method (8 to 20) gets a null
 * handle and every thread is a platform thread. {@link #isVirtual(Thread)} never throws.
 */
public final class VirtualThreads {
  private static final MethodHandle IS_VIRTUAL = lookup();

  private VirtualThreads() {}

  private static MethodHandle lookup() {
    try {
      return MethodHandles.publicLookup()
          .findVirtual(Thread.class, "isVirtual", MethodType.methodType(boolean.class));
    } catch (NoSuchMethodException | IllegalAccessException | RuntimeException e) {
      return null;
    }
  }

  /** True when this JVM has virtual threads ({@code Thread.isVirtual} exists). */
  public static boolean supported() {
    return IS_VIRTUAL != null;
  }

  /** True when {@code thread} is a virtual thread; false for null and before JDK 21. */
  public static boolean isVirtual(Thread thread) {
    if (IS_VIRTUAL == null || thread == null) {
      return false;
    }
    try {
      return (boolean) IS_VIRTUAL.invokeExact(thread);
    } catch (Throwable e) {
      return false;
    }
  }
}
