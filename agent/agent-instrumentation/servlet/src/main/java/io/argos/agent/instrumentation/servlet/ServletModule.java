/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.servlet;

import io.argos.agent.instrumentation.api.HelperClasses;
import io.argos.agent.instrumentation.api.InstrumentationModule;
import io.argos.agent.instrumentation.api.ModuleIds;
import io.argos.agent.instrumentation.api.TypeInstrumentation;
import io.argos.agent.instrumentation.api.Witness;
import java.util.Arrays;
import java.util.List;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * The servlet request boundary (10 §6.1, §7.1, F-A01, F-A28, D-04, D-21, D-26): one transaction per
 * request, begun on the first {@code Filter.doFilter} or {@code HttpServlet.service} of the REQUEST
 * dispatch and ended when that outermost call returns, or, for an async request, by its {@code
 * AsyncListener}, the ASYNC dispatch that completes it, the first flush of a streaming response or
 * {@code transaction.max-duration-ms}, whichever comes first. FORWARD, INCLUDE, ERROR and ASYNC
 * dispatches inside the request join the transaction anchored on the request attribute {@value
 * ServletHelperCommon#TX_ATTRIBUTE}; an ERROR dispatch that the container starts after the
 * transaction ended (Tomcat's error page handling, Spring Boot's {@code /error}) is not recorded,
 * never names a service (C-48) and is counted as unanchored.
 *
 * <p>javax.servlet (3.1, 4.0) and jakarta.servlet (5.0-6.1) are matched by name with one advice and
 * one helper per namespace; the namespace-independent logic is in {@link ServletHelperCommon}.
 * Response flushes (streaming end, ADR-0010 C-127) are matched by container class names of Tomcat,
 * Jetty and Undertow ({@link ContainerResponseInstrumentation}). The async listener is registered
 * here, at the exit of the dispatch that left the request in async mode, so that it works on every
 * container; it follows the switch of the servlet-async module ({@code instrumentation.disabled:
 * servlet-async}), whose container hooks ({@code startAsync}, {@code AsyncContext.start}) complete
 * it.
 */
public final class ServletModule implements InstrumentationModule {
  public static final int MODULE_ID = ModuleIds.SERVLET;

  static final String JAVAX = "javax";
  static final String JAKARTA = "jakarta";

  @Override
  public String id() {
    return "servlet";
  }

  @Override
  public int moduleId() {
    return MODULE_ID;
  }

  @Override
  public ElementMatcher<ClassLoader> witness() {
    return Witness.hasClassesNamed(JAVAX + ".servlet.http.HttpServlet")
        .or(Witness.hasClassesNamed(JAKARTA + ".servlet.http.HttpServlet"));
  }

  @Override
  public List<TypeInstrumentation> types() {
    return Arrays.<TypeInstrumentation>asList(
        ServletInstrumentation.httpServlet(JAVAX, JavaxServletAdvice.class.getName()),
        ServletInstrumentation.filter(JAVAX, JavaxFilterAdvice.class.getName()),
        ServletInstrumentation.httpServlet(JAKARTA, JakartaServletAdvice.class.getName()),
        ServletInstrumentation.filter(JAKARTA, JakartaFilterAdvice.class.getName()),
        ContainerResponseInstrumentation.facades(),
        ContainerResponseInstrumentation.streams());
  }

  /**
   * Every helper goes into every matching loader: a helper whose namespace (or Servlet 4 API) is
   * absent is defined but never linked (only code of its own namespace calls it, and {@link
   * ServletMappingHelperJavax} only on a Servlet 4+ container).
   */
  @Override
  public List<String> helperClassNames() {
    return HelperClasses.of(
        ServletModule.class,
        "ServletHelperCommon",
        "ServletMappingHelperJavax",
        "ServletHelperJavax",
        "ServletHelperJakarta",
        "AsyncListenerHelper");
  }

  @Override
  public String[] libraryVersionRange() {
    return new String[] {"javax.servlet 3.1-4.0", "jakarta.servlet 5.0-6.1"};
  }
}
