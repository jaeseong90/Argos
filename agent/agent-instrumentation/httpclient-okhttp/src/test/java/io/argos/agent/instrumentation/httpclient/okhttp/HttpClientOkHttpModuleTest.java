/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.httpclient.okhttp;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.instrumentation.api.HelperClasses;
import io.argos.agent.instrumentation.api.ModuleIds;
import io.argos.agent.instrumentation.api.TypeInstrumentation;
import io.argos.agent.instrumentation.api.TypeTransformer;
import java.lang.reflect.Constructor;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** HttpClientOkHttpModule: identity, helper list, witness, named matchers on the real classes. */
class HttpClientOkHttpModuleTest {
  private final HttpClientOkHttpModule module = new HttpClientOkHttpModule();

  @Test
  void identity() {
    assertThat(module.id()).isEqualTo("httpclient-okhttp");
    assertThat(module.moduleId()).isEqualTo(ModuleIds.HTTPCLIENT_OKHTTP);
    assertThat(module.helperClassNames()).containsExactly(OkHttpInterceptorHelper.class.getName());
    assertThat(HelperClasses.problems(module)).isEmpty();
    assertThat(module.libraryVersionRange()).hasSize(1);
  }

  @Test
  @DisplayName("witness: a loader that sees OkHttp, not one without it")
  void witness() throws Exception {
    assertThat(module.witness().matches(getClass().getClassLoader())).isTrue();
    try (URLClassLoader empty = new URLClassLoader(new URL[0], null)) {
      assertThat(module.witness().matches(empty)).isFalse();
    }
  }

  @Test
  @DisplayName(
      "library under test: OkHttp 3 (okhttp3.RealCall) in `test`, OkHttp 4"
          + " (okhttp3.internal.connection.RealCall) in `testOkHttp4`")
  void libraryUnderTest() {
    URL jar = OkHttpClient.class.getProtectionDomain().getCodeSource().getLocation();
    System.out.println("[okhttp-under-test] " + jar);
    String realCall = realCallClass().getName();
    if (jar.toString().contains("okhttp-3.")) {
      assertThat(realCall).isEqualTo("okhttp3.RealCall");
    } else {
      assertThat(jar.toString()).contains("okhttp-4.");
      assertThat(realCall).isEqualTo("okhttp3.internal.connection.RealCall");
    }
  }

  @Test
  @DisplayName(
      "named matching: OkHttpClient$Builder constructors (exit) and build() (entry), the RealCall"
          + " constructor of this OkHttp version; nothing else")
  void matchers() throws Exception {
    List<TypeInstrumentation> rules = module.types();
    assertThat(rules).hasSize(2);
    assertThat(
            rules
                .get(0)
                .typeMatcher()
                .matches(TypeDescription.ForLoadedType.of(OkHttpClient.Builder.class)))
        .isTrue();
    assertThat(
            rules
                .get(0)
                .typeMatcher()
                .matches(TypeDescription.ForLoadedType.of(OkHttpClient.class)))
        .isFalse();
    Class<?> realCall = realCallClass();
    assertThat(rules.get(1).typeMatcher().matches(TypeDescription.ForLoadedType.of(realCall)))
        .isTrue();

    List<ElementMatcher<? super MethodDescription>> builderMethods = new ArrayList<>();
    List<String> builderAdvice = new ArrayList<>();
    collect(rules.get(0), builderMethods, builderAdvice);
    assertThat(builderAdvice)
        .containsExactly(
            OkHttpBuilderConstructorAdvice.class.getName(),
            OkHttpBuilderBuildAdvice.class.getName());
    for (Constructor<?> constructor : OkHttpClient.Builder.class.getDeclaredConstructors()) {
      assertThat(
              builderMethods
                  .get(0)
                  .matches(new MethodDescription.ForLoadedConstructor(constructor)))
          .isTrue();
    }
    assertThat(
            builderMethods
                .get(1)
                .matches(
                    new MethodDescription.ForLoadedMethod(
                        OkHttpClient.Builder.class.getMethod("build"))))
        .isTrue();
    assertThat(
            builderMethods
                .get(1)
                .matches(
                    new MethodDescription.ForLoadedMethod(
                        OkHttpClient.Builder.class.getMethod("interceptors"))))
        .isFalse();

    List<ElementMatcher<? super MethodDescription>> callMethods = new ArrayList<>();
    List<String> callAdvice = new ArrayList<>();
    collect(rules.get(1), callMethods, callAdvice);
    assertThat(callAdvice).containsExactly(OkHttpRealCallAdvice.class.getName());
    for (Constructor<?> constructor : realCall.getDeclaredConstructors()) {
      assertThat(
              callMethods.get(0).matches(new MethodDescription.ForLoadedConstructor(constructor)))
          .isTrue();
    }
  }

  private static Class<?> realCallClass() {
    for (String name : HttpClientOkHttpModule.REAL_CALL_CLASSES) {
      try {
        return Class.forName(name);
      } catch (ClassNotFoundException e) {
        // the other OkHttp generation
      }
    }
    throw new AssertionError("no RealCall class");
  }

  private static void collect(
      TypeInstrumentation rule,
      List<ElementMatcher<? super MethodDescription>> methods,
      List<String> advice) {
    rule.transform(
        new TypeTransformer() {
          @Override
          public void applyAdvice(
              ElementMatcher<? super MethodDescription> matcher, String adviceClassName) {
            methods.add(matcher);
            advice.add(adviceClassName);
          }
        });
  }
}
