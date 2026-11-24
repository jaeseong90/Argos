/* SPDX-License-Identifier: Apache-2.0 */
package com.example.jobs;

import java.util.concurrent.atomic.AtomicInteger;

/** Plain scheduled-method targets for {@code ScheduledMethodRunnable} built by hand. */
public class PlainJobs {
  /** The exception {@link #fail()} threw last (identity check: the advice never replaces it). */
  static volatile IllegalStateException lastFailure;

  static final AtomicInteger works = new AtomicInteger();

  public void work() {
    works.incrementAndGet();
  }

  public void fail() {
    IllegalStateException failure = new IllegalStateException("scheduled job failed");
    lastFailure = failure;
    throw failure;
  }
}
