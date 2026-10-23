/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.violating.instrumentation.demo;

import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.implementation.MethodDelegation;

/** T-AG-002 violation: MethodDelegation instead of inlined advice (DSN-02-01, 10 §5.3 A-6). */
public final class DelegationInstrumentation {
  public Implementation implementation() {
    return MethodDelegation.to(DemoHelper.class);
  }
}
