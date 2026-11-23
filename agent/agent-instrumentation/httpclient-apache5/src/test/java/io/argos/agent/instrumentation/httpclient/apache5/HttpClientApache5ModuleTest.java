/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.httpclient.apache5;

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
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** HttpClientApache5Module: identity, helper list, witness, named matchers on the real classes. */
class HttpClientApache5ModuleTest {
  private final HttpClientApache5Module module = new HttpClientApache5Module();

  @Test
  void identity() {
    assertThat(module.id()).isEqualTo("httpclient-apache5");
    assertThat(module.moduleId()).isEqualTo(ModuleIds.HTTPCLIENT_APACHE5);
    assertThat(module.helperClassNames()).containsExactly(Apache5Helper.class.getName());
    assertThat(HelperClasses.problems(module)).isEmpty();
    assertThat(module.libraryVersionRange()).hasSize(1);
  }

  @Test
  @DisplayName("library under test: httpclient5 5.5.x in `test`, 5.6.x in `testHc5Latest`")
  void libraryUnderTest() {
    URL jar = CloseableHttpClient.class.getProtectionDomain().getCodeSource().getLocation();
    System.out.println("[httpclient5-under-test] " + jar);
    assertThat(jar.toString()).containsAnyOf("httpclient5-5.5.", "httpclient5-5.6.");
  }

  @Test
  @DisplayName("witness: a loader that sees HttpClient 5 classic, not one without it")
  void witness() throws Exception {
    assertThat(module.witness().matches(getClass().getClassLoader())).isTrue();
    try (URLClassLoader empty = new URLClassLoader(new URL[0], null)) {
      assertThat(module.witness().matches(empty)).isFalse();
    }
  }

  @Test
  @DisplayName(
      "named matching: doExecute of InternalHttpClient and MinimalHttpClient only, not"
          + " CloseableHttpClient's abstract method or the async client")
  void matchers() throws Exception {
    List<TypeInstrumentation> rules = module.types();
    assertThat(rules).hasSize(1);
    ElementMatcher<TypeDescription> types = rules.get(0).typeMatcher();
    for (String name : HttpClientApache5Module.CLIENT_CLASSES) {
      assertThat(types.matches(TypeDescription.ForLoadedType.of(Class.forName(name))))
          .as(name)
          .isTrue();
    }
    assertThat(types.matches(TypeDescription.ForLoadedType.of(CloseableHttpClient.class)))
        .isFalse();
    assertThat(
            types.matches(
                TypeDescription.ForLoadedType.of(
                    Class.forName(
                        "org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient"))))
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
    assertThat(advice).containsExactly(Apache5ExecuteAdvice.class.getName());
    for (String name : HttpClientApache5Module.CLIENT_CLASSES) {
      Method doExecute =
          Class.forName(name)
              .getDeclaredMethod(
                  "doExecute", HttpHost.class, ClassicHttpRequest.class, HttpContext.class);
      assertThat(methods.get(0).matches(new MethodDescription.ForLoadedMethod(doExecute)))
          .as(name)
          .isTrue();
    }
    Method abstractDoExecute =
        CloseableHttpClient.class.getDeclaredMethod(
            "doExecute", HttpHost.class, ClassicHttpRequest.class, HttpContext.class);
    assertThat(methods.get(0).matches(new MethodDescription.ForLoadedMethod(abstractDoExecute)))
        .isFalse();
  }
}
