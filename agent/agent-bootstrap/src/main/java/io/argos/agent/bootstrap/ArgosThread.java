/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.bootstrap;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Common type of every agent thread (10 §3, §14). {@link Argos#skip(int)} ignores work done on
 * these threads, so the agent never records its own I/O. Agent threads are daemons and carry no
 * context class loader (they never pin an application class loader).
 */
public final class ArgosThread extends Thread {

  public ArgosThread(Runnable task, String name) {
    super(task, name);
    setDaemon(true);
    setContextClassLoader(null);
  }

  /** True when the current thread is an agent thread. */
  public static boolean isAgentThread() {
    return Thread.currentThread() instanceof ArgosThread;
  }

  /**
   * A factory of agent threads named {@code name} (the first thread) and {@code name-2}, {@code
   * name-3}, ... for further threads.
   */
  public static ThreadFactory factory(final String name) {
    return new ThreadFactory() {
      private final AtomicInteger count = new AtomicInteger();

      @Override
      public Thread newThread(Runnable task) {
        int n = count.incrementAndGet();
        return new ArgosThread(task, n == 1 ? name : name + "-" + n);
      }
    };
  }
}
