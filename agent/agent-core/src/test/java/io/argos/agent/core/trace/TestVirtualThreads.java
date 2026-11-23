/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.trace;

import io.argos.agent.core.context.VirtualThreads;
import java.lang.reflect.Method;

/**
 * Starts virtual threads from tests compiled for Java 8 ({@code
 * Thread.ofVirtual().name(n).start(r)} through reflection; the tests that use it assume {@link
 * VirtualThreads#supported()}).
 */
public final class TestVirtualThreads {
  private TestVirtualThreads() {}

  /** A started virtual thread named {@code name} running {@code task}. */
  public static Thread start(String name, Runnable task) {
    try {
      Object builder = Thread.class.getMethod("ofVirtual").invoke(null);
      Class<?> type = Class.forName("java.lang.Thread$Builder");
      Method named = type.getMethod("name", String.class);
      Object namedBuilder = named.invoke(builder, name);
      return (Thread) type.getMethod("start", Runnable.class).invoke(namedBuilder, task);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("virtual threads need JDK 21+", e);
    }
  }

  /** Runs {@code task} on a new virtual thread named {@code name} and waits for it. */
  public static void run(String name, Runnable task) throws InterruptedException {
    Thread thread = start(name, task);
    thread.join(30_000);
    if (thread.isAlive()) {
      throw new IllegalStateException("virtual thread " + name + " did not end");
    }
  }
}
