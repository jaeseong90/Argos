/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.common.async;

import io.argos.sample.common.seed.Seeds;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * The custom executor of SAMPLE_CUSTOM_EXECUTOR=true (18 §4.4): a {@link ThreadPoolExecutor}
 * subclass (not on the agent's executor allow-list by class name) whose threads are named {@code
 * seed-ARGOS_SEED-%d}, the thread-name seed of 18 §4.3. Bounded queue, abort on overflow.
 */
public final class SeedThreadPoolExecutor extends ThreadPoolExecutor {

  /** Thread name prefix: {@code seed-ARGOS_SEED-}. */
  public static final String THREAD_NAME_PREFIX = "seed-" + Seeds.SENTINEL + "-";

  public SeedThreadPoolExecutor(int threads) {
    super(
        threads,
        threads,
        60,
        TimeUnit.SECONDS,
        new ArrayBlockingQueue<Runnable>(1000),
        new NamedThreadFactory(THREAD_NAME_PREFIX, true));
  }
}
