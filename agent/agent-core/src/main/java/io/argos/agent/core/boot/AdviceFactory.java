/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.boot;

import io.argos.agent.bootstrap.Argos;
import java.util.concurrent.ConcurrentHashMap;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.implementation.bytecode.StackManipulation;
import net.bytebuddy.implementation.bytecode.constant.IntegerConstant;
import net.bytebuddy.implementation.bytecode.member.MethodInvocation;
import net.bytebuddy.pool.TypePool;

/**
 * Builds the inlined {@link Advice} of an advice class from its class file in the agent jar
 * (DSN-02-01: inline only, the advice class is never loaded) and caches it per (module, class).
 *
 * <p>Every advice gets an exception handler: a suppressed exception ({@code suppress =
 * Throwable.class}, A-1) calls {@code Argos.adviceError(throwable, moduleId)}, which counts it
 * against the module (10 §5.2: disabled at {@code instrumentation.error-threshold}).
 */
final class AdviceFactory {
  private static final MethodDescription ADVICE_ERROR = adviceErrorMethod();

  private final ClassFileLocator locator;
  private final TypePool pool;
  private final ConcurrentHashMap<String, Advice> cache = new ConcurrentHashMap<String, Advice>();

  AdviceFactory(ClassLoader agentLoader) {
    this.locator = ClassFileLocator.ForClassLoader.of(agentLoader);
    this.pool = TypePool.Default.of(locator);
  }

  private static MethodDescription adviceErrorMethod() {
    try {
      return new MethodDescription.ForLoadedMethod(
          Argos.class.getMethod("adviceError", Throwable.class, int.class));
    } catch (NoSuchMethodException e) {
      throw new IllegalStateException("Argos.adviceError(Throwable, int) is missing", e);
    }
  }

  Advice advice(int moduleId, String adviceClassName) {
    String key = moduleId + ":" + adviceClassName;
    Advice advice = cache.get(key);
    if (advice == null) {
      TypeDescription type = pool.describe(adviceClassName).resolve();
      advice =
          Advice.to(type, locator)
              .withExceptionHandler(
                  new Advice.ExceptionHandler.Simple(
                      new StackManipulation.Compound(
                          IntegerConstant.forValue(moduleId),
                          MethodInvocation.invoke(ADVICE_ERROR))));
      Advice raced = cache.putIfAbsent(key, advice);
      if (raced != null) {
        advice = raced;
      }
    }
    return advice;
  }
}
