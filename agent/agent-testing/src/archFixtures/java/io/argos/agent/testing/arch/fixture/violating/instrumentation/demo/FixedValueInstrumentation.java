/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.violating.instrumentation.demo;

import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.implementation.FixedValue;
import net.bytebuddy.matcher.ElementMatchers;

/** T-AG-002 violation: replaces a method body with a fixed value (intercept + FixedValue, A-6). */
public final class FixedValueInstrumentation {
  public DynamicType.Builder<?> transform(DynamicType.Builder<?> builder) {
    return builder.method(ElementMatchers.named("toString")).intercept(FixedValue.value("fixed"));
  }
}
