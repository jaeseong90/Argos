/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.httpclient.jdk;

import io.argos.agent.bootstrap.Argos;
import io.argos.agent.bootstrap.CallDepth;
import io.argos.agent.bootstrap.ContextStore;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.bootstrap.Tracer;
import java.net.HttpURLConnection;
import java.net.URL;
import net.bytebuddy.asm.Advice;

/**
 * Inlined into {@code connect()}, {@code getOutputStream()}, {@code getInputStream()} and {@code
 * getErrorStream()} of {@code sun.net.www.protocol.http.HttpURLConnection} (java.base, 10 §6.5).
 * Bootstrap API and JDK types only: no helper can reach the bootstrap class loader.
 *
 * <p>The state machine of 10 §6.5, per connection, for the outermost call of a thread only ({@code
 * CallDepth.HTTP_CLIENT}: the JDK calls {@code connect()} from {@code getOutputStream()} and {@code
 * getInputStream()}, and {@code getInputStream()} again through {@code getResponseCode()}):
 *
 * <ol>
 *   <li>not started: the first {@code connect()}, {@code getOutputStream()} or {@code
 *       getInputStream()} inside a transaction sets the propagation headers ({@code
 *       Tracer.outgoingHeaders}, only while {@code connected} is false, with {@code
 *       setRequestProperty}) and opens the HTTP step ({@code httpStart}; HOST text of ADR-0010
 *       C-142). Two {@link ContextStore} entries keep the state: {@code long[] {seq, hostId,
 *       start}} (it stays, so the connection never starts again) and the {@link TraceContext}
 *       (removed when the step ends, so the step ends once).
 *   <li>started: a {@code connect()} or {@code getOutputStream()} that throws ends the step with
 *       the error (connection refused, connect timeout); one that returns keeps it open while the
 *       application writes the body.
 *   <li>response: the exit of {@code getInputStream()} (directly or through {@code
 *       getResponseCode()}), or of {@code getErrorStream()} once a status is known, ends the step
 *       with the {@code responseCode} field (the JDK sets it also when it throws for 4xx/5xx; then
 *       the exception is not an error of the call) or, without a status, with the exception ({@code
 *       httpEnd} classifies, 10 §6.5).
 * </ol>
 *
 * <p>The application's arguments, results and exceptions are never touched; header values are never
 * logged (SEC-26).
 */
public final class HttpUrlConnectionAdvice {
  private HttpUrlConnectionAdvice() {}

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static Object enter(
      @Advice.This HttpURLConnection connection,
      @Advice.Origin("#m") String method,
      @Advice.FieldValue("connected") boolean connected) {
    if (Argos.skip(HttpClientJdkModule.MODULE_ID)) {
      return null;
    }
    // Start before CallDepth.enter: if anything here throws, enter returns null and exit leaves
    // the depth alone, so the counter can never stay raised on this thread.
    if (CallDepth.depth(CallDepth.HTTP_CLIENT) == 0 && !"getErrorStream".equals(method)) {
      ContextStore<HttpURLConnection, long[]> states =
          ContextStore.of(HttpURLConnection.class, long[].class);
      if (states.get(connection) == null) {
        Tracer tracer = Argos.tracer();
        TraceContext context = tracer.current();
        if (context != null) {
          URL url = connection.getURL();
          String host = url.getHost();
          int port = url.getPort();
          String preserved = connection.getRequestProperty("traceparent");
          if (!connected) {
            String[] headers =
                tracer.outgoingHeaders(
                    context, host, preserved, connection.getRequestProperty("tracestate"));
            if (headers != null) {
              if (headers[0] != null) {
                connection.setRequestProperty("traceparent", headers[0]);
                preserved = null;
              }
              if (headers[1] != null) {
                connection.setRequestProperty("tracestate", headers[1]);
              }
            }
          }
          String httpMethod = connection.getRequestMethod();
          if ("GET".equals(httpMethod) && connection.getDoOutput()) {
            // The JDK sends a GET with a request body as POST (getOutputStream).
            httpMethod = "POST";
          }
          long hostId = tracer.hostId(url.getProtocol(), host, port);
          long start = System.nanoTime();
          int seq =
              tracer.httpStart(
                  context,
                  hostId,
                  httpMethod,
                  port < 0 ? host : host + ":" + port,
                  url.getPath(),
                  preserved,
                  start);
          states.put(connection, new long[] {seq, hostId, start});
          ContextStore.of(HttpURLConnection.class, TraceContext.class).put(connection, context);
        }
      }
    }
    CallDepth.enter(CallDepth.HTTP_CLIENT);
    return true;
  }

  @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
  public static void exit(
      @Advice.Enter Object entered,
      @Advice.This HttpURLConnection connection,
      @Advice.Origin("#m") String method,
      @Advice.FieldValue("responseCode") int responseCode,
      @Advice.Thrown Throwable error) {
    if (entered == null) {
      return;
    }
    if (CallDepth.exit(CallDepth.HTTP_CLIENT) != 0) {
      return;
    }
    boolean response =
        "getInputStream".equals(method) || (responseCode > 0 && "getErrorStream".equals(method));
    if (!response && error == null) {
      return;
    }
    TraceContext context =
        ContextStore.of(HttpURLConnection.class, TraceContext.class).remove(connection);
    if (context == null) {
      return;
    }
    long[] state = ContextStore.of(HttpURLConnection.class, long[].class).get(connection);
    if (state == null) {
      return;
    }
    int status = responseCode > 0 ? responseCode : 0;
    Argos.tracer()
        .httpEnd(
            context,
            (int) state[0],
            state[1],
            status,
            status > 0 ? null : error,
            state[2],
            System.nanoTime());
  }
}
