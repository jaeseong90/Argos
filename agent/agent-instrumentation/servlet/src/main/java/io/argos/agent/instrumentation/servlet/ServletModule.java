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
 * dispatch and ended when that outermost call returns (or, after {@code startAsync}, when the
 * outermost call of the last ASYNC dispatch returns). FORWARD, INCLUDE, ERROR and ASYNC dispatches
 * inside the request join the transaction anchored on the request attribute {@value
 * ServletHelperCommon#TX_ATTRIBUTE}; an ERROR dispatch that the container starts after the
 * transaction ended (Tomcat's error page handling) is not recorded and never names a service.
 *
 * <p>javax.servlet (3.1, 4.0) and jakarta.servlet (5.0-6.1) are matched by name with one advice and
 * one helper per namespace; the namespace-independent logic is in {@link ServletHelperCommon}.
 *
 * <p>PoC scope (WO-02): no header whitelist, user id or query string capture (all off by default,
 * 10 §15), no {@code transactions_unanchored_total}, no leaked-context handling (10 §7.1), no
 * {@code HttpServletMapping} fallback name; servlet async completion without an ASYNC dispatch
 * ({@code AsyncContext.complete()}) needs the servlet-async module.
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
    String javax = JavaxServletAdvice.class.getName();
    String jakarta = JakartaServletAdvice.class.getName();
    return Arrays.<TypeInstrumentation>asList(
        ServletInstrumentation.httpServlet(JAVAX, javax),
        ServletInstrumentation.filter(JAVAX, javax),
        ServletInstrumentation.httpServlet(JAKARTA, jakarta),
        ServletInstrumentation.filter(JAKARTA, jakarta));
  }

  /**
   * Both namespace helpers go into every matching loader: a helper whose namespace is absent is
   * defined but never linked (only advice of its own namespace calls it).
   */
  @Override
  public List<String> helperClassNames() {
    return HelperClasses.of(
        ServletModule.class, "ServletHelperCommon", "ServletHelperJavax", "ServletHelperJakarta");
  }

  @Override
  public String[] libraryVersionRange() {
    return new String[] {"javax.servlet 3.1-4.0", "jakarta.servlet 5.0-6.1"};
  }
}
