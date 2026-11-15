/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.violating.instrumentation.demo;

import io.argos.agent.testing.arch.fixture.violating.instrumentation.api.TypeTransformer;
import net.bytebuddy.matcher.ElementMatchers;

/**
 * A-7 violations of WO-13: exit advice with onThrowable on a constructor matcher, and the same
 * advice on constructors and on a method.
 */
public final class ConstructorThrowableInstrumentation {
  public void transform(TypeTransformer transformer) {
    transformer.applyAdvice(
        ElementMatchers.isConstructor(), ConstructorThrowableAdvice.class.getName());
    transformer.applyAdvice(
        ElementMatchers.named("run"), ConstructorThrowableAdvice.class.getName());
  }
}
