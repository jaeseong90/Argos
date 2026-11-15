/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.servletasync;

import io.argos.agent.instrumentation.api.HelperClasses;
import io.argos.agent.instrumentation.api.InstrumentationModule;
import io.argos.agent.instrumentation.api.ModuleIds;
import io.argos.agent.instrumentation.api.TypeInstrumentation;
import io.argos.agent.instrumentation.api.Witness;
import java.util.Arrays;
import java.util.List;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Servlet async processing (10 §6.7, F-A07, F-A34): the container hooks of an async request. The
 * container classes are matched by name, never by subtype (10 §5.1, ADR-0010 C-127); this release
 * lists Tomcat 9, 10.1 and 11 (Jetty and Undertow come with WO-50):
 *
 * <ul>
 *   <li>{@code org.apache.catalina.connector.Request.startAsync(ServletRequest, ServletResponse)}
 *       exit ({@link JavaxStartAsyncAdvice}, {@link JakartaStartAsyncAdvice}): the transaction
 *       anchored on the request enters async mode at once ({@code TraceContext.asyncStarted}),
 *       keeps the response status so far, and the container's response maps to it, so that the
 *       first flush of an SSE or {@code StreamingResponseBody} response ends it even before the
 *       request thread has left the filter chain.
 *   <li>{@code org.apache.catalina.core.AsyncContextImpl.start(Runnable)} enter ({@link
 *       AsyncStartAdvice}): the task carries the current transaction in {@code
 *       ContextStore<Runnable, TraceContext>}; the task itself is never wrapped. The executor
 *       module's {@code Runnable.run} advice (WO-15) binds it on the worker thread.
 * </ul>
 *
 * <p>The {@code AsyncListener} that ends an async request ({@code onComplete}, {@code onError},
 * {@code onTimeout}) is registered by the servlet module at the exit of the dispatch that left the
 * request in async mode, which works on every container; it follows this module's switch ({@code
 * instrumentation.disabled: servlet-async} turns it off).
 */
public final class ServletAsyncModule implements InstrumentationModule {
  public static final int MODULE_ID = ModuleIds.SERVLET_ASYNC;

  /** Tomcat's async context implementation (9, 10.1, 11): the witness of this module. */
  static final String TOMCAT_ASYNC_CONTEXT = "org.apache.catalina.core.AsyncContextImpl";

  /** Tomcat's request (9, 10.1, 11). */
  static final String TOMCAT_REQUEST = "org.apache.catalina.connector.Request";

  @Override
  public String id() {
    return "servlet-async";
  }

  @Override
  public int moduleId() {
    return MODULE_ID;
  }

  @Override
  public ElementMatcher<ClassLoader> witness() {
    return Witness.hasClassesNamed(TOMCAT_ASYNC_CONTEXT, TOMCAT_REQUEST);
  }

  @Override
  public List<TypeInstrumentation> types() {
    return Arrays.<TypeInstrumentation>asList(
        AsyncInstrumentation.startAsync("javax", JavaxStartAsyncAdvice.class.getName()),
        AsyncInstrumentation.startAsync("jakarta", JakartaStartAsyncAdvice.class.getName()),
        AsyncInstrumentation.asyncContextStart(AsyncStartAdvice.class.getName()));
  }

  /** A helper whose namespace is absent is defined but never linked. */
  @Override
  public List<String> helperClassNames() {
    return HelperClasses.of(
        ServletAsyncModule.class, "AsyncHelperCommon", "AsyncHelperJavax", "AsyncHelperJakarta");
  }

  @Override
  public String[] libraryVersionRange() {
    return new String[] {
      "javax.servlet 3.1-4.0 on Tomcat 9", "jakarta.servlet 5.0-6.1 on Tomcat 10.1/11"
    };
  }
}
