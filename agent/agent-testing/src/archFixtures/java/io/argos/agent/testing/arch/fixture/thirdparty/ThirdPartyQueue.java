/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.thirdparty;

/** Stands in for a third-party library that the agent relocates below io.argos.agent.shaded. */
public final class ThirdPartyQueue {
  private int size;

  public boolean offer(Object value) {
    if (value == null) {
      return false;
    }
    size++;
    return true;
  }

  public int size() {
    return size;
  }
}
