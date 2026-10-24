/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.common.diag;

import java.util.concurrent.TimeUnit;

/**
 * GET /api/threads/dump-me (18 §4.2): blocks the request thread so that a thread dump shows this
 * frame ({@code ThreadDumpTarget.blockHere}) at a known place (single-stack matching tests).
 */
public final class ThreadDumpTarget {

  /** Default block time of the catalog. */
  public static final long DEFAULT_MS = 10_000;

  /** Upper bound of {@code ms}. */
  public static final long MAX_MS = 60_000;

  private ThreadDumpTarget() {}

  /** Blocks the current thread for {@code millis} and returns the thread name. */
  public static String blockHere(long millis) throws InterruptedException {
    if (millis < 0 || millis > MAX_MS) {
      throw new IllegalArgumentException("ms must be between 0 and " + MAX_MS);
    }
    TimeUnit.MILLISECONDS.sleep(millis);
    return Thread.currentThread().getName();
  }
}
