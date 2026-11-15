/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.springwebmvc;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.instrumentation.api.HelperClasses;
import io.argos.agent.instrumentation.api.ModuleIds;
import io.argos.agent.instrumentation.api.TypeInstrumentation;
import io.argos.agent.instrumentation.api.TypeTransformer;
import java.util.ArrayList;
import java.util.List;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** SpringWebMvcModule: identity, helpers, the handle(...) matcher of both namespaces. */
class SpringWebMvcModuleTest {
  private final SpringWebMvcModule module = new SpringWebMvcModule();

  /** Stand-ins with the signature of HandlerAdapter.handle in Framework 5.3 and 6.x. */
  abstract static class JavaxAdapter {
    public abstract Object handle(
        javax.servlet.http.HttpServletRequest request,
        javax.servlet.http.HttpServletResponse response,
        Object handler);

    public abstract boolean supports(Object handler);
  }

  abstract static class JakartaAdapter {
    public abstract Object handle(
        jakarta.servlet.http.HttpServletRequest request,
        jakarta.servlet.http.HttpServletResponse response,
        Object handler);
  }

  @Test
  @DisplayName("identity and helper list")
  void identity() {
    assertThat(module.id()).isEqualTo("spring-webmvc");
    assertThat(module.moduleId()).isEqualTo(ModuleIds.SPRING_WEBMVC);
    assertThat(module.helperClassNames())
        .containsExactly(
            SpringWebMvcHelperCommon.class.getName(),
            SpringWebMvcHelperJavax.class.getName(),
            SpringWebMvcHelperJakarta.class.getName());
    assertThat(HelperClasses.problems(module)).isEmpty();
    assertThat(module.types()).hasSize(1);
  }

  @Test
  @DisplayName(
      "C-123: the five Spring adapters are matched by name (RequestMappingHandlerAdapter through"
          + " AbstractHandlerMethodAdapter, which declares handle); custom adapters are not")
  void adapterNames() {
    TypeInstrumentation instrumentation = module.types().get(0);
    assertThat(SpringWebMvcModule.HANDLER_ADAPTERS)
        .containsExactly(
            "org.springframework.web.servlet.mvc.method.AbstractHandlerMethodAdapter",
            "org.springframework.web.servlet.mvc.HttpRequestHandlerAdapter",
            "org.springframework.web.servlet.mvc.SimpleControllerHandlerAdapter",
            "org.springframework.web.servlet.handler.SimpleServletHandlerAdapter",
            "org.springframework.web.servlet.function.support.HandlerFunctionAdapter");
    for (String adapter : SpringWebMvcModule.HANDLER_ADAPTERS) {
      assertThat(
              instrumentation.typeMatcher().matches(new TypeDescription.Latent(adapter, 0, null)))
          .as(adapter)
          .isTrue();
    }
    for (String other :
        new String[] {
          "org.springframework.web.servlet.DispatcherServlet",
          // Inherits handle() from AbstractHandlerMethodAdapter: the declaring class is
          // transformed.
          "org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter",
          "com.example.web.CustomHandlerAdapter"
        }) {
      assertThat(instrumentation.typeMatcher().matches(new TypeDescription.Latent(other, 0, null)))
          .as(other)
          .isFalse();
    }
  }

  @Test
  @DisplayName("handle(request, response, handler): javax advice for javax, jakarta for jakarta")
  void handleMethods() throws ClassNotFoundException {
    final List<ElementMatcher<? super MethodDescription>> methods =
        new ArrayList<ElementMatcher<? super MethodDescription>>();
    final List<String> advice = new ArrayList<String>();
    module
        .types()
        .get(0)
        .transform(
            new TypeTransformer() {
              @Override
              public void applyAdvice(
                  ElementMatcher<? super MethodDescription> methodMatcher, String adviceClass) {
                methods.add(methodMatcher);
                advice.add(adviceClass);
              }
            });
    assertThat(advice)
        .containsExactly(
            JavaxHandlerAdapterAdvice.class.getName(), JakartaHandlerAdapterAdvice.class.getName());
    for (String name : advice) {
      assertThat(Class.forName(name, false, getClass().getClassLoader()).getSimpleName())
          .endsWith("Advice");
    }
    MethodDescription javaxHandle = handle(JavaxAdapter.class);
    MethodDescription jakartaHandle = handle(JakartaAdapter.class);
    assertThat(methods.get(0).matches(javaxHandle)).isTrue();
    assertThat(methods.get(0).matches(jakartaHandle)).isFalse();
    assertThat(methods.get(1).matches(jakartaHandle)).isTrue();
    assertThat(methods.get(1).matches(javaxHandle)).isFalse();
    for (MethodDescription other :
        TypeDescription.ForLoadedType.of(JavaxAdapter.class).getDeclaredMethods()) {
      if (!other.getName().equals("handle")) {
        assertThat(methods.get(0).matches(other)).isFalse();
      }
    }
  }

  private static MethodDescription handle(Class<?> type) {
    for (MethodDescription method : TypeDescription.ForLoadedType.of(type).getDeclaredMethods()) {
      if (method.getName().equals("handle")) {
        return method;
      }
    }
    throw new AssertionError("no handle method in " + type);
  }
}
