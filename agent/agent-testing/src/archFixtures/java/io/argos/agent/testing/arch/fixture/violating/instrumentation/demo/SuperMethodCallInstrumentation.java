/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.violating.instrumentation.demo;

import net.bytebuddy.implementation.SuperMethodCall;

/** T-AG-002 violation: SuperMethodCall, an Implementation that defines a method body (A-6). */
public final class SuperMethodCallInstrumentation {
  public Object implementation() {
    return SuperMethodCall.INSTANCE;
  }
}
