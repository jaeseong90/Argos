/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.violating.instrumentation.demo;

import io.argos.agent.testing.arch.fixture.app.Order;
import io.argos.agent.testing.arch.fixture.violating.bootstrap.ContextStore;
import io.argos.agent.testing.arch.fixture.violating.instrumentation.api.TypeTransformer;
import net.bytebuddy.matcher.ElementMatchers;

/**
 * A-7 violations: enter advice on a constructor matcher, an advice name that is not a class
 * literal. A-9 violations: store values of an application type and of Object.
 */
public final class ConstructorEnterInstrumentation {
  public void transform(TypeTransformer transformer, String adviceName) {
    transformer.applyAdvice(
        ElementMatchers.isConstructor(), ConstructorEnterAdvice.class.getName());
    transformer.applyAdvice(ElementMatchers.named("<init>"), adviceName);
  }

  public int stores() {
    int created = 0;
    created += ContextStore.of(Thread.class, Order.class) == null ? 0 : 1;
    created += ContextStore.of(Thread.class, Object.class) == null ? 0 : 1;
    return created;
  }
}
