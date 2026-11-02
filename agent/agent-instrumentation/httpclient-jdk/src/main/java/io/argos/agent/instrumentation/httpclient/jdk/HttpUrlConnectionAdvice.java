/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.httpclient.jdk;

import io.argos.agent.bootstrap.Argos;
import io.argos.agent.bootstrap.ContextStore;
import io.argos.agent.bootstrap.DictKind;
import io.argos.agent.bootstrap.FailureKind;
import io.argos.agent.bootstrap.StepType;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.bootstrap.Tracer;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import net.bytebuddy.asm.Advice;

/**
 * Inlined into {@code sun.net.www.protocol.http.HttpURLConnection.getInputStream()} (java.base, 10
 * §6.5). Bootstrap API and JDK types only (no helper can reach the bootstrap class loader).
 *
 * <p>Enter, inside a transaction, for the first call on this connection only (a {@link
 * ContextStore} entry marks it: the JDK calls {@code getInputStream()} again from inside the
 * exchange through {@code getResponseCode()}, and applications call it after {@code
 * getResponseCode()}): {@code traceparent} when the request is not connected and carries none, then
 * an HTTP step named after the host with the detail {@code METHOD host[:port]/path} (no query
 * string, no user info, no path parameters such as {@code ;jsessionid}). Exit: the response code
 * ({@code responseCode} field, set by the JDK while it reads the status line, also when it throws
 * for 4xx/5xx), the failure class of 10 §6.5 and {@code recordExtStat}. The application's result
 * and exception are never touched.
 */
public final class HttpUrlConnectionAdvice {
  private HttpUrlConnectionAdvice() {}

  // The advice locals are written here and read by exit(); Error Prone cannot see that link.
  @SuppressWarnings("UnusedVariable")
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static TraceContext enter(
      @Advice.This HttpURLConnection connection,
      @Advice.FieldValue("connected") boolean connected,
      @Advice.Local("argosSeq") int seq,
      @Advice.Local("argosHost") long hostId,
      @Advice.Local("argosStart") long start) {
    if (Argos.skip(HttpClientJdkModule.MODULE_ID)) {
      return null;
    }
    Tracer tracer = Argos.tracer();
    TraceContext context = tracer.current();
    if (context == null) {
      return null;
    }
    ContextStore<HttpURLConnection, TraceContext> calls =
        ContextStore.of(HttpURLConnection.class, TraceContext.class);
    if (calls.get(connection) != null) {
      return null;
    }
    calls.put(connection, context);
    URL url = connection.getURL();
    String host = url.getHost();
    if (!connected
        && tracer.shouldPropagate(host)
        && connection.getRequestProperty("traceparent") == null) {
      connection.setRequestProperty("traceparent", tracer.traceparent(context, tracer.newSpanId()));
    }
    hostId = tracer.dict(DictKind.HOST, host);
    start = System.nanoTime();
    seq = tracer.stepStart(context, StepType.HTTP, hostId, 0L, start);
    int port = url.getPort();
    String path = url.getPath();
    if (path.indexOf(';') >= 0) {
      // Path parameters (;jsessionid=..., matrix variables) are dropped with the rule of the
      // inbound
      // HttpPaths.clean; agent-core is not reachable from java.base, so the loop is inlined.
      StringBuilder clean = new StringBuilder(path.length());
      int i = 0;
      while (i < path.length()) {
        char c = path.charAt(i);
        if (c == ';') {
          int next = path.indexOf('/', i);
          if (next < 0) {
            break;
          }
          i = next;
        } else {
          clean.append(c);
          i++;
        }
      }
      path = clean.toString();
    }
    tracer.stepDetail(
        context,
        seq,
        connection.getRequestMethod()
            + " "
            + host
            + (port < 0 ? "" : ":" + port)
            + (path.isEmpty() ? "/" : path));
    return context;
  }

  @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
  public static void exit(
      @Advice.Enter TraceContext context,
      @Advice.FieldValue("responseCode") int responseCode,
      @Advice.Thrown Throwable error,
      @Advice.Local("argosSeq") int seq,
      @Advice.Local("argosHost") long hostId,
      @Advice.Local("argosStart") long start) {
    if (context == null) {
      return;
    }
    long end = System.nanoTime();
    int failure = FailureKind.NONE;
    if (responseCode >= 500) {
      failure = FailureKind.HTTP_5XX;
    } else if (responseCode <= 0 && error != null) {
      if (error instanceof SocketTimeoutException) {
        failure = FailureKind.TIMEOUT;
      } else if (error instanceof ConnectException
          || error instanceof UnknownHostException
          || error instanceof NoRouteToHostException) {
        failure = FailureKind.CONNECT_FAIL;
      } else {
        failure = FailureKind.OTHER;
      }
    }
    Tracer tracer = Argos.tracer();
    tracer.stepEnd(
        context, seq, responseCode > 0 ? responseCode : 0, failure != FailureKind.NONE, 0L, end);
    tracer.recordExtStat(hostId, end - start, failure);
  }
}
