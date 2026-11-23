/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.bootstrap;

import java.lang.reflect.Constructor;

/**
 * Creates {@link AgentControl} capabilities for module unit tests. The bootstrap API sits on the
 * boot class path in these tests (as with the agent jar) while this class is loaded by the test
 * class loader, so the package-private constructor is reached reflectively.
 */
public final class TestControls {
  private TestControls() {}

  public static AgentControl newControl() {
    try {
      Constructor<AgentControl> constructor = AgentControl.class.getDeclaredConstructor();
      constructor.setAccessible(true);
      return constructor.newInstance();
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }
}
