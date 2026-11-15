/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.servletasync;

import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import io.argos.agent.instrumentation.api.TypeInstrumentation;
import io.argos.agent.instrumentation.api.TypeTransformer;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * The container types of {@link ServletAsyncModule}, matched by name (10 §5.1, ADR-0010 C-127):
 * Tomcat's {@code Request.startAsync(ServletRequest, ServletResponse)} per servlet namespace (the
 * no-argument {@code startAsync()} calls it, so one advice per call) and {@code
 * AsyncContextImpl.start(Runnable)}.
 */
final class AsyncInstrumentation implements TypeInstrumentation {
  private final ElementMatcher<TypeDescription> types;
  private final ElementMatcher<? super MethodDescription> methods;
  private final String advice;

  private AsyncInstrumentation(
      ElementMatcher<TypeDescription> types,
      ElementMatcher<? super MethodDescription> methods,
      String advice) {
    this.types = types;
    this.methods = methods;
    this.advice = advice;
  }

  /** {@code Request.startAsync(<namespace>.servlet.ServletRequest, ...ServletResponse)}. */
  static AsyncInstrumentation startAsync(String namespace, String advice) {
    String api = namespace + ".servlet.";
    return new AsyncInstrumentation(
        named(ServletAsyncModule.TOMCAT_REQUEST),
        named("startAsync")
            .and(takesArguments(2))
            .and(isPublic())
            .and(takesArgument(0, named(api + "ServletRequest")))
            .and(takesArgument(1, named(api + "ServletResponse"))),
        advice);
  }

  /** {@code AsyncContextImpl.start(Runnable)}. */
  static AsyncInstrumentation asyncContextStart(String advice) {
    return new AsyncInstrumentation(
        named(ServletAsyncModule.TOMCAT_ASYNC_CONTEXT),
        named("start")
            .and(takesArguments(1))
            .and(isPublic())
            .and(takesArgument(0, named("java.lang.Runnable"))),
        advice);
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return types;
  }

  @Override
  public void transform(TypeTransformer transformer) {
    transformer.applyAdvice(methods, advice);
  }

  @Override
  public String toString() {
    return "servlet-async(" + types + ")";
  }
}
