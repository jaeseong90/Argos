/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.common.async;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/** Thread factory with a name prefix and a 1-based counter ({@code prefix + n}). */
public final class NamedThreadFactory implements ThreadFactory {

  private final String prefix;
  private final boolean daemon;
  private final AtomicInteger counter = new AtomicInteger();

  public NamedThreadFactory(String prefix, boolean daemon) {
    this.prefix = prefix;
    this.daemon = daemon;
  }

  @Override
  public Thread newThread(Runnable task) {
    Thread thread = new Thread(task, prefix + counter.incrementAndGet());
    thread.setDaemon(daemon);
    return thread;
  }
}
