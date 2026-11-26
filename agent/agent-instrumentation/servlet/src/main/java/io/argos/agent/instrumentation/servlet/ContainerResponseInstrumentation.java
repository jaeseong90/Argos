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
 * hold the facade, and Tomcat calls the internal one at the end of every request.
 *
 * <p>Jetty and Undertow (WO-50, compatibility matrix rows with {@code SAMPLE_CONTAINER}): the
 * response object the application gets, its output stream and its writer.
 *
 * <ul>
 *   <li>Jetty 9.4 (javax.servlet 3.1): {@code org.eclipse.jetty.server.Response}, {@code
 *       org.eclipse.jetty.server.HttpOutput}, {@code org.eclipse.jetty.server.ResponseWriter}.
 *   <li>Jetty 12.0 ee10 (jakarta.servlet 6.0) and 12.1 ee11 (6.1): {@code
 *       org.eclipse.jetty.ee10|ee11.servlet.ServletApiResponse}, {@code ...servlet.HttpOutput},
 *       {@code ...servlet.ResponseWriter}.
 *   <li>Undertow 2.2 (javax) and 2.3 (jakarta): {@code
 *       io.undertow.servlet.spec.HttpServletResponseImpl}, {@code ...spec.ServletOutputStreamImpl},
 *       {@code ...spec.ServletPrintWriterDelegate}.
 * </ul>
 */
final class ContainerResponseInstrumentation implements TypeInstrumentation {
  /** Tomcat's response facade (9, 10.1, 11). */
  static final String TOMCAT_RESPONSE_FACADE = "org.apache.catalina.connector.ResponseFacade";

  /** Tomcat's servlet output stream (9, 10.1, 11). */
  static final String TOMCAT_OUTPUT_STREAM = "org.apache.catalina.connector.CoyoteOutputStream";

  /** Tomcat's servlet writer (9, 10.1, 11). */
  static final String TOMCAT_WRITER = "org.apache.catalina.connector.CoyoteWriter";

  /** Jetty 9.4's response (the application's response object itself). */
  static final String JETTY9_RESPONSE = "org.eclipse.jetty.server.Response";

  /** Jetty 9.4's servlet output stream. */
  static final String JETTY9_OUTPUT_STREAM = "org.eclipse.jetty.server.HttpOutput";

  /** Jetty 9.4's servlet writer. */
  static final String JETTY9_WRITER = "org.eclipse.jetty.server.ResponseWriter";

  /** Jetty 12.0's (ee10) response facade. */
  static final String JETTY_EE10_RESPONSE = "org.eclipse.jetty.ee10.servlet.ServletApiResponse";

  /** Jetty 12.0's (ee10) servlet output stream. */
  static final String JETTY_EE10_OUTPUT_STREAM = "org.eclipse.jetty.ee10.servlet.HttpOutput";

  /** Jetty 12.0's (ee10) servlet writer. */
  static final String JETTY_EE10_WRITER = "org.eclipse.jetty.ee10.servlet.ResponseWriter";

  /** Jetty 12.1's (ee11) response facade. */
  static final String JETTY_EE11_RESPONSE = "org.eclipse.jetty.ee11.servlet.ServletApiResponse";

  /** Jetty 12.1's (ee11) servlet output stream. */
  static final String JETTY_EE11_OUTPUT_STREAM = "org.eclipse.jetty.ee11.servlet.HttpOutput";

  /** Jetty 12.1's (ee11) servlet writer. */
  static final String JETTY_EE11_WRITER = "org.eclipse.jetty.ee11.servlet.ResponseWriter";

  /** Undertow's response (2.2 javax, 2.3 jakarta). */
  static final String UNDERTOW_RESPONSE = "io.undertow.servlet.spec.HttpServletResponseImpl";

  /** Undertow's servlet output stream. */
  static final String UNDERTOW_OUTPUT_STREAM = "io.undertow.servlet.spec.ServletOutputStreamImpl";

  /** Undertow's servlet writer (the PrintWriter that getWriter() returns). */
  static final String UNDERTOW_WRITER = "io.undertow.servlet.spec.ServletPrintWriterDelegate";

  /** Response objects of every container: flush and stream mapping. */
  static final String[] RESPONSES = {
    TOMCAT_RESPONSE_FACADE,
    JETTY9_RESPONSE,
    JETTY_EE10_RESPONSE,
    JETTY_EE11_RESPONSE,
    UNDERTOW_RESPONSE
  };

  /** Output streams and writers of every container: flush. */
  static final String[] STREAMS = {
    TOMCAT_OUTPUT_STREAM,
    TOMCAT_WRITER,
    JETTY9_OUTPUT_STREAM,
    JETTY9_WRITER,
    JETTY_EE10_OUTPUT_STREAM,
    JETTY_EE10_WRITER,
    JETTY_EE11_OUTPUT_STREAM,
    JETTY_EE11_WRITER,
    UNDERTOW_OUTPUT_STREAM,
    UNDERTOW_WRITER
  };

  private final ElementMatcher<TypeDescription> types;
  private final boolean facade;

  private ContainerResponseInstrumentation(ElementMatcher<TypeDescription> types, boolean facade) {
    this.types = types;
    this.facade = facade;
  }

  /** The response facades: flush and stream mapping. */
  static ContainerResponseInstrumentation facades() {
    return new ContainerResponseInstrumentation(namedOneOf(RESPONSES), true);
  }

  /** The output streams and writers: flush. */
  static ContainerResponseInstrumentation streams() {
    return new ContainerResponseInstrumentation(namedOneOf(STREAMS), false);
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
