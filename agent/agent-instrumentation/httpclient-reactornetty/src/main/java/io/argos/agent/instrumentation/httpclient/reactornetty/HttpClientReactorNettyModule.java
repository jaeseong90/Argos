/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.httpclient.reactornetty;

import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import io.argos.agent.instrumentation.api.HelperClasses;
import io.argos.agent.instrumentation.api.InstrumentationModule;
import io.argos.agent.instrumentation.api.ModuleIds;
import io.argos.agent.instrumentation.api.TypeInstrumentation;
import io.argos.agent.instrumentation.api.TypeTransformer;
import java.util.Arrays;
import java.util.List;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Outgoing calls through the Reactor Netty {@code HttpClient}, the transport of Spring's {@code
 * WebClient}, inside a servlet transaction ({@code block()}, {@code toFuture()}, {@code
 * subscribe()}; F-A33, DSN-10-14, 10 §6.5). One HTTP step per {@code HttpClient} request, opened
 * and closed with the transaction passed directly (never bound to an event-loop thread):
 *
 * <ol>
 *   <li>{@code HttpClientConnect$HttpClientHandler.<init>} exit: the handler is created by {@code
 *       MonoHttpConnect.subscribe} on the subscribing thread (the one that calls {@code block()},
 *       {@code toFuture()} or {@code subscribe()}), so the thread's transaction and the start time
 *       are attached to it ({@code ContextStore}, key = the handler). A WebFlux server has no
 *       transaction there: nothing is attached and nothing is recorded (metrics only).
 *   <li>{@code HttpClientHandler.requestWithBody(HttpClientOperations)} exit (event loop): the
 *       propagation headers through {@code HttpClientRequest.requestHeaders().set(..)}, after the
 *       method copied the client's default headers (which replace all headers), and before the
 *       request is written (the returned publisher is subscribed later); the step opens at the
 *       first request. Redirected requests get their headers again, for their own host.
 *   <li>{@code HttpClientConnect$HttpObserver.onStateChange} entry with {@code RESPONSE_RECEIVED}:
 *       the final response's status closes the step, before the response is handed downstream (so
 *       before {@code block()} returns and the transaction can end).
 *   <li>{@code HttpClientHandler.test(Throwable)} exit with {@code false}: the handler is the retry
 *       filter of every error of the connection {@code Mono}; {@code false} means the error reaches
 *       the application now (connection refused, connect and response timeouts, I/O errors), so the
 *       step closes with it, and opens first when no connection was ever made. {@code true}
 *       (redirect, one retry of a reset pooled connection) keeps the step open.
 * </ol>
 *
 * <p>These are internal classes of {@code reactor.netty.http.client}: {@link ReactorNettyWitness}
 * checks every method and field the advice uses and disables the module in a class loader whose
 * Reactor Netty lacks one, with one WARN (10 §18 U-9). No {@code readOnly=false}, no argument or
 * result replacement.
 */
public final class HttpClientReactorNettyModule implements InstrumentationModule {
  public static final int MODULE_ID = ModuleIds.HTTPCLIENT_REACTORNETTY;

  static final String HANDLER_CLASS =
      "reactor.netty.http.client.HttpClientConnect$HttpClientHandler";
  static final String OBSERVER_CLASS = "reactor.netty.http.client.HttpClientConnect$HttpObserver";

  @Override
  public String id() {
    return "httpclient-reactornetty";
  }

  @Override
  public int moduleId() {
    return MODULE_ID;
  }

  @Override
  public ElementMatcher<ClassLoader> witness() {
    return new ReactorNettyWitness();
  }

  @Override
  public List<TypeInstrumentation> types() {
    return Arrays.<TypeInstrumentation>asList(
        new TypeInstrumentation() {
          @Override
          public ElementMatcher<TypeDescription> typeMatcher() {
            return named(HANDLER_CLASS);
          }

          @Override
          public void transform(TypeTransformer transformer) {
            transformer.applyAdvice(isConstructor(), ReactorNettyHandlerAdvice.class.getName());
            transformer.applyAdvice(requestWithBody(), ReactorNettyRequestAdvice.class.getName());
            transformer.applyAdvice(retryFilter(), ReactorNettyRetryAdvice.class.getName());
          }

          @Override
          public String toString() {
            return "httpclient-reactornetty(HttpClientHandler)";
          }
        },
        new TypeInstrumentation() {
          @Override
          public ElementMatcher<TypeDescription> typeMatcher() {
            return named(OBSERVER_CLASS);
          }

          @Override
          public void transform(TypeTransformer transformer) {
            transformer.applyAdvice(onStateChange(), ReactorNettyResponseAdvice.class.getName());
          }

          @Override
          public String toString() {
            return "httpclient-reactornetty(HttpObserver.onStateChange)";
          }
        });
  }

  /** {@code HttpClientHandler.requestWithBody(HttpClientOperations)}. */
  static ElementMatcher.Junction<MethodDescription> requestWithBody() {
    return named("requestWithBody")
        .and(takesArguments(1))
        .and(takesArgument(0, named("reactor.netty.http.client.HttpClientOperations")));
  }

  /** {@code HttpClientHandler.test(Throwable)} (not its bridge {@code test(Object)}). */
  static ElementMatcher.Junction<MethodDescription> retryFilter() {
    return named("test").and(takesArguments(1)).and(takesArgument(0, named("java.lang.Throwable")));
  }

  /** {@code HttpObserver.onStateChange(Connection, ConnectionObserver.State)}. */
  static ElementMatcher.Junction<MethodDescription> onStateChange() {
    return named("onStateChange")
        .and(takesArguments(2))
        .and(takesArgument(0, named("reactor.netty.Connection")))
        .and(takesArgument(1, named("reactor.netty.ConnectionObserver$State")));
  }

  @Override
  public List<String> helperClassNames() {
    return HelperClasses.of(HttpClientReactorNettyModule.class, "ReactorNettyHelper");
  }

  @Override
  public String[] libraryVersionRange() {
    return new String[] {
      "io.projectreactor.netty:reactor-netty-http 1.0-1.3 (tested 1.2.18, 1.3.8; 1.0/1.1 by"
          + " source, guarded by the witness)"
    };
  }
}
