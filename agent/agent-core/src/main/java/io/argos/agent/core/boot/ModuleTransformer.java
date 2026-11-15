/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.boot;

import io.argos.agent.instrumentation.api.InstrumentationModule;
import io.argos.agent.instrumentation.api.TypeInstrumentation;
import io.argos.agent.instrumentation.api.TypeTransformer;
import java.security.ProtectionDomain;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.utility.JavaModule;

/**
 * Applies one {@link TypeInstrumentation} of a module (10 §5.2 steps 3-4): injects the module's
 * helpers into the type's loader, then inlines the advice. Any exception fails the transformation
 * of this type only (ByteBuddy keeps the original class, {@link
 * io.argos.agent.core.guard.TransformErrorListener} counts it).
 */
final class ModuleTransformer implements AgentBuilder.Transformer {
  private final InstrumentationModule module;
  private final TypeInstrumentation instrumentation;
  private final AdviceFactory advices;
  private final HelperInjector helpers;

  /** {@code argos.test.faults=transform-error:<module>} (test builds only, T-AG-041). */
  private final boolean injectedFailure;

  ModuleTransformer(
      InstrumentationModule module,
      TypeInstrumentation instrumentation,
      AdviceFactory advices,
      HelperInjector helpers,
      boolean injectedFailure) {
    this.module = module;
    this.instrumentation = instrumentation;
    this.advices = advices;
    this.helpers = helpers;
    this.injectedFailure = injectedFailure;
  }

  @Override
  public DynamicType.Builder<?> transform(
      DynamicType.Builder<?> builder,
      TypeDescription typeDescription,
      ClassLoader classLoader,
      JavaModule javaModule,
      ProtectionDomain protectionDomain) {
    TransformRecorder.markModule(module.moduleId());
    if (injectedFailure) {
      throw TestFaults.transformFailure(module.id());
    }
    helpers.inject(module, classLoader, protectionDomain, javaModule);
    Applier applier = new Applier(builder);
    instrumentation.transform(applier);
    return applier.builder;
  }

  private final class Applier implements TypeTransformer {
    private DynamicType.Builder<?> builder;

    Applier(DynamicType.Builder<?> builder) {
      this.builder = builder;
    }

    @Override
    public void applyAdvice(
        ElementMatcher<? super MethodDescription> methods, String adviceClassName) {
      builder = builder.visit(advices.advice(module.moduleId(), adviceClassName).on(methods));
    }
  }
}
