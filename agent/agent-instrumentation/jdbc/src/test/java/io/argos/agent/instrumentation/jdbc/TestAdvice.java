/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.jdbc;

import io.argos.agent.bootstrap.Argos;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.implementation.bytecode.StackManipulation;
import net.bytebuddy.implementation.bytecode.constant.IntegerConstant;
import net.bytebuddy.implementation.bytecode.member.MethodInvocation;

/**
 * Advice for the module tests, built like the core's {@code AdviceFactory}: a suppressed exception
 * calls {@code Argos.adviceError(throwable, moduleId)}, so tests can assert that the advice never
 * failed ({@code ModuleHealth.errors(moduleId)} unchanged).
 */
final class TestAdvice {
  private TestAdvice() {}

  static Advice of(Class<?> adviceClass, int moduleId) {
    try {
      MethodDescription handler =
          new MethodDescription.ForLoadedMethod(
              Argos.class.getMethod("adviceError", Throwable.class, int.class));
      return Advice.to(adviceClass)
          .withExceptionHandler(
              new Advice.ExceptionHandler.Simple(
                  new StackManipulation.Compound(
                      IntegerConstant.forValue(moduleId), MethodInvocation.invoke(handler))));
    } catch (NoSuchMethodException e) {
      throw new IllegalStateException(e);
    }
  }
}
