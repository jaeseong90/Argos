/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.servlet;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.instrumentation.api.HelperClasses;
import io.argos.agent.instrumentation.api.ModuleIds;
import io.argos.agent.instrumentation.api.TypeInstrumentation;
import io.argos.agent.instrumentation.api.TypeTransformer;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ServletModule: identity, helper list, matchers of 10 §6.1 against the real servlet APIs. */
class ServletModuleTest {
  private final ServletModule module = new ServletModule();

  /** Records the advice of a type instrumentation for the matched type. */
  static final class Recorder implements TypeTransformer {
    final List<ElementMatcher<? super MethodDescription>> methods =
        new ArrayList<ElementMatcher<? super MethodDescription>>();
    final List<String> advice = new ArrayList<String>();

    @Override
    public void applyAdvice(
        ElementMatcher<? super MethodDescription> methodMatcher, String adviceClassName) {
      methods.add(methodMatcher);
      advice.add(adviceClassName);
    }

    boolean matchesAny(MethodDescription method) {
      for (ElementMatcher<? super MethodDescription> matcher : methods) {
        if (matcher.matches(method)) {
          return true;
        }
      }
      return false;
    }
  }

  /** A concrete javax filter, an abstract one and an interface extending Filter. */
  static class JavaxFilter implements javax.servlet.Filter {
    @Override
    public void init(javax.servlet.FilterConfig config) {}

    @Override
    public void doFilter(
        javax.servlet.ServletRequest request,
        javax.servlet.ServletResponse response,
        javax.servlet.FilterChain chain)
        throws IOException, javax.servlet.ServletException {
      chain.doFilter(request, response);
    }

    @Override
    public void destroy() {}
  }

  /** Inherits doFilter: the declaring class carries the advice, this one is not transformed. */
  static class InheritingJavaxFilter extends JavaxFilter {}

  /** Declares doFilter abstract only (like GenericFilterBean declares none). */
  abstract static class AbstractJakartaFilter implements jakarta.servlet.Filter {
    @Override
    public abstract void doFilter(
        jakarta.servlet.ServletRequest request,
        jakarta.servlet.ServletResponse response,
        jakarta.servlet.FilterChain chain)
        throws IOException, jakarta.servlet.ServletException;
  }

  interface JakartaFilterExtension extends jakarta.servlet.Filter {}

  static class JakartaFilter implements JakartaFilterExtension {
    @Override
    public void doFilter(
        jakarta.servlet.ServletRequest request,
        jakarta.servlet.ServletResponse response,
        jakarta.servlet.FilterChain chain)
        throws IOException, jakarta.servlet.ServletException {
      chain.doFilter(request, response);
    }
  }

  @Test
  @DisplayName("identity, helper list in dependency order, version range")
  void identity() {
    assertThat(module.id()).isEqualTo("servlet");
    assertThat(module.moduleId()).isEqualTo(ModuleIds.SERVLET);
    assertThat(module.helperClassNames())
        .containsExactly(
            ServletHelperCommon.class.getName(),
            ServletHelperJavax.class.getName(),
            ServletHelperJakarta.class.getName());
    assertThat(HelperClasses.problems(module)).isEmpty();
    assertThat(module.libraryVersionRange()).isNotEmpty();
    assertThat(module.witness().matches(ServletModuleTest.class.getClassLoader())).isTrue();
  }

  @Test
  @DisplayName("HttpServlet.service(ServletRequest, ServletResponse) of both namespaces only")
  void httpServletService() {
    for (Class<?> servlet :
        new Class<?>[] {
          javax.servlet.http.HttpServlet.class, jakarta.servlet.http.HttpServlet.class
        }) {
      TypeDescription type = TypeDescription.ForLoadedType.of(servlet);
      Recorder recorder = recorderFor(type);
      assertThat(recorder.advice).as(servlet.getName()).hasSize(1);
      int matched = 0;
      for (MethodDescription method : type.getDeclaredMethods()) {
        if (recorder.matchesAny(method)) {
          matched++;
          assertThat(method.getName()).isEqualTo("service");
          assertThat(method.isPublic()).isTrue();
          assertThat(method.getParameters().get(0).getType().asErasure().getSimpleName())
              .isEqualTo("ServletRequest");
        }
      }
      assertThat(matched).as(servlet.getName()).isEqualTo(1);
      String expected =
          servlet.getName().startsWith("javax")
              ? JavaxServletAdvice.class.getName()
              : JakartaServletAdvice.class.getName();
      assertThat(recorder.advice).containsExactly(expected);
    }
  }

  @Test
  @DisplayName("Filter.doFilter of concrete filters (also through a sub-interface), not interfaces")
  void filters() {
    for (Class<?> filter : new Class<?>[] {JavaxFilter.class, JakartaFilter.class}) {
      TypeDescription type = TypeDescription.ForLoadedType.of(filter);
      Recorder recorder = recorderFor(type);
      assertThat(recorder.advice).as(filter.getName()).hasSize(1);
      int matched = 0;
      for (MethodDescription method : type.getDeclaredMethods()) {
        if (recorder.matchesAny(method)) {
          matched++;
          assertThat(method.getName()).isEqualTo("doFilter");
        }
      }
      assertThat(matched).isEqualTo(1);
    }
    assertThat(matchingTypes(TypeDescription.ForLoadedType.of(javax.servlet.Filter.class)))
        .isEmpty();
    assertThat(matchingTypes(TypeDescription.ForLoadedType.of(JakartaFilterExtension.class)))
        .isEmpty();
    assertThat(matchingTypes(TypeDescription.ForLoadedType.of(String.class))).isEmpty();
    assertThat(matchingTypes(TypeDescription.ForLoadedType.of(InheritingJavaxFilter.class)))
        .isEmpty();
    assertThat(matchingTypes(TypeDescription.ForLoadedType.of(AbstractJakartaFilter.class)))
        .isEmpty();
  }

  @Test
  @DisplayName("advice and helper classes exist in the module (names are resolvable)")
  void classesExist() throws ClassNotFoundException {
    ClassLoader loader = ServletModuleTest.class.getClassLoader();
    for (String helper : module.helperClassNames()) {
      assertThat(Class.forName(helper, false, loader).getName()).isEqualTo(helper);
    }
    for (TypeInstrumentation instrumentation : module.types()) {
      Recorder recorder = new Recorder();
      instrumentation.transform(recorder);
      for (String advice : recorder.advice) {
        assertThat(Class.forName(advice, false, loader).getSimpleName()).endsWith("Advice");
      }
    }
  }

  private List<TypeInstrumentation> matchingTypes(TypeDescription type) {
    List<TypeInstrumentation> matching = new ArrayList<TypeInstrumentation>();
    for (TypeInstrumentation instrumentation : module.types()) {
      if (instrumentation.typeMatcher().matches(type)) {
        matching.add(instrumentation);
      }
    }
    return matching;
  }

  private Recorder recorderFor(TypeDescription type) {
    Recorder recorder = new Recorder();
    for (TypeInstrumentation instrumentation : matchingTypes(type)) {
      instrumentation.transform(recorder);
    }
    return recorder;
  }
}
