/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.violating.instrumentation.demo;

import net.bytebuddy.implementation.StubMethod;

/** T-AG-002 violation: StubMethod, an Implementation that empties a method body (A-6). */
public final class StubMethodInstrumentation {
  public Object implementation() {
    return StubMethod.INSTANCE;
  }
}
