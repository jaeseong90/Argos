/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.servlet;

import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import io.argos.agent.instrumentation.api.TypeInstrumentation;
import io.argos.agent.instrumentation.api.TypeTransformer;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Response flush instrumentation for the end of streaming responses (10 §6.7, F-A34): container
 * implementation classes matched by name, never by subtype (10 §5.1, ADR-0010 C-127). The classes
 * listed here are the witness: a container without them is simply not matched, and its SSE and
 * {@code StreamingResponseBody} responses end at {@code onComplete}/{@code onTimeout} of the async
 * listener, with {@code transaction.max-duration-ms} as the bound. Methods are matched by exact
 * name, arity and visibility.
 *
 * <p>Tomcat 9 (javax), 10.1 and 11 (jakarta) share the class names:
 *
 * <ul>
 *   <li>{@code org.apache.catalina.connector.ResponseFacade}: {@code flushBuffer()} (flush end) and
 *       {@code getOutputStream()}/{@code getWriter()} (the returned stream is linked to the
 *       facade).
 *   <li>{@code org.apache.catalina.connector.CoyoteOutputStream}: {@code flush()}.
 *   <li>{@code org.apache.catalina.connector.CoyoteWriter}: {@code flush()}.
 * </ul>
 *
 * {@code org.apache.catalina.connector.Response.flushBuffer()} is not instrumented: applications
 * hold the facade, and Tomcat calls the internal one at the end of every request. Jetty and
 * Undertow class names come with WO-50 (compatibility matrix).
 */
final class ContainerResponseInstrumentation implements TypeInstrumentation {
  /** Tomcat's response facade (9, 10.1, 11). */
  static final String TOMCAT_RESPONSE_FACADE = "org.apache.catalina.connector.ResponseFacade";

  /** Tomcat's servlet output stream (9, 10.1, 11). */
  static final String TOMCAT_OUTPUT_STREAM = "org.apache.catalina.connector.CoyoteOutputStream";

  /** Tomcat's servlet writer (9, 10.1, 11). */
  static final String TOMCAT_WRITER = "org.apache.catalina.connector.CoyoteWriter";

  private final ElementMatcher<TypeDescription> types;
  private final boolean facade;

  private ContainerResponseInstrumentation(ElementMatcher<TypeDescription> types, boolean facade) {
    this.types = types;
    this.facade = facade;
  }

  /** The response facades: flush and stream mapping. */
  static ContainerResponseInstrumentation facades() {
    return new ContainerResponseInstrumentation(named(TOMCAT_RESPONSE_FACADE), true);
  }

  /** The output streams and writers: flush. */
  static ContainerResponseInstrumentation streams() {
    return new ContainerResponseInstrumentation(
        namedOneOf(TOMCAT_OUTPUT_STREAM, TOMCAT_WRITER), false);
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return types;
  }

  @Override
  public void transform(TypeTransformer transformer) {
    if (facade) {
      transformer.applyAdvice(
          named("flushBuffer").and(takesArguments(0)).and(isPublic()).and(returns(void.class)),
          ResponseFlushAdvice.class.getName());
      ElementMatcher.Junction<MethodDescription> streams =
          namedOneOf("getOutputStream", "getWriter").and(takesArguments(0)).and(isPublic());
      transformer.applyAdvice(streams, ResponseStreamAdvice.class.getName());
    } else {
      transformer.applyAdvice(
          named("flush").and(takesArguments(0)).and(isPublic()).and(returns(void.class)),
          ResponseFlushAdvice.class.getName());
    }
  }

  @Override
  public String toString() {
    return "servlet-response(" + types + ")";
  }
}
