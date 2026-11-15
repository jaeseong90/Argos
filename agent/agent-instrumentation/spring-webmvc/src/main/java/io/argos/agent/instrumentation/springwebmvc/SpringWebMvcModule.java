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
 * Spring MVC service names (10 §6.2, F-A02, F-A29, F-A36, ADR-0004 C-48): at the enter of {@code
 * HandlerAdapter.handle(request, response, handler)} the handler mapping has stored the best
 * matching pattern in the request attribute {@value
 * SpringWebMvcHelperCommon#BEST_MATCHING_PATTERN}; on the REQUEST dispatch it fixes the service
 * name {@code [<context path> ]<METHOD> <pattern>} ({@code GET /api/orders/{id}}, {@code /shop-a
 * GET /api/orders/{id}}). The actual request method is used even when the mapping has no method
 * restriction. Without a pattern, a {@code HandlerMethod} handler gives the non-fixed name {@code
 * [<context path> ]<Controller>#<method>}, and the exit of the same call fixes the pattern if the
 * request has one by then (10 §6.2 "종료 시 재시도"); the exit advice does nothing else.
 *
 * <p>C-48: other dispatches never name the service: Spring Boot's ERROR dispatch to {@code /error}
 * (a 404 without handler) and the FORWARD of Spring Boot's {@code ErrorPageFilter} in a WAR keep
 * the REQUEST name or, without one, the F-A29 fallback the tracer applies at the end (path
 * normalization, {@code ServiceNameResolver}); a FORWARD keeps the name of the forwarding handler.
 *
 * <p>Matcher (ADR-0010 C-123, WO-02 D-5; 10 §6.2's {@code safeImplements(HandlerAdapter)} is
 * corrected): 10 §5.1 allows subtype matching only for jdbc, executor and servlet filters, so the
 * module matches the classes that declare Spring's five {@code handle} methods by name ({@code
 * RequestMappingHandlerAdapter} inherits its {@code handle} from {@code
 * AbstractHandlerMethodAdapter}, which is the transformed class). A custom {@code HandlerAdapter}
 * of an application is not transformed and gets the fallback name.
 */
public final class SpringWebMvcModule implements InstrumentationModule {
  public static final int MODULE_ID = ModuleIds.SPRING_WEBMVC;

  /**
   * Spring Framework 5.3-7.x classes that declare {@code handle(request, response, handler)}: the
   * base of {@code RequestMappingHandlerAdapter}, {@code HttpRequestHandlerAdapter}, {@code
   * SimpleControllerHandlerAdapter}, {@code SimpleServletHandlerAdapter} and {@code
   * HandlerFunctionAdapter} (C-123).
   */
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

  /**
   * {@code handle(HttpServletRequest, HttpServletResponse, Object)}, one advice (enter and exit)
   * per namespace.
   */
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
