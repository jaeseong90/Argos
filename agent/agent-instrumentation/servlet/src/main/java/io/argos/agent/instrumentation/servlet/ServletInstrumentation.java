/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.servlet;

import static net.bytebuddy.matcher.ElementMatchers.declaresMethod;
import static net.bytebuddy.matcher.ElementMatchers.isAbstract;
import static net.bytebuddy.matcher.ElementMatchers.isInterface;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import io.argos.agent.instrumentation.api.SafeMatchers;
import io.argos.agent.instrumentation.api.TypeInstrumentation;
import io.argos.agent.instrumentation.api.TypeTransformer;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * The two entry points of 10 §6.1 per servlet namespace: {@code HttpServlet.service(ServletRequest,
 * ServletResponse)} (matched by name) and {@code doFilter(ServletRequest, ServletResponse,
 * FilterChain)} of every class that declares a concrete {@code doFilter} and implements {@code
 * Filter} (subtype matching through {@link SafeMatchers}, allowed for servlet filters by 10 §5.1).
 */
final class ServletInstrumentation implements TypeInstrumentation {
  private final ElementMatcher<TypeDescription> types;
  private final ElementMatcher<? super MethodDescription> methods;
  private final String advice;

  private ServletInstrumentation(
      ElementMatcher<TypeDescription> types,
      ElementMatcher<? super MethodDescription> methods,
      String advice) {
    this.types = types;
    this.methods = methods;
    this.advice = advice;
  }

  /** {@code <namespace>.servlet.http.HttpServlet.service(ServletRequest, ServletResponse)}. */
  static ServletInstrumentation httpServlet(String namespace, String advice) {
    String api = namespace + ".servlet.";
    return new ServletInstrumentation(
        named(api + "http.HttpServlet"),
        named("service")
            .and(takesArguments(2))
            .and(isPublic())
            .and(takesArgument(0, named(api + "ServletRequest")))
            .and(takesArgument(1, named(api + "ServletResponse"))),
        advice);
  }

  /** {@code doFilter(ServletRequest, ServletResponse, FilterChain)} of concrete filters. */
  static ServletInstrumentation filter(String namespace, String advice) {
    String api = namespace + ".servlet.";
    return new ServletInstrumentation(
        // Cheap checks first: most classes declare no doFilter, so the hierarchy walk of
        // safeImplements runs for few types; filters that inherit doFilter (subclasses of Spring's
        // OncePerRequestFilter) are not transformed, the declaring class carries the advice.
        declaresMethod(doFilter(api)).and(not(isInterface())).and(safeImplements(api + "Filter")),
        doFilter(api),
        advice);
  }

  private static ElementMatcher.Junction<MethodDescription> doFilter(String api) {
    return named("doFilter")
        .and(takesArguments(3))
        .and(not(isAbstract()))
        .and(takesArgument(0, named(api + "ServletRequest")))
        .and(takesArgument(1, named(api + "ServletResponse")))
        .and(takesArgument(2, named(api + "FilterChain")));
  }

  private static ElementMatcher.Junction<TypeDescription> safeImplements(String interfaceName) {
    return SafeMatchers.safeImplements(named(interfaceName));
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
    return "servlet(" + types + ")";
  }
}
