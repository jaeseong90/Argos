/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.trace;

import io.argos.agent.bootstrap.FailureKind;

/**
 * The single classifier of external-call outcomes (10 §6.5, F-A05; WO-14), used by {@code
 * Tracer.httpEnd} for every HTTP client module. By class name, so that no application or JDK module
 * class is loaded or linked (java.net.http is not readable from every loader):
 *
 * <ul>
 *   <li>{@link FailureKind#TIMEOUT}: {@code java.net.SocketTimeoutException}, {@code
 *       java.net.http.HttpTimeoutException} (with its {@code HttpConnectTimeoutException}), Netty's
 *       {@code io.netty.handler.timeout.TimeoutException} (reactor-netty {@code responseTimeout}:
 *       {@code ReadTimeoutException}), Netty's {@code io.netty.channel.ConnectTimeoutException} (a
 *       {@code ConnectException}, checked before its superclass) and {@code
 *       java.util.concurrent.TimeoutException};
 *   <li>{@link FailureKind#CONNECT_FAIL}: any other {@code java.net.ConnectException} (refused),
 *       {@code java.net.UnknownHostException}, {@code java.net.NoRouteToHostException};
 *   <li>{@link FailureKind#OTHER}: any other error;
 *   <li>{@link FailureKind#HTTP_5XX}: no error and a status of 500 or more.
 * </ul>
 *
 * <p>Classification is by cause (ADR-0010 C-223 (3)): every timeout, connect or response, is
 * TIMEOUT whatever the client; refused or unreachable is CONNECT_FAIL.
 *
 * <p>The cause chain is walked from the thrown error, at most {@value #MAX_CAUSES} links (a cycle
 * stops the walk), and each link's class and superclasses (at most {@value #MAX_SUPERCLASSES}); the
 * first link that matches decides. Never throws.
 */
public final class HttpFailures {
  /** Links of the cause chain read at most. */
  static final int MAX_CAUSES = 8;

  /** Superclasses of one link read at most. */
  static final int MAX_SUPERCLASSES = 16;

  /**
   * The application's Netty timeout classes. Assembled at run time: the agent jar relocates every
   * {@code io.netty} string literal to its shaded copy (C-23), which would never match the class an
   * application's reactor-netty throws.
   */
  private static final String NETTY_TIMEOUT =
      String.join(".", "io", "netty", "handler", "timeout", "TimeoutException");

  /** Netty's connect timeout, a {@code java.net.ConnectException} subclass (C-223 (3)). */
  private static final String NETTY_CONNECT_TIMEOUT =
      String.join(".", "io", "netty", "channel", "ConnectTimeoutException");

  private HttpFailures() {}

  /** The {@link FailureKind} of a call that ended with {@code status} and {@code error}. */
  @SuppressWarnings("ReferenceEquality") // a self-referencing cause ends the chain (identity)
  public static int classify(int status, Throwable error) {
    if (error == null) {
      return status >= 500 ? FailureKind.HTTP_5XX : FailureKind.NONE;
    }
    try {
      Throwable link = error;
      for (int depth = 0; link != null && depth < MAX_CAUSES; depth++) {
        int kind = byClass(link.getClass());
        if (kind != FailureKind.NONE) {
          return kind;
        }
        Throwable cause = link.getCause();
        if (cause == link) {
          break;
        }
        link = cause;
      }
    } catch (Throwable ignored) {
      // A broken getCause() override: still an error.
    }
    return FailureKind.OTHER;
  }

  private static int byClass(Class<?> type) {
    Class<?> c = type;
    for (int i = 0; c != null && i < MAX_SUPERCLASSES; i++) {
      String name = c.getName();
      if ("java.net.SocketTimeoutException".equals(name)
          || "java.net.http.HttpTimeoutException".equals(name)
          || NETTY_TIMEOUT.equals(name)
          || NETTY_CONNECT_TIMEOUT.equals(name)
          || "java.util.concurrent.TimeoutException".equals(name)) {
        return FailureKind.TIMEOUT;
      }
      if ("java.net.ConnectException".equals(name)
          || "java.net.UnknownHostException".equals(name)
          || "java.net.NoRouteToHostException".equals(name)) {
        return FailureKind.CONNECT_FAIL;
      }
      if ("java.lang.Throwable".equals(name)) {
        break;
      }
      c = c.getSuperclass();
    }
    return FailureKind.NONE;
  }
}
