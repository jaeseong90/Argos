/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.httpclient.okhttp;

import io.argos.agent.bootstrap.Argos;
import io.argos.agent.bootstrap.CallDepth;
import io.argos.agent.bootstrap.ContextStore;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.bootstrap.Tracer;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.ListIterator;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * The agent's OkHttp application interceptor (10 §6.5), injected into the class loader of the
 * library (10 §5.4) and put at the front of every client's interceptor list by {@link #install}. It
 * records one HTTP step per call in the transaction attached to the call ({@link
 * OkHttpRealCallAdvice}; the current thread's transaction when none is attached, e.g. a call
 * created before the agent transformed {@code RealCall}), adds the propagation headers through
 * {@code Request.newBuilder().header(..)} and classifies the outcome through the bootstrap {@link
 * Tracer}. The response and the exception of {@code chain.proceed} reach OkHttp unchanged; an agent
 * failure is counted ({@code Tracer.moduleError}) and the call proceeds as if the agent were
 * absent. No fields, no static state.
 */
public final class OkHttpInterceptorHelper implements Interceptor {
  static final String TRACEPARENT = "traceparent";
  static final String TRACESTATE = "tracestate";

  /**
   * Leaves exactly one agent interceptor in {@code interceptors}: adds one at index 0 when there is
   * none, keeps the first and removes later copies otherwise. Copies arise in OkHttp 4, whose
   * {@code Builder(OkHttpClient)} constructor (behind {@code client.newBuilder()}) first runs
   * {@code Builder()}, whose exit adds the interceptor, and then appends the client's interceptors,
   * which hold one already. Only the agent's own interceptors are ever removed.
   */
  public static void install(Object builder) {
    // Object in the signature: the advice that calls this is described from the agent jar, where no
    // OkHttp class exists; this helper lives in the application's loader and casts.
    List<Interceptor> interceptors =
        builder == null ? null : ((OkHttpClient.Builder) builder).interceptors();
    if (interceptors == null) {
      return;
    }
    boolean found = false;
    for (ListIterator<Interceptor> it = interceptors.listIterator(); it.hasNext(); ) {
      if (it.next() instanceof OkHttpInterceptorHelper) {
        if (found) {
          it.remove();
        }
        found = true;
      }
    }
    if (!found) {
      interceptors.add(0, new OkHttpInterceptorHelper());
    }
  }

  /** {@link OkHttpRealCallAdvice}: the new {@code call} belongs to {@code context}. */
  public static void attach(Object call, TraceContext context) {
    if (call != null && context != null) {
      ContextStore.of(Call.class, TraceContext.class).put((Call) call, context);
    }
  }

  @Override
  public Response intercept(Interceptor.Chain chain) throws IOException {
    Request request = chain.request();
    if (Argos.skip(HttpClientOkHttpModule.MODULE_ID)) {
      return chain.proceed(request);
    }
    boolean outermost = CallDepth.enter(CallDepth.HTTP_CLIENT) == 1;
    try {
      TraceContext context = null;
      long[] call = null;
      Request sent = request;
      if (outermost) {
        try {
          context = context(chain);
          if (context != null) {
            call = new long[3];
            sent = start(context, request, call);
          }
        } catch (Throwable t) {
          Argos.tracer().moduleError(HttpClientOkHttpModule.MODULE_ID, t);
          call = null;
          sent = request;
        }
      }
      if (call == null) {
        return chain.proceed(sent);
      }
      Response response;
      try {
        response = chain.proceed(sent);
      } catch (Throwable error) {
        long endNanos = System.nanoTime();
        end(context, call, 0, classifiable(chain, error, call[2], endNanos), endNanos);
        throw error;
      }
      end(context, call, response.code(), null, System.nanoTime());
      return response;
    } finally {
      CallDepth.exit(CallDepth.HTTP_CLIENT);
    }
  }

  /**
   * The transaction attached to the chain's call when it was created (removed from the store: a
   * call runs once), else the current thread's.
   */
  static TraceContext context(Interceptor.Chain chain) {
    Call call = null;
    try {
      call = chain.call();
    } catch (Throwable t) {
      // OkHttp before 3.10 has no Chain.call(): only the current thread's transaction is known.
    }
    TraceContext context =
        call == null ? null : ContextStore.of(Call.class, TraceContext.class).remove(call);
    return context != null ? context : Argos.tracer().current();
  }

  /**
   * Opens the HTTP step ({@code call} receives {@code {seq, hostId, startNanos}}) and returns the
   * request to send: {@code request} itself or a copy with the propagation headers.
   */
  static Request start(TraceContext context, Request request, long[] call) {
    long startNanos = System.nanoTime();
    Tracer tracer = Argos.tracer();
    HttpUrl url = request.url();
    String host = url.host();
    int port = url.port();
    String existingTraceparent = request.header(TRACEPARENT);
    String[] headers =
        tracer.outgoingHeaders(context, host, existingTraceparent, request.header(TRACESTATE));
    Request sent = request;
    String preserved = null;
    if (headers != null) {
      if (headers[0] == null) {
        preserved = existingTraceparent;
      }
      if (headers[0] != null || headers[1] != null) {
        Request.Builder builder = request.newBuilder();
        if (headers[0] != null) {
          builder.header(TRACEPARENT, headers[0]);
        }
        if (headers[1] != null) {
          builder.header(TRACESTATE, headers[1]);
        }
        sent = builder.build();
      }
    }
    long hostId = tracer.hostId(url.scheme(), host, port);
    String hostText = host.indexOf(':') >= 0 ? "[" + host + "]" : host;
    call[0] =
        tracer.httpStart(
            context,
            hostId,
            request.method(),
            port == HttpUrl.defaultPort(url.scheme()) ? hostText : hostText + ":" + port,
            url.encodedPath(),
            preserved,
            startNanos);
    call[1] = hostId;
    call[2] = startNanos;
    return sent;
  }

  /**
   * Slack below the call timeout for {@link #classifiable}: the call's timeout starts a few
   * microseconds before this interceptor's start time (more under a pause between the two).
   */
  static final long CALL_TIMEOUT_SLACK_NANOS = 20_000_000L;

  /**
   * The error the tracer classifies for {@code error}; the application gets OkHttp's own exception
   * unchanged either way. OkHttp's {@code callTimeout} (3.12+) ends a call by cancelling it: inside
   * the interceptor chain the in-flight I/O fails with whatever the cancellation causes (a {@code
   * SocketException} "Socket closed", {@code IOException} "Canceled"), and only after the chain
   * does OkHttp turn it into the plain {@code InterruptedIOException("timeout")} the application
   * sees, which by class alone would be OTHER. ADR-0010 C-224 (1) makes OkHttp's call timeout
   * TIMEOUT (C-223 (3)) and nothing else: the error came out of this call's chain, the call is
   * cancelled, a call timeout is set ({@code Call.timeout()}) and at least that long (less {@link
   * #CALL_TIMEOUT_SLACK_NANOS}) has passed since the step started. Then a new {@code
   * SocketTimeoutException} stands in for the error; the tracer reads its class name only and drops
   * it. An application's own {@code cancel()} before the call timeout, or an exception of the same
   * look thrown without one, keeps {@code error}, as does any failure reading the call (OkHttp
   * before 3.12 has neither {@code Chain.call()} nor {@code callTimeout}).
   */
  static Throwable classifiable(
      Interceptor.Chain chain, Throwable error, long startNanos, long endNanos) {
    try {
      Call call = chain.call();
      if (error != null && call != null && call.isCanceled()) {
        long timeoutNanos = call.timeout().timeoutNanos();
        if (timeoutNanos > 0
            && endNanos - startNanos
                >= timeoutNanos - Math.min(CALL_TIMEOUT_SLACK_NANOS, timeoutNanos / 2)) {
          return new SocketTimeoutException("timeout");
        }
      }
    } catch (Throwable t) {
      // OkHttp before 3.12 (no Chain.call() or Call.timeout()): classified by class.
    }
    return error;
  }

  /** Closes the HTTP step; never throws. */
  static void end(TraceContext context, long[] call, int status, Throwable error, long endNanos) {
    try {
      Argos.tracer().httpEnd(context, (int) call[0], call[1], status, error, call[2], endNanos);
    } catch (Throwable t) {
      Argos.tracer().moduleError(HttpClientOkHttpModule.MODULE_ID, t);
    }
  }
}
