/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.violating.instrumentation.demo;

import io.argos.agent.testing.arch.fixture.thirdparty.ThirdPartyQueue;

/** B-4 violation: a helper that uses a shaded (relocated) library. */
public final class ThirdPartyHelper {
  private static final ThirdPartyQueue QUEUE = new ThirdPartyQueue();

  private ThirdPartyHelper() {}

  public static boolean submit(Object value) {
    return QUEUE.offer(value);
  }
}
