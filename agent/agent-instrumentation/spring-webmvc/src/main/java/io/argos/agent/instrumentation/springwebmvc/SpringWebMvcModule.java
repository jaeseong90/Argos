/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.springwebmvc;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import io.argos.agent.instrumentation.api.HelperClasses;
import io.argos.agent.instrumentation.api.InstrumentationModule;
import io.argos.agent.instrumentation.api.ModuleIds;
import io.argos.agent.instrumentation.api.TypeInstrumentation;
import io.argos.agent.instrumentation.api.TypeTransformer;
import io.argos.agent.instrumentation.api.Witness;
import java.util.Collections;
import java.util.List;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Spring MVC service names (10 §6.2, F-A02, F-A36, ADR-0004 C-48): at the enter of {@code
 * HandlerAdapter.handle(request, response, handler)} the handler mapping has stored the best
 * matching pattern in the request attribute {@value
 * SpringWebMvcHelperCommon#BEST_MATCHING_PATTERN}; on the REQUEST dispatch it fixes the service
 * name {@code [<context path> ]<METHOD> <pattern>} ({@code GET /api/orders/{id}}). The actual
 * request method is used even when the mapping has no method restriction.
 *
 * <p>C-48: other dispatches never name the service: Spring Boot's ERROR dispatch to {@code /error}
 * (a 404 without handler) keeps the REQUEST name or, without one, the F-A29 fallback the tracer
 * applies at the end (path normalization); a FORWARD keeps the name of the forwarding handler.
 *
 * <p>Matcher (deviation from 10 §6.2, which says {@code safeImplements(HandlerAdapter)}): 10 §5.1
 * allows subtype matching only for jdbc, executor and servlet filters, so the module matches
 * Spring's own adapters by name. A custom {@code HandlerAdapter} of an application gets the
 * fallback name.
 */
public final class SpringWebMvcModule implements InstrumentationModule {
  public static final int MODULE_ID = ModuleIds.SPRING_WEBMVC;

  /** Spring Framework 5.3-7.x adapters that declare {@code handle(request, response, handler)}. */
  static final String[] HANDLER_ADAPTERS = {
    "org.springframework.web.servlet.mvc.method.AbstractHandlerMethodAdapter",
    "org.springframework.web.servlet.mvc.HttpRequestHandlerAdapter",
    "org.springframework.web.servlet.mvc.SimpleControllerHandlerAdapter",
    "org.springframework.web.servlet.handler.SimpleServletHandlerAdapter",
    "org.springframework.web.servlet.function.support.HandlerFunctionAdapter"
  };

  @Override
  public String id() {
    return "spring-webmvc";
  }

  @Override
  public int moduleId() {
    return MODULE_ID;
  }

  @Override
  public ElementMatcher<ClassLoader> witness() {
    return Witness.hasClassesNamed("org.springframework.web.servlet.HandlerAdapter");
  }

  @Override
  public List<TypeInstrumentation> types() {
    return Collections.<TypeInstrumentation>singletonList(new HandlerAdapterInstrumentation());
  }

  @Override
  public List<String> helperClassNames() {
    return HelperClasses.of(
        SpringWebMvcModule.class,
        "SpringWebMvcHelperCommon",
        "SpringWebMvcHelperJavax",
        "SpringWebMvcHelperJakarta");
  }

  @Override
  public String[] libraryVersionRange() {
    return new String[] {"Spring Framework 5.3 (javax), 6.x and 7.x (jakarta)"};
  }

  /** {@code handle(HttpServletRequest, HttpServletResponse, Object)}, one advice per namespace. */
  static final class HandlerAdapterInstrumentation implements TypeInstrumentation {
    @Override
    public ElementMatcher<TypeDescription> typeMatcher() {
      return namedOneOf(HANDLER_ADAPTERS);
    }

    @Override
    public void transform(TypeTransformer transformer) {
      transformer.applyAdvice(handle("javax"), JavaxHandlerAdapterAdvice.class.getName());
      transformer.applyAdvice(handle("jakarta"), JakartaHandlerAdapterAdvice.class.getName());
    }

    static ElementMatcher<MethodDescription> handle(String namespace) {
      return named("handle")
          .and(takesArguments(3))
          .and(takesArgument(0, named(namespace + ".servlet.http.HttpServletRequest")))
          .and(takesArgument(1, named(namespace + ".servlet.http.HttpServletResponse")));
    }
  }
}
