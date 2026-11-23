/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.httpclient.jdk11;

import io.argos.agent.bootstrap.Argos;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.bootstrap.Tracer;
import io.argos.agent.instrumentation.api.ModuleIds;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;

/**
 * The request that {@link HttpClientSendAdvice} hands to {@code HttpClientImpl} instead of the
 * application's (10 §6.5 {@code ArgosHttpRequestWrapper}): every method delegates to the
 * application's request, except {@link #headers()}, which adds the propagation headers that {@code
 * Tracer.outgoingHeaders} decided ({@code traceparent} replaced only when the application set none
 * or {@code propagation.preserve-incoming} is false; nothing for {@code
 * propagation.exclude-hosts}). The JDK copies the request into its own {@code HttpRequestImpl}
 * right away, so the application never sees this object. It also carries the HTTP step of the call
 * until it ends.
 *
 * <p>End of the step, once:
 *
 * <ul>
 *   <li>{@code send}: at the exit of {@code send}, with the response status or the exception;
 *   <li>{@code sendAsync}: on the completion of the exchange's internal future ({@code
 *       MultiExchange.responseAsync}, handed over by {@link MultiExchangeAdvice}). This object is
 *       registered on that future (as a {@link BiConsumer}, after the JDK's own dependent) at the
 *       exit of {@code sendAsync}, so it runs before the future returned to the application
 *       completes: a transaction that waits for the response always has the closed step (U-10). An
 *       exchange that completes while this is being registered is ended at the exit of {@code
 *       sendAsync} instead (once, whichever thread is first). The returned future is never touched.
 * </ul>
 *
 * <p>Helper rules (10 §5.4): injected into the platform class loader (where {@code java.net.http}
 * lives), bootstrap API and JDK types only, no static state; the request and the context are
 * instance fields of a short-lived object, never stored in a {@code ContextStore}. Implements
 * {@link BiPredicate} only as the accept-all header filter of {@code HttpHeaders.of}. Header values
 * are never logged (SEC-26).
 */
public final class Jdk11RequestHelper extends HttpRequest
    implements BiPredicate<String, String>, BiConsumer<Object, Throwable> {
  private static final int MODULE_ID = ModuleIds.HTTPCLIENT_JDK11;

  private final HttpRequest delegate;
  private final HttpHeaders headers;
  private final TraceContext context;
  private final int seq;
  private final long hostId;
  private final long startNanos;
  private final boolean async;

  /** The exchange's internal future ({@code sendAsync} only), until the step is handed to it. */
  private volatile CompletableFuture<?> exchange;

  /** The step ends once, whichever thread gets here first (see {@link #end}). */
  private final AtomicBoolean ended = new AtomicBoolean();

  private Jdk11RequestHelper(
      HttpRequest delegate,
      String traceparent,
      String tracestate,
      TraceContext context,
      int seq,
      long hostId,
      long startNanos,
      boolean async) {
    this.delegate = delegate;
    this.context = context;
    this.seq = seq;
    this.hostId = hostId;
    this.startNanos = startNanos;
    this.async = async;
    this.headers = HttpHeaders.of(merge(delegate.headers(), traceparent, tracestate), this);
  }

  /**
   * The wrapper of {@code request} with the propagation headers and an open HTTP step, or null
   * outside a transaction (and for a request that is already a wrapper). Never throws.
   */
  public static Jdk11RequestHelper start(HttpRequest request, boolean async) {
    try {
      if (request == null || request instanceof Jdk11RequestHelper) {
        return null;
      }
      Tracer tracer = Argos.tracer();
      TraceContext context = tracer.current();
      if (context == null) {
        return null;
      }
      URI uri = request.uri();
      String scheme = uri == null ? null : uri.getScheme();
      String host = uri == null ? null : uri.getHost();
      int port = uri == null ? -1 : uri.getPort();
      HttpHeaders original = request.headers();
      String appTraceparent = original.firstValue("traceparent").orElse(null);
      String appTracestate = original.firstValue("tracestate").orElse(null);
      String[] out = tracer.outgoingHeaders(context, host, appTraceparent, appTracestate);
      String traceparent = out == null ? null : out[0];
      String tracestate = out == null ? null : out[1];
      long hostId = tracer.hostId(scheme, host, port);
      long start = System.nanoTime();
      int seq =
          tracer.httpStart(
              context,
              hostId,
              request.method(),
              host == null ? null : port < 0 ? host : host + ":" + port,
              uri == null ? null : uri.getRawPath(),
              traceparent == null ? appTraceparent : null,
              start);
      return new Jdk11RequestHelper(
          request, traceparent, tracestate, context, seq, hostId, start, async);
    } catch (Throwable t) {
      Argos.tracer().moduleError(MODULE_ID, t);
      return null;
    }
  }

  /** The application's headers with {@code traceparent}/{@code tracestate} set when not null. */
  private static Map<String, List<String>> merge(
      HttpHeaders original, String traceparent, String tracestate) {
    Map<String, List<String>> map =
        new TreeMap<String, List<String>>(String.CASE_INSENSITIVE_ORDER);
    map.putAll(original.map());
    if (traceparent != null) {
      map.put("traceparent", Collections.singletonList(traceparent));
    }
    if (tracestate != null) {
      map.put("tracestate", Collections.singletonList(tracestate));
    }
    return map;
  }

  /** {@link MultiExchangeAdvice}: the exchange of this request started. */
  public void exchangeStarted(CompletableFuture<?> future) {
    if (async) {
      exchange = future;
    }
  }

  /**
   * The exit of {@code send} ({@code result} the response) or {@code sendAsync} ({@code result} the
   * returned future, never touched beyond {@code isDone}/{@code getNow} when no exchange exists).
   * Never throws.
   */
  public void end(Object result, Throwable error) {
    try {
      if (!async || error != null) {
        finish(error == null ? result : null, error);
        return;
      }
      CompletableFuture<?> internal = exchange;
      exchange = null;
      if (internal != null) {
        // Registered after the JDK's own dependent, so it runs first when the exchange completes.
        // The dependent future is dropped on purpose: accept() never throws.
        @SuppressWarnings("FutureReturnValueIgnored")
        Object unused = internal.whenComplete(this);
        if (internal.isDone()) {
          // The exchange completed while this was being registered: the completing thread may have
          // run the JDK's dependent (and so the returned future) before it took this one. End the
          // step here, before sendAsync returns; the late accept() is then a no-op (`ended`).
          finishWith(internal);
        }
        return;
      }
      // No exchange was created (a closed client answers with an already failed future).
      if (result instanceof CompletableFuture && ((CompletableFuture<?>) result).isDone()) {
        finishWith((CompletableFuture<?>) result);
      } else {
        finish(null, null);
      }
    } catch (Throwable t) {
      Argos.tracer().moduleError(MODULE_ID, t);
    }
  }

  /** Ends the step with the outcome of the completed {@code future} (never waits). */
  private void finishWith(CompletableFuture<?> future) {
    Object response = null;
    Throwable failure = null;
    try {
      response = future.getNow(null);
    } catch (CompletionException e) {
      failure = e.getCause() == null ? e : e.getCause();
    } catch (CancellationException e) {
      failure = e;
    }
    finish(response, failure);
  }

  /** Completion of the exchange's internal future ({@code sendAsync}). Never throws. */
  @Override
  public void accept(Object response, Throwable error) {
    try {
      finish(response, error);
    } catch (Throwable t) {
      Argos.tracer().moduleError(MODULE_ID, t);
    }
  }

  private void finish(Object response, Throwable error) {
    if (!ended.compareAndSet(false, true)) {
      return;
    }
    int status = response instanceof HttpResponse ? ((HttpResponse<?>) response).statusCode() : 0;
    Argos.tracer()
        .httpEnd(
            context, seq, hostId, status, status > 0 ? null : error, startNanos, System.nanoTime());
  }

  /** The header filter of {@code HttpHeaders.of}: the JDK validates the headers itself. */
  @Override
  public boolean test(String name, String value) {
    return true;
  }

  @Override
  public Optional<BodyPublisher> bodyPublisher() {
    return delegate.bodyPublisher();
  }

  @Override
  public String method() {
    return delegate.method();
  }

  @Override
  public Optional<Duration> timeout() {
    return delegate.timeout();
  }

  @Override
  public boolean expectContinue() {
    return delegate.expectContinue();
  }

  @Override
  public URI uri() {
    return delegate.uri();
  }

  @Override
  public Optional<HttpClient.Version> version() {
    return delegate.version();
  }

  @Override
  public HttpHeaders headers() {
    return headers;
  }

  @Override
  public String toString() {
    return delegate.toString();
  }
}
