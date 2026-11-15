/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.compliant.instrumentation.demo;

import io.argos.agent.testing.arch.fixture.compliant.bootstrap.ContextStore;
import io.argos.agent.testing.arch.fixture.compliant.bootstrap.TraceContext;
import io.argos.agent.testing.arch.fixture.compliant.instrumentation.api.TypeTransformer;
import java.lang.ref.WeakReference;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;

/**
 * A-7: constructors get exit-only advice (directly and through a matcher method); enter advice goes
 * to methods. A-9: store values are bootstrap types, boxed primitives, long[] and WeakReference.
 */
public final class DemoConstructorInstrumentation {
  public void transform(TypeTransformer transformer) {
    transformer.applyAdvice(ElementMatchers.isConstructor(), DemoExitOnlyAdvice.class.getName());
    transformer.applyAdvice(constructors(), DemoExitOnlyAdvice.class.getName());
    transformer.applyAdvice(ElementMatchers.named("service"), DemoAdvice.class.getName());
  }

  private static ElementMatcher.Junction<MethodDescription> constructors() {
    return ElementMatchers.isConstructor().and(ElementMatchers.takesArguments(1));
  }

  public int stores() {
    int created = 0;
    created += ContextStore.of(Thread.class, TraceContext.class) == null ? 0 : 1;
    created += ContextStore.of(Thread.class, Integer.class) == null ? 0 : 1;
    created += ContextStore.of(Object.class, long[].class) == null ? 0 : 1;
    created += ContextStore.of(Object.class, WeakReference.class) == null ? 0 : 1;
    return created;
  }
}
