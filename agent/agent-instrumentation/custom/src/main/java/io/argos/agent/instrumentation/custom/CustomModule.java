/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.custom;

import io.argos.agent.instrumentation.api.InstrumentationModule;
import io.argos.agent.instrumentation.api.ModuleIds;
import io.argos.agent.instrumentation.api.TypeInstrumentation;
import java.util.Collections;
import java.util.List;

/**
 * User-configured method tracing (10 §6.10, F-A14, SEC-25): the methods that {@code
 * instrumentation.custom-methods} items name get a {@code METHOD} step inside a transaction ({@link
 * CustomMethodAdvice}); an item with {@code as-transaction} starts a {@code BATCH} transaction
 * named {@code BATCH <Class#method>} when the thread has none ({@link CustomTransactionAdvice}).
 *
 * <p>The matchers ({@link CustomMatchers}) read the agent core's {@code CustomMethodPolicy} at
 * every transformation, so a remote change applied by the core's {@code Retransformer} adds the
 * advice to new items' methods and removes it from removed items' methods. The policy fixes what
 * may match: never a class under a forbidden prefix ({@code java.}, {@code javax.}, {@code
 * jakarta.}, {@code jdk.}, {@code sun.}, {@code com.sun.}, {@code io.argos.}, ByteBuddy), never a
 * generated class (lambdas, CGLIB and JDK proxies), at most {@code
 * instrumentation.custom-methods.max-classes-per-pattern} (500) classes per item, counted at class
 * load and at retransformation. The matcher costs one volatile read per class when there is no
 * item.
 *
 * <p>The advice uses the bootstrap API only and has no helper (10 §5.4): the step name is the
 * {@code @Advice.Origin} constant {@code Class#method}, whose dictionary id the tracer's dictionary
 * caches (bounded, 10 §9).
 */
public final class CustomModule implements InstrumentationModule {
  public static final int MODULE_ID = ModuleIds.CUSTOM;

  /**
   * The {@code argosSeq} local of {@link CustomTransactionAdvice} when its enter began the
   * transaction (a step sequence number is never negative when the enter value is set).
   */
  static final int BEGAN = -2;

  @Override
  public String id() {
    return "custom";
  }

  @Override
  public int moduleId() {
    return MODULE_ID;
  }

  @Override
  public List<TypeInstrumentation> types() {
    return Collections.<TypeInstrumentation>singletonList(new CustomMethodInstrumentation());
  }

  @Override
  public String[] libraryVersionRange() {
    return new String[] {"application classes named by instrumentation.custom-methods"};
  }
}
