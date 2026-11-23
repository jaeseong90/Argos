/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.httpclient.reactornetty;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.bootstrap.AgentControl;
import io.argos.agent.bootstrap.TestControls;
import io.argos.agent.bootstrap.Tracer;
import io.argos.agent.instrumentation.api.HelperClasses;
import io.argos.agent.instrumentation.api.ModuleIds;
import io.argos.agent.instrumentation.api.TypeInstrumentation;
import io.argos.agent.instrumentation.api.TypeTransformer;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.MethodCall;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.pool.TypePool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.HttpClientConfig;

/**
 * HttpClientReactorNettyModule: identity, helper list, the witness of 10 §18 U-9 (methods and
 * fields of the internals read from the class files) and the named matchers on the real classes,
 * for the Reactor Netty version of the test task (1.2.18 in {@code test}, 1.3.8 in {@code
 * testReactorNetty13}).
 */
class HttpClientReactorNettyModuleTest {
  private final HttpClientReactorNettyModule module = new HttpClientReactorNettyModule();

  @Test
  void identity() {
    assertThat(module.id()).isEqualTo("httpclient-reactornetty");
    assertThat(module.moduleId()).isEqualTo(ModuleIds.HTTPCLIENT_REACTORNETTY);
    assertThat(module.helperClassNames()).containsExactly(ReactorNettyHelper.class.getName());
    assertThat(HelperClasses.problems(module)).isEmpty();
    assertThat(module.libraryVersionRange()).hasSize(1);
  }

  @Test
  @DisplayName(
      "T-AG-055m U-9 witness: this Reactor Netty declares every method and field the advice binds;"
          + " a loader without Reactor Netty does not match")
  void witness() throws Exception {
    URL jar = HttpClient.class.getProtectionDomain().getCodeSource().getLocation();
    System.out.println("[reactor-netty-under-test] " + jar);
    assertThat(jar.toString()).containsAnyOf("reactor-netty-http-1.2.", "reactor-netty-http-1.3.");
    assertThat(ReactorNettyWitness.missingMember(getClass().getClassLoader())).isNull();
    assertThat(module.witness().matches(getClass().getClassLoader())).isTrue();
    try (URLClassLoader empty = new URLClassLoader(new URL[0], null)) {
      assertThat(module.witness().matches(empty)).isFalse();
    }
  }

  @Test
  @DisplayName(
      "T-AG-055m U-9 witness: HttpClient present but HttpClientHandler without requestWithBody:"
          + " no match, reported once through Tracer.moduleError")
  void witnessRejectsChangedInternals() throws Exception {
    ClassLoader real = getClass().getClassLoader();
    byte[] stripped =
        new ByteBuddy()
            .subclass(Object.class)
            .name(HttpClientReactorNettyModule.HANDLER_CLASS)
            .defineConstructor(Visibility.PACKAGE_PRIVATE)
            .withParameters(HttpClientConfig.class)
            .intercept(MethodCall.invoke(Object.class.getConstructor()))
            .make()
            .getBytes();
    String strippedResource =
        HttpClientReactorNettyModule.HANDLER_CLASS.replace('.', '/') + ".class";
    ClassLoader changed =
        new ClassLoader(null) {
          @Override
          public URL getResource(String name) {
            return real.getResource(name);
          }

          @Override
          public InputStream getResourceAsStream(String name) {
            return name.equals(strippedResource)
                ? new ByteArrayInputStream(stripped)
                : real.getResourceAsStream(name);
          }
        };
    assertThat(ReactorNettyWitness.missingMember(changed))
        .isEqualTo("HttpClientHandler.requestWithBody(HttpClientOperations)");

    List<Object[]> reported = Collections.synchronizedList(new ArrayList<Object[]>());
    AgentControl control = TestControls.newControl();
    control.installTracer(
        (Tracer)
            Proxy.newProxyInstance(
                Tracer.class.getClassLoader(),
                new Class<?>[] {Tracer.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("moduleError")) {
                    reported.add(args);
                  }
                  return null;
                }));
    try {
      assertThat(module.witness().matches(changed)).isFalse();
    } finally {
      control.installTracer(null);
    }
    assertThat(reported).hasSize(1);
    assertThat(reported.get(0)[0]).isEqualTo(HttpClientReactorNettyModule.MODULE_ID);
    assertThat(reported.get(0)[1])
        .isInstanceOf(ReactorNettyWitness.UnsupportedVersionException.class);
  }

  @Test
  @DisplayName(
      "named matching: HttpClientHandler constructor, requestWithBody and test(Throwable) (not its"
          + " bridge), HttpObserver.onStateChange")
  void matchers() {
    TypePool pool = TypePool.Default.ofSystemLoader();
    TypeDescription handler = pool.describe(HttpClientReactorNettyModule.HANDLER_CLASS).resolve();
    TypeDescription observer = pool.describe(HttpClientReactorNettyModule.OBSERVER_CLASS).resolve();
    List<TypeInstrumentation> rules = module.types();
    assertThat(rules).hasSize(2);
    assertThat(rules.get(0).typeMatcher().matches(handler)).isTrue();
    assertThat(rules.get(0).typeMatcher().matches(observer)).isFalse();
    assertThat(rules.get(1).typeMatcher().matches(observer)).isTrue();

    List<ElementMatcher<? super MethodDescription>> methods = new ArrayList<>();
    List<String> advice = new ArrayList<>();
    collect(rules.get(0), methods, advice);
    assertThat(advice)
        .containsExactly(
            ReactorNettyHandlerAdvice.class.getName(),
            ReactorNettyRequestAdvice.class.getName(),
            ReactorNettyRetryAdvice.class.getName());
    assertThat(handler.getDeclaredMethods().filter(methods.get(0))).hasSize(1);
    assertThat(handler.getDeclaredMethods().filter(methods.get(1))).hasSize(1);
    assertThat(handler.getDeclaredMethods().filter(methods.get(2)))
        .hasSize(1)
        .allSatisfy(m -> assertThat(m.isBridge()).isFalse());

    methods.clear();
    advice.clear();
    collect(rules.get(1), methods, advice);
    assertThat(advice).containsExactly(ReactorNettyResponseAdvice.class.getName());
    assertThat(observer.getDeclaredMethods().filter(methods.get(0))).hasSize(1);
  }

  @Test
  void urlSplitting() {
    assertThat(ReactorNettyHelper.split("http://127.0.0.1:8080/a/b?q=1"))
        .containsExactly("http", "127.0.0.1", "8080", "/a/b?q=1");
    assertThat(ReactorNettyHelper.split("https://user@Example.test"))
        .containsExactly("https", "Example.test", "", "/");
    assertThat(ReactorNettyHelper.split("http://[::1]:9/x"))
        .containsExactly("http", "::1", "9", "/x");
    assertThat(ReactorNettyHelper.split("http://h?x=1")).containsExactly("http", "h", "", "?x=1");
    assertThat(ReactorNettyHelper.split("/relative")).isNull();
  }

  @Test
  @DisplayName("status text of Netty's HttpResponseStatus.toString(): leading three-digit code")
  void statusText() {
    assertThat(ReactorNettyHelper.leadingCode("200 OK")).isEqualTo(200);
    assertThat(ReactorNettyHelper.leadingCode("503 Service Unavailable")).isEqualTo(503);
    assertThat(ReactorNettyHelper.leadingCode("599")).isEqualTo(599);
    assertThat(ReactorNettyHelper.leadingCode("2000 x")).isZero();
    assertThat(ReactorNettyHelper.leadingCode("OK")).isZero();
    assertThat(ReactorNettyHelper.leadingCode(null)).isZero();
    assertThat(ReactorNettyHelper.status("not a response")).isZero();
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
