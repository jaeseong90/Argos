/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.violating.bootstrap;

import io.argos.agent.testing.arch.fixture.thirdparty.ThirdPartyQueue;

/** AGT-01/B-1 violation: a bootstrap class that depends on a non-JDK library. */
public final class UsesThirdParty {
  private final ThirdPartyQueue queue = new ThirdPartyQueue();

  public boolean offer(Object value) {
    return queue.offer(value);
  }
}
