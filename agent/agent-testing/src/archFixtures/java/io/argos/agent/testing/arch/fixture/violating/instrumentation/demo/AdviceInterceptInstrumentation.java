/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.violating.instrumentation.demo;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;

/**
 * T-AG-002 violation: Advice used as an Implementation through intercept(..), which replaces the
 * method body instead of inlining into it (A-6). No net.bytebuddy.implementation type appears in
 * this class; only the intercept call reveals it.
 */
public final class AdviceInterceptInstrumentation {
  public DynamicType.Builder<?> transform(DynamicType.Builder<?> builder) {
    return builder
        .method(ElementMatchers.named("run"))
        .intercept(Advice.to(HelperOnlyAdvice.class));
  }
}
