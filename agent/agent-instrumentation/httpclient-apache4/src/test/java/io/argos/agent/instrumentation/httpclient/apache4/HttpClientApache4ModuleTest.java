/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.httpclient.apache4;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.instrumentation.api.HelperClasses;
import io.argos.agent.instrumentation.api.ModuleIds;
import io.argos.agent.instrumentation.api.TypeInstrumentation;
import io.argos.agent.instrumentation.api.TypeTransformer;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.protocol.HttpContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** HttpClientApache4Module: identity, helper list, witness, named matchers on the real classes. */
class HttpClientApache4ModuleTest {
  private final HttpClientApache4Module module = new HttpClientApache4Module();

  @Test
  void identity() {
    assertThat(module.id()).isEqualTo("httpclient-apache4");
    assertThat(module.moduleId()).isEqualTo(ModuleIds.HTTPCLIENT_APACHE4);
    assertThat(module.helperClassNames()).containsExactly(Apache4Helper.class.getName());
    assertThat(HelperClasses.problems(module)).isEmpty();
    assertThat(module.libraryVersionRange()).hasSize(1);
  }

  @Test
  @DisplayName("witness: a loader that sees HttpClient 4, not one without it")
  void witness() throws Exception {
    assertThat(module.witness().matches(getClass().getClassLoader())).isTrue();
    try (URLClassLoader empty = new URLClassLoader(new URL[0], null)) {
      assertThat(module.witness().matches(empty)).isFalse();
    }
  }

  @Test
  @DisplayName(
      "named matching: doExecute of InternalHttpClient, MinimalHttpClient and AbstractHttpClient"
          + " only, not CloseableHttpClient's abstract method or its execute overloads")
  void matchers() throws Exception {
    List<TypeInstrumentation> rules = module.types();
    assertThat(rules).hasSize(1);
    ElementMatcher<TypeDescription> types = rules.get(0).typeMatcher();
    for (String name : HttpClientApache4Module.CLIENT_CLASSES) {
      assertThat(types.matches(TypeDescription.ForLoadedType.of(Class.forName(name))))
          .as(name)
          .isTrue();
    }
    assertThat(types.matches(TypeDescription.ForLoadedType.of(CloseableHttpClient.class)))
        .isFalse();
    assertThat(
            types.matches(
                TypeDescription.ForLoadedType.of(
                    Class.forName("org.apache.http.impl.client.DefaultHttpClient"))))
        .as("subclasses inherit AbstractHttpClient.doExecute; their own names are not matched")
        .isFalse();

    List<ElementMatcher<? super MethodDescription>> methods = new ArrayList<>();
    List<String> advice = new ArrayList<>();
    rules
        .get(0)
        .transform(
            new TypeTransformer() {
              @Override
              public void applyAdvice(
                  ElementMatcher<? super MethodDescription> matcher, String adviceClassName) {
                methods.add(matcher);
                advice.add(adviceClassName);
              }
            });
    assertThat(advice).containsExactly(Apache4ExecuteAdvice.class.getName());
    Class<?> internal = Class.forName(HttpClientApache4Module.CLIENT_CLASSES[0]);
    Method doExecute =
        internal.getDeclaredMethod(
            "doExecute", HttpHost.class, HttpRequest.class, HttpContext.class);
    assertThat(methods.get(0).matches(new MethodDescription.ForLoadedMethod(doExecute))).isTrue();
    Method abstractDoExecute =
        CloseableHttpClient.class.getDeclaredMethod(
            "doExecute", HttpHost.class, HttpRequest.class, HttpContext.class);
    assertThat(methods.get(0).matches(new MethodDescription.ForLoadedMethod(abstractDoExecute)))
        .isFalse();
    Method execute =
        CloseableHttpClient.class.getMethod("execute", HttpUriRequest.class, HttpContext.class);
    assertThat(methods.get(0).matches(new MethodDescription.ForLoadedMethod(execute))).isFalse();
  }

  @Test
  void requestLinePaths() {
    assertThat(Apache4Helper.pathOf("http://h:1/a/b?q=1")).isEqualTo("/a/b?q=1");
    assertThat(Apache4Helper.pathOf("https://h")).isEqualTo("/");
    assertThat(Apache4Helper.pathOf("/a?x")).isEqualTo("/a?x");
    assertThat(Apache4Helper.pathOf(null)).isNull();
  }
}
