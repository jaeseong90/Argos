/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.httpclient.jdk11;

import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import io.argos.agent.instrumentation.api.HelperClasses;
import io.argos.agent.instrumentation.api.InstrumentationModule;
import io.argos.agent.instrumentation.api.ModuleIds;
import io.argos.agent.instrumentation.api.TypeInstrumentation;
import io.argos.agent.instrumentation.api.TypeTransformer;
import io.argos.agent.instrumentation.api.Witness;
import java.util.Arrays;
import java.util.List;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Outgoing calls through {@code java.net.http.HttpClient} (Java 11+, 10 §6.5, F-A05, F-A06, F-A32).
 * Two JDK classes of the {@code java.net.http} module, which the platform class loader defines (not
 * the bootstrap loader):
 *
 * <ul>
 *   <li>{@code jdk.internal.net.http.HttpClientImpl}: the public {@code send(HttpRequest,
 *       BodyHandler)} and {@code sendAsync(HttpRequest, BodyHandler[, PushPromiseHandler])} ({@link
 *       HttpClientSendAdvice}). The outermost call of a thread replaces the request with {@link
 *       Jdk11RequestHelper}, a delegating {@code HttpRequest} with the propagation headers (the one
 *       {@code @Argument(0, readOnly = false)} of the 1.0 allow-list, 10 §5.3 A-5), and opens the
 *       HTTP step;
 *   <li>{@code jdk.internal.net.http.MultiExchange.responseAsync(Executor)} ({@link
 *       MultiExchangeAdvice}): hands the exchange's internal future to the wrapper, so that a
 *       {@code sendAsync} step ends when the exchange completes, before the future returned to the
 *       application does, without touching that future (U-10, see the README).
 * </ul>
 *
 * <p>Both classes are on the JDK allow-list of the core. The helper is injected into the platform
 * class loader, and the core gives {@code java.net.http} read edges to it and to the bootstrap API
 * (10 §4.4). On JDK 8 the witness fails and the module stays disabled. This class itself refers to
 * no {@code java.net.http} type: it is loaded on every JDK.
 */
public final class HttpClientJdk11Module implements InstrumentationModule {
  public static final int MODULE_ID = ModuleIds.HTTPCLIENT_JDK11;

  /** The client implementation behind {@code HttpClient.newHttpClient()} and its facade. */
  static final String CLIENT_CLASS = "jdk.internal.net.http.HttpClientImpl";

  /** One request/response exchange, including redirects and retries. */
  static final String EXCHANGE_CLASS = "jdk.internal.net.http.MultiExchange";

  static final String REQUEST_TYPE = "java.net.http.HttpRequest";

  @Override
  public String id() {
    return "httpclient-jdk11";
  }

  @Override
  public int moduleId() {
    return MODULE_ID;
  }

  @Override
  public ElementMatcher<ClassLoader> witness() {
    return Witness.hasClassesNamed("java.net.http.HttpClient", CLIENT_CLASS, EXCHANGE_CLASS);
  }

  @Override
  public List<TypeInstrumentation> types() {
    return Arrays.<TypeInstrumentation>asList(
        new TypeInstrumentation() {
          @Override
          public ElementMatcher<TypeDescription> typeMatcher() {
            return named(CLIENT_CLASS);
          }

          @Override
          public void transform(TypeTransformer transformer) {
            transformer.applyAdvice(sendPoints(), HttpClientSendAdvice.class.getName());
          }

          @Override
          public String toString() {
            return "httpclient-jdk11(" + CLIENT_CLASS + ".send/sendAsync)";
          }
        },
        new TypeInstrumentation() {
          @Override
          public ElementMatcher<TypeDescription> typeMatcher() {
            return named(EXCHANGE_CLASS);
          }

          @Override
          public void transform(TypeTransformer transformer) {
            transformer.applyAdvice(exchangePoint(), MultiExchangeAdvice.class.getName());
          }

          @Override
          public String toString() {
            return "httpclient-jdk11(" + EXCHANGE_CLASS + ".responseAsync)";
          }
        });
  }

  /**
   * The public {@code send(HttpRequest, BodyHandler)}, {@code sendAsync(HttpRequest, BodyHandler)}
   * and {@code sendAsync(HttpRequest, BodyHandler, PushPromiseHandler)}; the private {@code
   * sendAsync(.., Executor)} they all call is not matched.
   */
  static ElementMatcher.Junction<MethodDescription> sendPoints() {
    return isPublic()
        .and(
            named("send")
                .and(takesArguments(2))
                .or(named("sendAsync").and(takesArguments(2).or(takesArguments(3)))))
        .and(takesArgument(0, named(REQUEST_TYPE)));
  }

  /** {@code MultiExchange.responseAsync(Executor)}. */
  static ElementMatcher.Junction<MethodDescription> exchangePoint() {
    return named("responseAsync")
        .and(takesArguments(1))
        .and(takesArgument(0, named("java.util.concurrent.Executor")));
  }

  @Override
  public List<String> helperClassNames() {
    return HelperClasses.of(HttpClientJdk11Module.class, "Jdk11RequestHelper");
  }

  @Override
  public String[] libraryVersionRange() {
    return new String[] {"java.net.http.HttpClient (JDK 11-25)"};
  }
}
