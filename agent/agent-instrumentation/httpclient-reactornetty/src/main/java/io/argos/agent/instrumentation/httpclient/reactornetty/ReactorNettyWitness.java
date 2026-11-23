/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.httpclient.reactornetty;

import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import io.argos.agent.bootstrap.Argos;
import io.argos.agent.instrumentation.api.Witness;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.pool.TypePool;

/**
 * The witness of {@link HttpClientReactorNettyModule} (10 §5.1, §6.5 "witness로 메서드 존재 확인", 10 §18
 * U-9). The advice depends on package-private internals of {@code reactor.netty.http.client}, so
 * besides the classes it checks, by reading the class files (no class is loaded), every method and
 * field the advice binds:
 *
 * <ul>
 *   <li>{@code HttpClientConnect$HttpClientHandler}: constructor {@code (HttpClientConfig)}, {@code
 *       requestWithBody(HttpClientOperations)}, {@code boolean test(Throwable)}, fields {@code
 *       toURI} and {@code method};
 *   <li>{@code HttpClientConnect$HttpObserver}: {@code onStateChange(Connection,
 *       ConnectionObserver$State)}, field {@code handler};
 *   <li>the public types the helper uses: {@code HttpClientRequest}, {@code HttpClientResponse},
 *       {@code HttpClientState}.
 * </ul>
 *
 * <p>A loader without Reactor Netty's {@code HttpClient} does not match, silently. A loader with
 * {@code HttpClient} whose internals differ does not match either, and the first such loader is
 * reported once through {@code Tracer.moduleError} (one WARN naming {@link
 * UnsupportedVersionException}, the missing member only at DEBUG); the module then records nothing
 * in that loader and the application is not transformed by it.
 */
final class ReactorNettyWitness extends ElementMatcher.Junction.AbstractBase<ClassLoader> {
  static final String CLIENT_CLASS = "reactor.netty.http.client.HttpClient";
  static final String CONFIG_CLASS = "reactor.netty.http.client.HttpClientConfig";
  static final String OPERATIONS_CLASS = "reactor.netty.http.client.HttpClientOperations";

  private final ElementMatcher<ClassLoader> client = Witness.hasClassesNamed(CLIENT_CLASS);
  private final ElementMatcher<ClassLoader> classes =
      Witness.hasClassesNamed(
          HttpClientReactorNettyModule.HANDLER_CLASS,
          HttpClientReactorNettyModule.OBSERVER_CLASS,
          CONFIG_CLASS,
          OPERATIONS_CLASS,
          "reactor.netty.http.client.HttpClientRequest",
          "reactor.netty.http.client.HttpClientResponse",
          "reactor.netty.http.client.HttpClientState",
          "reactor.netty.Connection",
          "reactor.netty.ConnectionObserver$State");

  @Override
  public boolean matches(ClassLoader loader) {
    if (loader == null || !client.matches(loader)) {
      return false;
    }
    String missing = classes.matches(loader) ? missingMember(loader) : "internal classes";
    if (missing == null) {
      return true;
    }
    try {
      Argos.tracer()
          .moduleError(
              HttpClientReactorNettyModule.MODULE_ID, new UnsupportedVersionException(missing));
    } catch (Throwable ignored) {
      // Reporting never fails the witness.
    }
    return false;
  }

  /** The first method or field the advice needs that the loader's Reactor Netty lacks, or null. */
  static String missingMember(ClassLoader loader) {
    TypePool pool = TypePool.Default.of(ClassFileLocator.ForClassLoader.of(loader));
    TypeDescription handler = pool.describe(HttpClientReactorNettyModule.HANDLER_CLASS).resolve();
    if (!declares(
        handler,
        isConstructor().and(takesArguments(1)).and(takesArgument(0, named(CONFIG_CLASS))))) {
      return "HttpClientHandler(HttpClientConfig)";
    }
    if (!declares(handler, HttpClientReactorNettyModule.requestWithBody())) {
      return "HttpClientHandler.requestWithBody(HttpClientOperations)";
    }
    if (!declares(
        handler, HttpClientReactorNettyModule.retryFilter().and(returns(named("boolean"))))) {
      return "HttpClientHandler.test(Throwable)";
    }
    if (handler.getDeclaredFields().filter(named("toURI")).isEmpty()) {
      return "HttpClientHandler.toURI";
    }
    if (handler.getDeclaredFields().filter(named("method")).isEmpty()) {
      return "HttpClientHandler.method";
    }
    TypeDescription observer = pool.describe(HttpClientReactorNettyModule.OBSERVER_CLASS).resolve();
    if (!declares(observer, HttpClientReactorNettyModule.onStateChange())) {
      return "HttpObserver.onStateChange(Connection, State)";
    }
    if (observer.getDeclaredFields().filter(named("handler")).isEmpty()) {
      return "HttpObserver.handler";
    }
    return null;
  }

  private static boolean declares(
      TypeDescription type, ElementMatcher<? super MethodDescription> method) {
    return !type.getDeclaredMethods().filter(method).isEmpty();
  }

  @Override
  public String toString() {
    return "reactorNettyWitness(" + CLIENT_CLASS + " with HttpClientConnect internals)";
  }

  /** The reported reason: this Reactor Netty's internals differ from 1.0-1.3 (10 §18 U-9). */
  static final class UnsupportedVersionException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    UnsupportedVersionException(String missing) {
      super("unsupported reactor-netty-http internals, missing " + missing);
    }
  }
}
