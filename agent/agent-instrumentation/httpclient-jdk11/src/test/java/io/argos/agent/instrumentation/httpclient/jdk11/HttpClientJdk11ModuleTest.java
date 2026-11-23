/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.httpclient.jdk11;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.instrumentation.api.ModuleIds;
import io.argos.agent.instrumentation.api.TypeInstrumentation;
import io.argos.agent.instrumentation.api.TypeTransformer;
import java.lang.reflect.Method;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.junit.jupiter.api.Test;

/**
 * HttpClientJdk11Module on JDK 11+: identity, the helper, the witness on the platform class loader
 * (where java.net.http lives) and the matched methods of the real JDK classes.
 */
class HttpClientJdk11ModuleTest {
  private final HttpClientJdk11Module module = new HttpClientJdk11Module();

  @Test
  void identityAndHelper() {
    assertThat(module.id()).isEqualTo("httpclient-jdk11");
    assertThat(module.moduleId()).isEqualTo(ModuleIds.HTTPCLIENT_JDK11);
    assertThat(module.helperClassNames())
        .containsExactly("io.argos.agent.instrumentation.httpclient.jdk11.Jdk11RequestHelper");
    assertThat(module.libraryVersionRange()).isNotEmpty();
  }

  @Test
  void witnessMatchesThePlatformLoaderOfJavaNetHttp() {
    ClassLoader platform = HttpClient.class.getClassLoader();
    assertThat(platform).as("java.net.http is not in the bootstrap loader").isNotNull();
    assertThat(module.witness().matches(platform)).isTrue();
    assertThat(module.witness().matches(ClassLoader.getSystemClassLoader())).isTrue();
  }

  @Test
  void matchesThePublicSendMethodsAndResponseAsync() throws Exception {
    Class<?> client = Class.forName(HttpClientJdk11Module.CLIENT_CLASS);
    Class<?> exchange = Class.forName(HttpClientJdk11Module.EXCHANGE_CLASS);
    List<TypeInstrumentation> rules = module.types();
    assertThat(rules).hasSize(2);
    assertThat(rules.get(0).typeMatcher().matches(TypeDescription.ForLoadedType.of(client)))
        .isTrue();
    assertThat(rules.get(1).typeMatcher().matches(TypeDescription.ForLoadedType.of(exchange)))
        .isTrue();
    ElementMatcher<? super MethodDescription> send = only(rules.get(0), HttpClientSendAdvice.class);
    int matched = 0;
    for (Method method : client.getDeclaredMethods()) {
      boolean expected =
          java.lang.reflect.Modifier.isPublic(method.getModifiers())
              && (method.getName().equals("send") || method.getName().equals("sendAsync"));
      assertThat(send.matches(new MethodDescription.ForLoadedMethod(method)))
          .as(method.toString())
          .isEqualTo(expected);
      if (expected) {
        matched++;
      }
    }
    assertThat(matched).as("send, sendAsync(2), sendAsync(3)").isEqualTo(3);
    ElementMatcher<? super MethodDescription> responseAsync =
        only(rules.get(1), MultiExchangeAdvice.class);
    assertThat(
            responseAsync.matches(
                new MethodDescription.ForLoadedMethod(
                    exchange.getDeclaredMethod("responseAsync", Executor.class))))
        .isTrue();
  }

  private static ElementMatcher<? super MethodDescription> only(
      TypeInstrumentation rule, Class<?> advice) {
    final List<ElementMatcher<? super MethodDescription>> methods =
        new ArrayList<ElementMatcher<? super MethodDescription>>();
    rule.transform(
        new TypeTransformer() {
          @Override
          public void applyAdvice(
              ElementMatcher<? super MethodDescription> methodMatcher, String adviceClassName) {
            assertThat(adviceClassName).isEqualTo(advice.getName());
            methods.add(methodMatcher);
          }
        });
    assertThat(methods).hasSize(1);
    return methods.get(0);
  }
}
